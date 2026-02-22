package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.Thermodynamica;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class HeatNetwork {

        private static final String PROTOCOL_VERSION = "1";

        public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
                        new ResourceLocation(Thermodynamica.MODID, "main"),
                        () -> PROTOCOL_VERSION,
                        PROTOCOL_VERSION::equals,
                        PROTOCOL_VERSION::equals);

        private static int id = 0;

        public static void register() {
                CHANNEL.registerMessage(id++, HeatSyncPacket.class,
                                HeatSyncPacket::encode,
                                HeatSyncPacket::decode,
                                HeatSyncPacket::handle);

                CHANNEL.registerMessage(id++, ChunkHeatSyncPacket.class,
                                ChunkHeatSyncPacket::encode,
                                ChunkHeatSyncPacket::decode,
                                ChunkHeatSyncPacket::handle);

                CHANNEL.registerMessage(id++, DebugInfoPacket.class,
                                DebugInfoPacket::encode,
                                DebugInfoPacket::decode,
                                DebugInfoPacket::handle);

                Thermodynamica.LOGGER.debug("Network channel registered");
        }
}
