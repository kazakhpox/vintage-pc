package dev.vintagepc.net;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

/**
 * Сервер: кто «сидит» за каким ПК.
 *
 * Первый игрок, включивший ПК, — хост: у него крутится браузер с ОС и играми.
 * Остальные, кликнувшие по тому же ПК, становятся зрителями: получают JPEG-кадры экрана
 * хоста и могут по очереди (F8) брать управление — классический hotseat за одним монитором.
 * Хост в любой момент может забрать управление обратно.
 */
public final class PcSessions {
    private static final double VIEW_RANGE = 8, AMBIENT_RANGE = 24, HOST_TAKEOVER_RANGE = 48;
    private static final int MAX_FRAME_BYTES_PER_TICK = 96 * 1024;
    private static final int MAX_INPUTS_PER_TICK = 40;

    private static final class Session {
        UUID host;
        String hostName;
        UUID controller; // null — управляет хост
        final Set<UUID> viewers = new LinkedHashSet<>();
        /** Игроки рядом, которые видят монитор в мире (получают кадры с пониженной частотой). */
        final Set<UUID> ambient = new HashSet<>();
        int frameBudget, inputBudget;
        NetTopology.Network net = NetTopology.Network.NONE;
        String via = "";
        int netPcs;
        String towersSig = "";
        boolean card; long target;   // последние известные данные корпуса (чанк ПК может выгрузиться)
    }

    private static final Map<GlobalPos, Session> sessions = new HashMap<>();
    private static int tick;

