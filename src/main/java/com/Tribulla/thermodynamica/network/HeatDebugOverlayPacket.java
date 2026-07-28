package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.Thermodynamica;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class HeatDebugOverlayPacket {

    private final boolean enabled;

    public HeatDebugOverlayPacket(boolean enabled) {
        this.enabled = enabled;
    }

    public static void encode(HeatDebugOverlayPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enabled);
    }

    public static HeatDebugOverlayPacket decode(FriendlyByteBuf buf) {
        return new HeatDebugOverlayPacket(buf.readBoolean());
    }

    public static void handle(HeatDebugOverlayPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            if (!context.getDirection().getReceptionSide().isClient())
                return;
            try {
                Class<?> overlay = Class.forName(
                        "com.Tribulla.thermodynamica.client.HeatEnergyDebugOverlay");
                overlay.getMethod("setEnabled", boolean.class).invoke(null, packet.enabled);
            } catch (ReflectiveOperationException e) {
                Thermodynamica.LOGGER.error("Failed to toggle heat energy debug overlay", e);
            }
        });
        context.setPacketHandled(true);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
