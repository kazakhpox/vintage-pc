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
    }

    private static final Map<GlobalPos, Session> sessions = new HashMap<>();
    private static int tick;

    public static void init(IEventBus bus) {
        bus.addListener((ServerTickEvent.Post e) -> {
            for (Session s : sessions.values()) { s.frameBudget = 0; s.inputBudget = 0; }
            if (++tick % 20 == 0) checkRange(e.getServer());
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

    // ---------- служебное ----------

    private static void leaveAll(ServerPlayer p) {
        for (var it = sessions.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Session s = e.getValue();
            s.ambient.remove(p.getUUID());
            if (s.host.equals(p.getUUID())) {
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
