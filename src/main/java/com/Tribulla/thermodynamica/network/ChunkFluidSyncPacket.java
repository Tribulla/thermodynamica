package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.api.ClientFluidCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ChunkFluidSyncPacket {

    public record FluidData(double celsius, double pressure, Vec3 velocity,
            BlockPos renderStatePos, Vec3 worldCenter) {
    }

    private final ChunkPos chunkPos;
    private final Map<BlockPos, FluidData> cells;

    public ChunkFluidSyncPacket(ChunkPos chunkPos, Map<BlockPos, FluidData> cells) {
        this.chunkPos = chunkPos;
        this.cells = cells;
    }

    public static void encode(ChunkFluidSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.chunkPos.x);
        buf.writeInt(packet.chunkPos.z);
        buf.writeVarInt(packet.cells.size());
        for (Map.Entry<BlockPos, FluidData> entry : packet.cells.entrySet()) {
            FluidData data = entry.getValue();
            buf.writeBlockPos(entry.getKey());
            buf.writeFloat((float) data.celsius());
            buf.writeFloat((float) data.pressure());
            buf.writeFloat((float) data.velocity().x);
            buf.writeFloat((float) data.velocity().y);
            buf.writeFloat((float) data.velocity().z);
            buf.writeBlockPos(data.renderStatePos());
            buf.writeFloat((float) data.worldCenter().x);
            buf.writeFloat((float) data.worldCenter().y);
            buf.writeFloat((float) data.worldCenter().z);
        }
    }

    public static ChunkFluidSyncPacket decode(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int cz = buf.readInt();
        int count = buf.readVarInt();
        Map<BlockPos, FluidData> cells = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            double celsius = buf.readFloat();
            double pressure = buf.readFloat();
            Vec3 velocity = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
            BlockPos renderStatePos = buf.readBlockPos();
            Vec3 worldCenter = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
            cells.put(pos, new FluidData(celsius, pressure, velocity, renderStatePos, worldCenter));
        }
        return new ChunkFluidSyncPacket(new ChunkPos(cx, cz), cells);
    }

    public static void handle(ChunkFluidSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            for (Map.Entry<BlockPos, FluidData> entry : packet.cells.entrySet()) {
                FluidData data = entry.getValue();
                ClientFluidCache.update(entry.getKey(), data.celsius(), data.pressure(), data.velocity(),
                        data.renderStatePos(), data.worldCenter());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
