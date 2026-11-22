package dev.vintagepc.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vintagepc.VintagePC;
import dev.vintagepc.net.LanPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Клиентский мост виртуальной LAN.
 *
 *   JS в MCEF ⇄ ws://127.0.0.1:PORT/lan?pc=KEY (LocalHttpServer) ⇄ LanBridge ⇄ пакеты MC ⇄ LanHub на сервере
 *
 * Страница называет каналы локально ("eth:lan", "room:chat-lan"), а мост привязывает их к физической
 * сети ПК, которую сервер вычислил по кабелям: "room:chat-lan" → "room:1a2b3c4d~chat-lan".
 * Кабель выдернули — каналы отключаются; воткнули в другой роутер — переподключаются к новой сети.
 *
 * Протокол WebSocket:
 *   текст  JS→Java : {"op":"join","ch":"eth:lan"} | {"op":"leave","ch":"..."} | {"op":"shutdown","pc":KEY}
 *   текст  Java→JS : {"op":"hello",...} | {"op":"online","value":bool} | {"op":"link","up":bool,"label":..,"pcs":n}
 *   бинарь JS→Java : [u8 len][канал UTF-8][данные]
 *   бинарь Java→JS : [u8 len][канал][u8 len][ник отправителя][данные]
 */
public final class LanBridge {
    private static final Pattern LOCAL = Pattern.compile("(eth|room):[A-Za-z0-9_.-]{1,48}");

    private static final class St {
        final BlockPos pc;
        volatile String net = "";
        final Set<String> subs = ConcurrentHashMap.newKeySet();
        St(BlockPos pc) { this.pc = pc; }
    }

    private static final Map<LocalHttpServer.Conn, St> conns = new ConcurrentHashMap<>();
    private static final Map<String, Integer> refs = new HashMap<>();
    private static LocalHttpServer server;
    private static String token;
    private static volatile boolean online;

    /** Корень данных игрока: .minecraft/vintagepc/ (cartridges/, engines/, music/, cache/). */
    public static java.nio.file.Path dataRoot() {
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("vintagepc");
    }

    /** Запускает локальный сервер (лениво) и возвращает URL оболочки для конкретного ПК. */
    public static synchronized String shellUrl(String pcKey) {
        if (server == null) {
            byte[] r = new byte[16];
            new SecureRandom().nextBytes(r);
            token = HexFormat.of().formatHex(r);
            try {
                java.nio.file.Path root = dataRoot();
                for (String d : new String[]{"cartridges", "engines", "music", "cache"})
                    java.nio.file.Files.createDirectories(root.resolve(d));
                server = new LocalHttpServer(token, new WebRoutes(root), new Handler());
            } catch (Exception e) {
                VintagePC.LOG.error("Не удалось запустить локальный сервер Vintage PC", e);
                return "about:blank";
            }
            VintagePC.LOG.info("Vintage PC local server on {}", server.origin());
        }
        return server.origin() + "/?t=" + token + "&pc=" + pcKey;
    }

    static String full(String net, String local) {
        int c = local.indexOf(':');
        return local.substring(0, c + 1) + net + "~" + local.substring(c + 1);
    }

    // ---------- события подключения к серверу MC ----------

    static void onLogin() {
        online = true;
        broadcastText("{\"op\":\"online\",\"value\":true,\"player\":" + jsonStr(playerName()) + "}");
    }

    static void onLogout() {
        online = false;
        synchronized (refs) { refs.clear(); }  // сервер забыл подписки; восстановятся по NetState
        for (var e : conns.entrySet()) { e.getValue().net = ""; sendLink(e.getKey(), null); }
        broadcastText("{\"op\":\"online\",\"value\":false}");
    }

    /** Сервер сообщил, к какой сети подключён ПК (или что кабель выдернут). */
    static void netChanged(BlockPos pos, PcClient.NetInfo info) {
        String now = info == null ? "" : info.id();
        for (var e : conns.entrySet()) {
            St st = e.getValue();
            if (!st.pc.equals(pos)) continue;
            String old = st.net;
            if (!old.equals(now)) {
                for (String name : st.subs) if (!old.isEmpty()) ref(full(old, name), -1);
                st.net = now;
                for (String name : st.subs) if (!now.isEmpty()) ref(full(now, name), +1);
            }
            sendLink(e.getKey(), info);
        }
    }

    private static void sendLink(LocalHttpServer.Conn c, PcClient.NetInfo info) {
        boolean up = info != null && !info.id().isEmpty();
        c.sendText("{\"op\":\"link\",\"up\":" + up + ",\"label\":" + jsonStr(up ? info.label() : "")
                + ",\"pcs\":" + (info == null ? 0 : info.pcs()) + "}");
    }

    // ---------- входящие от сервера MC ----------

