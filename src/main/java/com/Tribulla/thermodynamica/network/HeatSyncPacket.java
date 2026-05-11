package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.Thermodynamica;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class HeatSyncPacket {

    private final BlockPos pos;
    private final double celsius;

    public HeatSyncPacket(BlockPos pos, double celsius) {
        this.pos = pos;
        this.celsius = celsius;
    }

    public static void encode(HeatSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeDouble(packet.celsius);
    }

    public static HeatSyncPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        double celsius = buf.readDouble();
        return new HeatSyncPacket(pos, celsius);
    }

    public static void handle(HeatSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientHeatCache.update(packet.pos, packet.celsius);
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }

    public double getCelsius() {
        return celsius;
    }
}
