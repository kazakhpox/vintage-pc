package dev.vintagepc.net;

import dev.vintagepc.VintagePC;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Пакеты «совместного экрана» (hotseat): один ПК, один запущенный браузер у хоста,
 * остальные игроки рядом видят его экран и по очереди берут управление.
 */
public final class PcPayloads {
    public static final int MAX_CHUNK = 15000;

    /** Сервер → клиент: открыть ПК как хост (свой браузер) или как зритель чужого экрана. */
    public record Open(BlockPos pos, boolean host, String hostName) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(VintagePC.id("pc_open"));
        public static final StreamCodec<FriendlyByteBuf, Open> CODEC = StreamCodec.of(
                (b, p) -> { b.writeBlockPos(p.pos); b.writeBoolean(p.host); b.writeUtf(p.hostName, 32); },
                b -> new Open(b.readBlockPos(), b.readBoolean(), b.readUtf(32)));
        @Override public Type<Open> type() { return TYPE; }
    }

    /** Сервер → клиент: сессия для этого клиента закончилась (ушёл далеко, ПК сломан, хост вышел). */
    public record Closed(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Closed> TYPE = new Type<>(VintagePC.id("pc_closed"));
        public static final StreamCodec<FriendlyByteBuf, Closed> CODEC = StreamCodec.of(
                (b, p) -> b.writeBlockPos(p.pos), b -> new Closed(b.readBlockPos()));
        @Override public Type<Closed> type() { return TYPE; }
    }

    /**
     * Сервер → хост: к какой сети физически подключён ПК (обход кабелей). net = "" — кабель не подключён
     * или сеть неисправна; label — имя роутера / "crossover"; pcs — сколько компьютеров в сегменте.
     */
    public record NetState(BlockPos pos, String net, String label, int pcs) implements CustomPacketPayload {
        public static final Type<NetState> TYPE = new Type<>(VintagePC.id("pc_net"));
        public static final StreamCodec<FriendlyByteBuf, NetState> CODEC = StreamCodec.of(
                (b, p) -> { b.writeBlockPos(p.pos); b.writeUtf(p.net, 8); b.writeUtf(p.label, 40); b.writeVarInt(p.pcs); },
                b -> new NetState(b.readBlockPos(), b.readUtf(8), b.readUtf(40), b.readVarInt()));
        @Override public Type<NetState> type() { return TYPE; }
    }

    /** Клиент → сервер: хост выключил ПК (или у него нет MCEF) / зритель отошёл от экрана. */
    public record Leave(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Leave> TYPE = new Type<>(VintagePC.id("pc_leave"));
        public static final StreamCodec<FriendlyByteBuf, Leave> CODEC = StreamCodec.of(
                (b, p) -> b.writeBlockPos(p.pos), b -> new Leave(b.readBlockPos()));
        @Override public Type<Leave> type() { return TYPE; }
    }

    /** Состояние сессии: кто управляет ("" — хост), сколько зрителей за экраном и сколько видят монитор рядом. */
    public record State(BlockPos pos, String controller, int viewers, int nearby) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(VintagePC.id("pc_state"));
        public static final StreamCodec<FriendlyByteBuf, State> CODEC = StreamCodec.of(
                (b, p) -> { b.writeBlockPos(p.pos); b.writeUtf(p.controller, 32); b.writeVarInt(p.viewers); b.writeVarInt(p.nearby); },
                b -> new State(b.readBlockPos(), b.readUtf(32), b.readVarInt(), b.readVarInt()));
        @Override public Type<State> type() { return TYPE; }
    }

    /** Клиент → сервер: взять (true) или отдать (false) управление. Хост с take=true забирает управление себе. */
    public record Control(BlockPos pos, boolean take) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(VintagePC.id("pc_control"));
        public static final StreamCodec<FriendlyByteBuf, Control> CODEC = StreamCodec.of(
                (b, p) -> { b.writeBlockPos(p.pos); b.writeBoolean(p.take); },
                b -> new Control(b.readBlockPos(), b.readBoolean()));
        @Override public Type<Control> type() { return TYPE; }
    }

    /** Кусок JPEG-кадра экрана (хост → сервер → зрители). */
    public record Frame(BlockPos pos, int frame, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<Frame> TYPE = new Type<>(VintagePC.id("pc_frame"));
        public static final StreamCodec<FriendlyByteBuf, Frame> CODEC = StreamCodec.of(
                (b, p) -> { b.writeBlockPos(p.pos); b.writeVarInt(p.frame); b.writeByte(p.index); b.writeByte(p.total); b.writeByteArray(p.data); },
                b -> new Frame(b.readBlockPos(), b.readVarInt(), b.readUnsignedByte(), b.readUnsignedByte(), b.readByteArray(MAX_CHUNK)));
        @Override public Type<Frame> type() { return TYPE; }
    }

    /**
     * Событие ввода (управляющий зритель → сервер → хост). Координаты мыши нормированы 0..1.
     * kind: 0 keyPress(a=key,b=scan,c=mods) 1 keyRelease 2 char(a=codepoint,c=mods)
     *       3 mousePress(a=button) 4 mouseRelease 5 mouseMove 6 wheel(x,y, a=delta*100)
     */
    public record Input(BlockPos pos, int kind, int a, int b, int c, float x, float y) implements CustomPacketPayload {
        public static final Type<Input> TYPE = new Type<>(VintagePC.id("pc_input"));
        public static final StreamCodec<ByteBuf, Input> CODEC = StreamCodec.of(
                (b, p) -> { BlockPos.STREAM_CODEC.encode(b, p.pos); b.writeByte(p.kind); b.writeInt(p.a); b.writeInt(p.b); b.writeInt(p.c); b.writeFloat(p.x); b.writeFloat(p.y); },
                b -> new Input(BlockPos.STREAM_CODEC.decode(b), b.readUnsignedByte(), b.readInt(), b.readInt(), b.readInt(), b.readFloat(), b.readFloat()));
        @Override public Type<Input> type() { return TYPE; }
    }

    private PcPayloads() {}
}