    public static void onDeliver(LanPayloads.DeliverS2C p) {
        String net = LanPayloads.netOf(p.channel());
        if (net == null) return;
        int c = p.channel().indexOf(':'), t = p.channel().indexOf('~');
        String local = p.channel().substring(0, c + 1) + p.channel().substring(t + 1);
        byte[] frame = frame(local, p.sender(), p.data());
        for (var e : conns.entrySet())
            if (net.equals(e.getValue().net) && e.getValue().subs.contains(local)) e.getKey().sendBinary(frame);
    }

    // ---------- WebSocket ----------

    private static final class Handler implements LocalHttpServer.WsHandler {
        @Override public void onOpen(LocalHttpServer.Conn c) {
            BlockPos pc = PcClient.fromKey(c.query.getOrDefault("pc", ""));
            if (!c.path.equals("/lan") || pc == null) { c.close(); return; }
            St st = new St(pc);
            PcClient.NetInfo info = PcClient.netInfo(pc);
            st.net = info == null ? "" : info.id();
            conns.put(c, st);
            c.sendText("{\"op\":\"hello\",\"player\":" + jsonStr(playerName()) + ",\"online\":" + online + "}");
            sendLink(c, info);
        }

        @Override public void onText(LocalHttpServer.Conn c, String text) {
            St st = conns.get(c);
            if (st == null) return;
            JsonObject o;
            try { o = JsonParser.parseString(text).getAsJsonObject(); } catch (Exception e) { return; }
            String op = o.has("op") ? o.get("op").getAsString() : "";
            if (op.equals("shutdown")) { // «Пуск → Завершение работы»
                Minecraft.getInstance().execute(() -> PcClient.shutdown(st.pc));
                return;
            }
            String ch = o.has("ch") ? o.get("ch").getAsString() : "";
            if (!LOCAL.matcher(ch).matches()) return;
            if (op.equals("join") && st.subs.size() < 16 && st.subs.add(ch)) { if (!st.net.isEmpty()) ref(full(st.net, ch), +1); }
            else if (op.equals("leave") && st.subs.remove(ch)) { if (!st.net.isEmpty()) ref(full(st.net, ch), -1); }
        }

        @Override public void onBinary(LocalHttpServer.Conn c, byte[] msg) {
            St st = conns.get(c);
            if (st == null || st.net.isEmpty() || msg.length < 2) return; // кабель не подключён — пакет в никуда
            int n = msg[0] & 0xFF;
            if (msg.length < 1 + n) return;
            String ch = new String(msg, 1, n, StandardCharsets.UTF_8);
            if (!st.subs.contains(ch)) return;
            byte[] data = Arrays.copyOfRange(msg, 1 + n, msg.length);
            if (data.length > LanPayloads.MAX_DATA) return;
            // Локальная доставка другим ПК этого же игрока в той же сети
            byte[] local = frame(ch, playerName(), data);
            for (var e : conns.entrySet())
                if (e.getKey() != c && st.net.equals(e.getValue().net) && e.getValue().subs.contains(ch)) e.getKey().sendBinary(local);
            if (online) sendToServer(new LanPayloads.SendC2S(full(st.net, ch), data));
        }

        @Override public void onClose(LocalHttpServer.Conn c) {
            St st = conns.remove(c);
            if (st != null && !st.net.isEmpty()) for (String ch : st.subs) ref(full(st.net, ch), -1);
        }
    }

    private static void ref(String ch, int delta) {
        synchronized (refs) {
            int v = refs.getOrDefault(ch, 0) + delta;
            if (v <= 0) refs.remove(ch); else refs.put(ch, v);
            if (delta > 0 && v == 1) sendToServer(new LanPayloads.Membership(ch, true));
            if (delta < 0 && v == 0) sendToServer(new LanPayloads.Membership(ch, false));
        }
    }

    private static void sendToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload p) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.getConnection() != null) PacketDistributor.sendToServer(p); });
    }

    private static void broadcastText(String s) { for (var c : conns.keySet()) c.sendText(s); }

    private static byte[] frame(String ch, String sender, byte[] data) {
        byte[] a = ch.getBytes(StandardCharsets.UTF_8), b = sender.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + a.length + b.length + data.length];
        out[0] = (byte) a.length;
        System.arraycopy(a, 0, out, 1, a.length);
        out[1 + a.length] = (byte) b.length;
        System.arraycopy(b, 0, out, 2 + a.length, b.length);
        System.arraycopy(data, 0, out, 2 + a.length + b.length, data.length);
        return out;
    }

    private static String playerName() {
        var u = Minecraft.getInstance().getUser();
        return u != null ? u.getName() : "Player";
    }

    private static String jsonStr(String s) { return new com.google.gson.JsonPrimitive(s).toString(); }

    private LanBridge() {}
}
