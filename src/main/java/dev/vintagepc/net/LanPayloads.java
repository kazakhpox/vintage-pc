package dev.vintagepc.net;

import dev.vintagepc.VintagePC;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Пакеты виртуальной LAN поверх соединения Minecraft.
 * Канал — "eth:&lt;сеть&gt;" (сырые Ethernet-кадры, обучаемый L2-свитч)
 * или "room:&lt;имя&gt;" (широковещательные сообщения для JS-игр, чата, лобби).
 */
public final class LanPayloads {
    public static final int MAX_CHANNEL = 64;
    public static final int MAX_DATA = 16 * 1024;

    /** Клиент → сервер: подписаться (join=true) / отписаться. */
    public record Membership(String channel, boolean join) implements CustomPacketPayload {
        public static final Type<Membership> TYPE = new Type<>(VintagePC.id("lan_membership"));
        public static final StreamCodec<FriendlyByteBuf, Membership> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeUtf(p.channel, MAX_CHANNEL); buf.writeBoolean(p.join); },
                buf -> new Membership(buf.readUtf(MAX_CHANNEL), buf.readBoolean()));
        @Override public Type<Membership> type() { return TYPE; }
    }

    /** Клиент → сервер: данные в канал. */
    public record SendC2S(String channel, byte[] data) implements CustomPacketPayload {
        public static final Type<SendC2S> TYPE = new Type<>(VintagePC.id("lan_send"));
        public static final StreamCodec<FriendlyByteBuf, SendC2S> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeUtf(p.channel, MAX_CHANNEL); buf.writeByteArray(p.data); },
                buf -> new SendC2S(buf.readUtf(MAX_CHANNEL), buf.readByteArray(MAX_DATA)));
        @Override public Type<SendC2S> type() { return TYPE; }
    }

    /** Сервер → клиент: данные из канала от другого игрока. */
    public record DeliverS2C(String channel, String sender, byte[] data) implements CustomPacketPayload {
        public static final Type<DeliverS2C> TYPE = new Type<>(VintagePC.id("lan_deliver"));
        public static final StreamCodec<FriendlyByteBuf, DeliverS2C> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeUtf(p.channel, MAX_CHANNEL); buf.writeUtf(p.sender, 32); buf.writeByteArray(p.data); },
                buf -> new DeliverS2C(buf.readUtf(MAX_CHANNEL), buf.readUtf(32), buf.readByteArray(MAX_DATA)));
        @Override public Type<DeliverS2C> type() { return TYPE; }
    }

    public static boolean validChannel(String ch) {
        return ch != null && ch.length() <= MAX_CHANNEL && ch.matches("(eth|room):[A-Za-z0-9_.-]{1,48}");
    }

    public static void register(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar("1").optional(); // optional: ванильные клиенты могут зайти на сервер
        r.playToServer(Membership.TYPE, Membership.CODEC, LanHub::onMembership);
        r.playToServer(SendC2S.TYPE, SendC2S.CODEC, LanHub::onSend);
        // Лямбда (а не ссылка на метод) — клиентский класс загрузится только при реальном вызове на клиенте.
        r.playToClient(DeliverS2C.TYPE, DeliverS2C.CODEC, (p, ctx) -> dev.vintagepc.client.LanBridge.onDeliver(p));
    }

    private LanPayloads() {}
}
