package dev.vintagepc.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vintagepc.VintagePC;
import dev.vintagepc.net.LanPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клиентский мост виртуальной LAN.
 *
 *   JS в MCEF ⇄ ws://127.0.0.1:PORT/lan (LocalHttpServer) ⇄ LanBridge ⇄ пакеты MC ⇄ LanHub на сервере
 *
 * Протокол WebSocket:
 *   текст  JS→Java : {"op":"join","ch":"eth:home"} | {"op":"leave","ch":"..."}
 *   текст  Java→JS : {"op":"hello","player":"Steve","online":true} | {"op":"online","value":bool}
 *   бинарь JS→Java : [u8 len][канал UTF-8][данные]
 *   бинарь Java→JS : [u8 len][канал][u8 len][ник отправителя][данные]
 *
 * Несколько компьютеров у одного игрока делят одну подписку на сервере (счётчик ссылок)
 * и видят друг друга локально — как два ПК в одном свитче.
 */
public final class LanBridge {
    private static final Set<LocalHttpServer.Conn> conns = ConcurrentHashMap.newKeySet();
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

    // ---------- события подключения к серверу MC ----------

    static void onLogin() {
        online = true;
        synchronized (refs) { for (String ch : refs.keySet()) sendToServer(new LanPayloads.Membership(ch, true)); }
        broadcastText("{\"op\":\"online\",\"value\":true,\"player\":" + jsonStr(playerName()) + "}");
    }

    static void onLogout() {
        online = false; // сервер сам забудет подписки; refs сохраняем, чтобы переподписаться
        broadcastText("{\"op\":\"online\",\"value\":false}");
    }

    // ---------- входящие от сервера MC ----------

    public static void onDeliver(LanPayloads.DeliverS2C p) {
        byte[] frame = frame(p.channel(), p.sender(), p.data());
        for (var c : conns) if (subs(c).contains(p.channel())) c.sendBinary(frame);
    }

    // ---------- WebSocket ----------

    private static final class Handler implements LocalHttpServer.WsHandler {
        @Override public void onOpen(LocalHttpServer.Conn c) {
            if (!c.path.equals("/lan")) { c.close(); return; }
            c.attrs.put("subs", ConcurrentHashMap.newKeySet());
            conns.add(c);
            c.sendText("{\"op\":\"hello\",\"player\":" + jsonStr(playerName()) + ",\"online\":" + online + "}");
        }

        @Override public void onText(LocalHttpServer.Conn c, String text) {
            JsonObject o;
            try { o = JsonParser.parseString(text).getAsJsonObject(); } catch (Exception e) { return; }
            String op = o.has("op") ? o.get("op").getAsString() : "";
            String ch = o.has("ch") ? o.get("ch").getAsString() : "";
            if (op.equals("shutdown") && o.has("pc")) { // «Пуск → Завершение работы»
                net.minecraft.core.BlockPos pos = PcClient.fromKey(o.get("pc").getAsString());
                if (pos != null) Minecraft.getInstance().execute(() -> PcClient.shutdown(pos));
                return;
            }
            if (!LanPayloads.validChannel(ch)) return;
            Set<String> subs = subs(c);
            if (op.equals("join") && subs.size() < 16 && subs.add(ch)) ref(ch, +1);
            else if (op.equals("leave") && subs.remove(ch)) ref(ch, -1);
        }

        @Override public void onBinary(LocalHttpServer.Conn c, byte[] msg) {
            if (msg.length < 2) return;
            int n = msg[0] & 0xFF;
            if (msg.length < 1 + n) return;
            String ch = new String(msg, 1, n, StandardCharsets.UTF_8);
            if (!subs(c).contains(ch)) return;
            byte[] data = Arrays.copyOfRange(msg, 1 + n, msg.length);
            if (data.length > LanPayloads.MAX_DATA) return;
            // Локальная доставка другим ПК этого же игрока
            byte[] local = frame(ch, playerName(), data);
            for (var o : conns) if (o != c && subs(o).contains(ch)) o.sendBinary(local);
            if (online) sendToServer(new LanPayloads.SendC2S(ch, data));
        }

        @Override public void onClose(LocalHttpServer.Conn c) {
            conns.remove(c);
            for (String ch : subs(c)) ref(ch, -1);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> subs(LocalHttpServer.Conn c) {
        return (Set<String>) c.attrs.getOrDefault("subs", Set.of());
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

    private static void broadcastText(String s) { for (var c : conns) c.sendText(s); }

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
