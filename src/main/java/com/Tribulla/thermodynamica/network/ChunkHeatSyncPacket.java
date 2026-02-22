package com.Tribulla.thermodynamica.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ChunkHeatSyncPacket {

    private final ChunkPos chunkPos;
    private final Map<BlockPos, Double> temperatures;

    public ChunkHeatSyncPacket(ChunkPos chunkPos, Map<BlockPos, Double> temperatures) {
        this.chunkPos = chunkPos;
        this.temperatures = temperatures;
    }

    public static void encode(ChunkHeatSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.chunkPos.x);
        buf.writeInt(packet.chunkPos.z);
        buf.writeVarInt(packet.temperatures.size());
        for (Map.Entry<BlockPos, Double> entry : packet.temperatures.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeDouble(entry.getValue());
        }
    }

    public static ChunkHeatSyncPacket decode(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int cz = buf.readInt();
        int count = buf.readVarInt();
        Map<BlockPos, Double> temps = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            double celsius = buf.readDouble();
            temps.put(pos, celsius);
        }
        return new ChunkHeatSyncPacket(new ChunkPos(cx, cz), temps);
    }

    public static void handle(ChunkHeatSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            for (Map.Entry<BlockPos, Double> entry : packet.temperatures.entrySet()) {
                ClientHeatCache.update(entry.getKey(), entry.getValue(), -1);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