    public static void init(IEventBus bus) {
        bus.addListener((ServerTickEvent.Post e) -> {
            for (Session s : sessions.values()) { s.frameBudget = 0; s.inputBudget = 0; }
            if (++tick % 20 == 0) { checkRange(e.getServer()); refreshNetworks(e.getServer()); }
            blinkRouters(e.getServer());
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) leaveAll(p);
        });
        bus.addListener((ServerStoppedEvent e) -> sessions.clear());
    }

    /** Вызывается из RetroPcBlock на сервере, когда игрок кликает по ПК. */
    public static void use(ServerPlayer p, BlockPos pos) {
        GlobalPos key = GlobalPos.of(p.level().dimension(), pos.immutable());
        Session s = sessions.get(key);
        ServerPlayer host = s == null ? null : p.server.getPlayerList().getPlayer(s.host);
        boolean hostGone = host == null || host.level().dimension() != key.dimension()
                || host.distanceToSqr(pos.getCenter()) > HOST_TAKEOVER_RANGE * HOST_TAKEOVER_RANGE;

        if (s == null || s.host.equals(p.getUUID()) || hostGone) {
            if (s != null && !s.host.equals(p.getUUID())) end(p.server, key, s); // старый хост ушёл — ПК переходит новому
            if (s == null || !s.host.equals(p.getUUID())) {
                s = new Session();
                s.host = p.getUUID();
                s.hostName = p.getGameProfile().getName();
                sessions.put(key, s);
            }
            send(p, new PcPayloads.Open(pos, true, s.hostName));
            updateNet(p.server, key, s, true);
        } else {
            s.viewers.add(p.getUUID());
            send(p, new PcPayloads.Open(pos, false, s.hostName));
        }
        broadcastState(p.server, key, s);
    }

    /** Блок сломан. */
    public static void removed(Level level, BlockPos pos) {
        if (level.getServer() == null) return;
        GlobalPos key = GlobalPos.of(level.dimension(), pos);
        Session s = sessions.get(key);
        if (s != null) end(level.getServer(), key, s);
    }

    // ---------- обработчики пакетов ----------

    static void onLeave(PcPayloads.Leave p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        GlobalPos key = GlobalPos.of(sp.level().dimension(), p.pos());
        Session s = sessions.get(key);
        if (s == null) return;
        if (s.host.equals(sp.getUUID())) end(sp.server, key, s);
        else if (s.viewers.remove(sp.getUUID())) {
            if (sp.getUUID().equals(s.controller)) s.controller = null;
            broadcastState(sp.server, key, s);
        }
    }

    static void onControl(PcPayloads.Control p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        GlobalPos key = GlobalPos.of(sp.level().dimension(), p.pos());
        Session s = sessions.get(key);
        if (s == null) return;
        UUID id = sp.getUUID();
        if (s.host.equals(id)) s.controller = null;                       // хост всегда может забрать управление
        else if (s.viewers.contains(id)) s.controller = p.take() ? id : (id.equals(s.controller) ? null : s.controller);
        else return;
        broadcastState(sp.server, key, s);
    }

    static void onFrame(PcPayloads.Frame p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        Session s = sessions.get(GlobalPos.of(sp.level().dimension(), p.pos()));
        if (s == null || !s.host.equals(sp.getUUID()) || (s.viewers.isEmpty() && s.ambient.isEmpty())) return;
        if ((s.frameBudget += p.data().length) > MAX_FRAME_BYTES_PER_TICK) return;
        Set<UUID> to = new HashSet<>(s.viewers);
        to.addAll(s.ambient);
        for (UUID v : to) {
            ServerPlayer vp = sp.server.getPlayerList().getPlayer(v);
            if (vp != null) send(vp, p);
        }
    }

    static void onInput(PcPayloads.Input p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        Session s = sessions.get(GlobalPos.of(sp.level().dimension(), p.pos()));
        if (s == null || !sp.getUUID().equals(s.controller) || ++s.inputBudget > MAX_INPUTS_PER_TICK) return;
        ServerPlayer host = sp.server.getPlayerList().getPlayer(s.host);
        if (host != null) send(host, p);
    }

    // ---------- физическая сеть ----------

    /** Есть ли у игрока включённый ПК, подключённый кабелем к сети net. */
    static boolean hostsNet(UUID player, String net) {
        if (net == null) return false;
        for (Session s : sessions.values()) if (s.host.equals(player) && net.equals(s.net.id())) return true;
        return false;
    }

    private static void refreshNetworks(net.minecraft.server.MinecraftServer server) {
        for (var e : sessions.entrySet()) updateNet(server, e.getKey(), e.getValue(), false);
    }

    /** Комплектующие ПК изменились (например, вынули Wi-Fi-карту). */
    public static void hardwareChanged(net.minecraft.world.level.Level level, BlockPos pos) {
        if (level.getServer() == null) return;
        GlobalPos key = GlobalPos.of(level.dimension(), pos);
        Session s = sessions.get(key);
        if (s != null) updateNet(level.getServer(), key, s, true);
    }

    static void onWifi(PcPayloads.Wifi p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        GlobalPos key = GlobalPos.of(sp.level().dimension(), p.pos());
        Session s = sessions.get(key);
        if (s == null || !s.host.equals(sp.getUUID())) return;
        if (!(sp.level().getBlockEntity(p.pos()) instanceof dev.vintagepc.block.RetroPcBlockEntity be)) return;
        if (p.tower() != 0 && !NetGraph.get(sp.level()).formed(BlockPos.of(p.tower()))) return;
        be.setWifiTower(p.tower());
        updateNet(sp.server, key, s, true);
    }

    /**
     * Сеть ПК: по кабелю, если он подключён к роутеру/вышке (или кроссовер), иначе — по Wi-Fi,
     * если стоит карта и выбрана собранная вышка этого измерения.
     */
    private static void updateNet(net.minecraft.server.MinecraftServer server, GlobalPos key, Session s, boolean force) {
        var level = server.getLevel(key.dimension());
        if (level == null) return;
        NetGraph g = NetGraph.get(level);
        NetTopology.Network n = NetTopology.explore(level, key.pos());
        String via = n.up() ? "cable" : "";
        var be = level.isLoaded(key.pos()) && level.getBlockEntity(key.pos()) instanceof dev.vintagepc.block.RetroPcBlockEntity b ? b : null;
        if (be != null) { s.card = be.has(dev.vintagepc.item.PcPart.WIFI); s.target = be.wifiTower(); }
        boolean card = s.card;
        long target = s.target;
        if (!n.up() && card && target != 0 && g.formed(BlockPos.of(target))) {
            n = NetTopology.explore(level, BlockPos.of(target));
            via = n.up() ? "wifi" : "";
        }
        // ПК в сети: по кабелю + подключённые по воздуху (сессии с тем же id)
        int pcs = n.pcs().size();
        if (n.up()) for (var e : sessions.entrySet())
            if (e.getValue() != s && "wifi".equals(e.getValue().via) && n.id().equals(e.getValue().net.id())) pcs++;
        if ("wifi".equals(via)) pcs++;

        List<PcPayloads.Tower> towers = new ArrayList<>();
        if (card) for (long t : g.towers()) {
            BlockPos tp = BlockPos.of(t);
            String name = g.name(tp);
            towers.add(new PcPayloads.Tower(t, name.isEmpty() ? "Wi-Fi " + NetTopology.hex(t * 0x9E3779B97F4A7C15L).substring(0, 4).toUpperCase(java.util.Locale.ROOT) : name,
                    (int) Math.sqrt(tp.distSqr(key.pos()))));
        }
        String sig = towers.toString() + card + target;

        boolean changed = !n.id().equals(s.net.id()) || !n.label().equals(s.net.label()) || pcs != s.netPcs
                || !via.equals(s.via) || !sig.equals(s.towersSig);
        String old = s.net.id();
        s.net = n; s.via = via; s.netPcs = pcs; s.towersSig = sig;
        if (!changed && !force) return;
        if (!old.equals(n.id()) && !hostsNet(s.host, old)) LanHub.dropNet(s.host, old);
        ServerPlayer host = server.getPlayerList().getPlayer(s.host);
        if (host != null) send(host, new PcPayloads.NetState(key.pos(), n.id(), n.label(), pcs, via, card, target, towers));
    }

    /** Время последнего пакета через роутер — для мигания светодиодов. */
    private static final Map<GlobalPos, Integer> routerActivity = new HashMap<>();

    static void activity(String net) {
        if (net == null) return;
        for (var e : sessions.entrySet()) {
            if (!net.equals(e.getValue().net.id())) continue;
            for (BlockPos r : e.getValue().net.hubs()) routerActivity.put(GlobalPos.of(e.getKey().dimension(), r), tick);
            return;
        }
    }

    private static void blinkRouters(net.minecraft.server.MinecraftServer server) {
        for (var it = routerActivity.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            var level = server.getLevel(e.getKey().dimension());
            BlockPos pos = e.getKey().pos();
            if (level == null || !level.isLoaded(pos)) { it.remove(); continue; }
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof dev.vintagepc.block.RouterBlock)) { it.remove(); continue; }
            boolean recent = tick - e.getValue() < 10;
            boolean on = recent && (tick / 2) % 2 == 0;                // мигание, пока идёт трафик
            if (state.getValue(dev.vintagepc.block.RouterBlock.ACTIVE) != on)
                level.setBlock(pos, state.setValue(dev.vintagepc.block.RouterBlock.ACTIVE, on), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            if (!recent) it.remove();
        }
    }

    // ---------- служебное ----------

    private static void leaveAll(ServerPlayer p) {
        for (var it = sessions.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Session s = e.getValue();
            s.ambient.remove(p.getUUID());
            if (s.host.equals(p.getUUID())) {
                LanHub.dropNet(s.host, s.net.id());
                for (UUID v : participants(s)) {
                    ServerPlayer vp = p.server.getPlayerList().getPlayer(v);
                    if (vp != null) send(vp, new PcPayloads.Closed(e.getKey().pos()));
                }
                it.remove();
            } else if (s.viewers.remove(p.getUUID())) {
                if (p.getUUID().equals(s.controller)) s.controller = null;
                broadcastState(p.server, e.getKey(), s);
            }
        }
    }

    private static void checkRange(net.minecraft.server.MinecraftServer server) {
        for (var e : new ArrayList<>(sessions.entrySet())) {
            Session s = e.getValue();
            BlockPos pos = e.getKey().pos();
            boolean changed = false;
            for (UUID v : new ArrayList<>(s.viewers)) {
                ServerPlayer vp = server.getPlayerList().getPlayer(v);
                if (vp == null || vp.level().dimension() != e.getKey().dimension()
                        || vp.distanceToSqr(pos.getCenter()) > VIEW_RANGE * VIEW_RANGE) {
                    s.viewers.remove(v);
                    if (v.equals(s.controller)) s.controller = null;
                    if (vp != null) send(vp, new PcPayloads.Closed(pos));
                    changed = true;
                }
            }
            // Кто стоит рядом и видит монитор в мире
            Set<UUID> near = new HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers())
                if (!p.getUUID().equals(s.host) && p.level().dimension() == e.getKey().dimension()
                        && p.distanceToSqr(pos.getCenter()) <= AMBIENT_RANGE * AMBIENT_RANGE) near.add(p.getUUID());
            for (UUID gone : s.ambient) if (!near.contains(gone) && !s.viewers.contains(gone)) {
                ServerPlayer gp = server.getPlayerList().getPlayer(gone);
                if (gp != null) send(gp, new PcPayloads.Closed(pos)); // освободить текстуру у ушедшего
            }
            if (!near.equals(s.ambient)) { s.ambient.clear(); s.ambient.addAll(near); changed = true; }
            if (changed) broadcastState(server, e.getKey(), s);
        }
    }

    private static void end(net.minecraft.server.MinecraftServer server, GlobalPos key, Session s) {
        sessions.remove(key);
        if (!hostsNet(s.host, s.net.id())) LanHub.dropNet(s.host, s.net.id());
        for (UUID id : participants(s)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) send(p, new PcPayloads.Closed(key.pos()));
        }
    }

    private static void broadcastState(net.minecraft.server.MinecraftServer server, GlobalPos key, Session s) {
        String ctrl = "";
        if (s.controller != null) {
            ServerPlayer c = server.getPlayerList().getPlayer(s.controller);
            ctrl = c != null ? c.getGameProfile().getName() : "";
        }
        var state = new PcPayloads.State(key.pos(), ctrl, s.viewers.size(), s.ambient.size());
        for (UUID id : participants(s)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) send(p, state);
        }
    }

    private static List<UUID> participants(Session s) {
        Set<UUID> all = new LinkedHashSet<>(s.viewers);
        all.addAll(s.ambient);
        List<UUID> l = new ArrayList<>(all);
        l.add(s.host);
        return l;
    }

    private static void send(ServerPlayer p, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(p, payload);
    }

    private PcSessions() {}
}
