package com.Tribulla.thermodynamica.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class DebugInfoPacket {

    private final BlockPos pos;
    private final String tierName;
    private final double celsius;
    private final String source;
    private final int priority;
    private final double conductivity;
    private final double dissipationRate;

    public DebugInfoPacket(BlockPos pos, String tierName, double celsius, String source,
            int priority, double conductivity, double dissipationRate) {
        this.pos = pos;
        this.tierName = tierName;
        this.celsius = celsius;
        this.source = source;
        this.priority = priority;
        this.conductivity = conductivity;
        this.dissipationRate = dissipationRate;
    }

    public static void encode(DebugInfoPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.tierName);
        buf.writeDouble(packet.celsius);
        buf.writeUtf(packet.source);
        buf.writeVarInt(packet.priority);
        buf.writeDouble(packet.conductivity);
        buf.writeDouble(packet.dissipationRate);
    }

    public static DebugInfoPacket decode(FriendlyByteBuf buf) {
        return new DebugInfoPacket(
                buf.readBlockPos(),
                buf.readUtf(),
                buf.readDouble(),
                buf.readUtf(),
                buf.readVarInt(),
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

    public String getTierName() {
        return tierName;
    }

    public double getCelsius() {
        return celsius;
    }

    public String getSource() {
        return source;
    }

    public int getPriority() {
        return priority;
    }

    public double getConductivity() {
        return conductivity;
    }

    public double getDissipationRate() {
        return dissipationRate;
    }
}
