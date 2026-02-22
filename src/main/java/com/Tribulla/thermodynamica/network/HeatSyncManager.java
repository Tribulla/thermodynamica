package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.config.SimulationSettings;
import com.Tribulla.thermodynamica.simulation.ChunkHeatKey;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Thermodynamica.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class HeatSyncManager {

    private static final ConcurrentHashMap<ServerPlayer, Map<BlockPos, Double>> lastSynced = new ConcurrentHashMap<>();
    private static int syncTimer = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;

        Thermodynamica instance = Thermodynamica.getInstance();
        if (instance == null || instance.getSimulationManager() == null)
            return;

        SimulationSettings settings = instance.getConfigManager().getSettings();

        syncTimer++;
        if (syncTimer < settings.getSimulationIntervalTicks())
            return;
        syncTimer = 0;

        HeatSimulationManager sim = instance.getSimulationManager();
        double threshold = settings.getSyncThreshold();
        int range = settings.getSyncRange();
        boolean debug = settings.isDebugMode();

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            syncToPlayer(player, sim, threshold, range, debug);
        }
    }

    private static void syncToPlayer(ServerPlayer player, HeatSimulationManager sim,
            double threshold, int range, boolean debug) {
        ServerLevel level = player.serverLevel();
        ResourceLocation dim = level.dimension().location();
        BlockPos playerPos = player.blockPosition();
        ChunkPos playerChunk = new ChunkPos(playerPos);
        int chunkRange = (range >> 4) + 1;

        Map<BlockPos, Double> playerLastSynced = lastSynced.computeIfAbsent(player, p -> new HashMap<>());

        for (int cx = playerChunk.x - chunkRange; cx <= playerChunk.x + chunkRange; cx++) {
            for (int cz = playerChunk.z - chunkRange; cz <= playerChunk.z + chunkRange; cz++) {
                Map<BlockPos, Double> temps = sim.getChunkTemperatures(dim, cx, cz);
                if (temps.isEmpty())
                    continue;

                Map<BlockPos, Double> toSync = new HashMap<>();

                for (Map.Entry<BlockPos, Double> entry : temps.entrySet()) {
                    BlockPos pos = entry.getKey();
                    double temp = entry.getValue();

                    if (pos.distSqr(playerPos) > (long) range * range)
                        continue;

                    Double lastTemp = playerLastSynced.get(pos);
                    if (!debug && lastTemp != null && Math.abs(temp - lastTemp) < threshold)
                        continue;

                    toSync.put(pos, temp);
                    playerLastSynced.put(pos, temp);
                }

                if (!toSync.isEmpty()) {
                    HeatNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new ChunkHeatSyncPacket(new ChunkPos(cx, cz), toSync));
                }
            }
        }
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        lastSynced.remove(player);
    }
}
