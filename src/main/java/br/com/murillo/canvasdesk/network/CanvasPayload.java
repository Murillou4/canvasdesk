package br.com.murillo.canvasdesk.network;

import br.com.murillo.canvasdesk.CanvasDesk;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.*;

/** Bounded fragments keep both directions below Fabric's payload size ceiling. */
public record CanvasPayload(String transfer, int index, int count, String json) implements CustomPacketPayload {
    public static final Type<CanvasPayload> TYPE = new Type<>(CanvasDesk.id("canvas"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CanvasPayload> CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeUtf(p.transfer, 40); buf.writeVarInt(p.index); buf.writeVarInt(p.count); buf.writeUtf(p.json, 6000); },
            buf -> new CanvasPayload(buf.readUtf(40), buf.readVarInt(), buf.readVarInt(), buf.readUtf(6000)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static List<CanvasPayload> split(String json) {
        String transfer = UUID.randomUUID().toString();
        int count = Math.max(1, (json.length() + 5999) / 6000);
        if (count > 1024) throw new IllegalArgumentException("Canvas response too large; request a smaller region");
        List<CanvasPayload> packets = new ArrayList<>(count);
        for (int i=0;i<count;i++) packets.add(new CanvasPayload(transfer, i, count, json.substring(i*6000, Math.min(json.length(), (i+1)*6000))));
        return packets;
    }
    public static final class Assembler {
        private final Map<String, Partial> pending = new HashMap<>();
        public String accept(CanvasPayload payload) {
            long now = System.currentTimeMillis();
            pending.values().removeIf(part -> now - part.created > 15000);
            if (payload.count < 1 || payload.count > 1024 || payload.index < 0 || payload.index >= payload.count)
                throw new IllegalArgumentException("Invalid packet fragments");
            if (!pending.containsKey(payload.transfer) && pending.size() >= 4) throw new IllegalArgumentException("Too many pending transfers");
            Partial part = pending.computeIfAbsent(payload.transfer, k -> new Partial(payload.count));
            if (part.parts.length != payload.count) throw new IllegalArgumentException("Changed transfer length");
            part.parts[payload.index] = payload.json;
            for (String value : part.parts) if (value == null) return null;
            pending.remove(payload.transfer);
            return String.join("", part.parts);
        }
        private static final class Partial {
            final long created = System.currentTimeMillis(); final String[] parts;
            Partial(int size) { parts = new String[size]; }
        }
    }
}
