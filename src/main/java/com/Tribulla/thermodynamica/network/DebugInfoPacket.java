package com.Tribulla.thermodynamica.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DebugInfoPacket {

    private final BlockPos pos;
    private final String blockName;
    private final double celsius;
    private final double conductivity;
    private final double dissipationRate;

    public DebugInfoPacket(BlockPos pos, String blockName, double celsius,
            double conductivity, double dissipationRate) {
        this.pos = pos;
        this.blockName = blockName;
        this.celsius = celsius;
        this.conductivity = conductivity;
        this.dissipationRate = dissipationRate;
    }

    public static void encode(DebugInfoPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.blockName);
        buf.writeDouble(packet.celsius);
        buf.writeDouble(packet.conductivity);
        buf.writeDouble(packet.dissipationRate);
    }

    public static DebugInfoPacket decode(FriendlyByteBuf buf) {
        return new DebugInfoPacket(
                buf.readBlockPos(),
                buf.readUtf(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble());
    }

    public static void handle(DebugInfoPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientHeatCache.setDebugInfo(packet);
        });
        ctx.get().setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }

    public String getBlockName() {
        return blockName;
    }

    public double getCelsius() {
        return celsius;
    }

    public double getConductivity() {
        return conductivity;
    }

    public double getDissipationRate() {
        return dissipationRate;
    }
}
