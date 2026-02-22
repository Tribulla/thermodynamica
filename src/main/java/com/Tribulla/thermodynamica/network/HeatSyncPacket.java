package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.Thermodynamica;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class HeatSyncPacket {

    private final BlockPos pos;
    private final double celsius;
    private final int tierOrdinal;

    public HeatSyncPacket(BlockPos pos, double celsius, int tierOrdinal) {
        this.pos = pos;
        this.celsius = celsius;
        this.tierOrdinal = tierOrdinal;
    }

    public static void encode(HeatSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeDouble(packet.celsius);
        buf.writeVarInt(packet.tierOrdinal);
    }

    public static HeatSyncPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        double celsius = buf.readDouble();
        int tierOrdinal = buf.readVarInt();
        return new HeatSyncPacket(pos, celsius, tierOrdinal);
    }

    public static void handle(HeatSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientHeatCache.update(packet.pos, packet.celsius, packet.tierOrdinal);
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }

    public double getCelsius() {
        return celsius;
    }

    public int getTierOrdinal() {
        return tierOrdinal;
    }
}
