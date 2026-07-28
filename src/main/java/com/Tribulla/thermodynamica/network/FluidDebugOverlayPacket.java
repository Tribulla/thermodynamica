package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.Thermodynamica;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FluidDebugOverlayPacket {

    private final boolean enabled;

    public FluidDebugOverlayPacket(boolean enabled) {
        this.enabled = enabled;
    }

    public static void encode(FluidDebugOverlayPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enabled);
    }

    public static FluidDebugOverlayPacket decode(FriendlyByteBuf buf) {
        return new FluidDebugOverlayPacket(buf.readBoolean());
    }

    public static void handle(FluidDebugOverlayPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            if (!context.getDirection().getReceptionSide().isClient())
                return;
            try {
                Class<?> overlay = Class.forName(
                        "com.Tribulla.thermodynamica.client.AirFluidDebugOverlay");
                overlay.getMethod("setEnabled", boolean.class).invoke(null, packet.enabled);
            } catch (ReflectiveOperationException e) {
                Thermodynamica.LOGGER.error("Failed to toggle air fluid debug overlay", e);
            }
        });
        context.setPacketHandled(true);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
