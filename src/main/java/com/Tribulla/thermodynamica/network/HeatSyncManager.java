package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.compat.ValkyrienSkiesCompat;
import com.Tribulla.thermodynamica.config.SimulationSettings;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            int configuredRange = settings.getSyncRange();
            int serverViewRange = Math.max(2, player.server.getPlayerList().getViewDistance()) * 16;
            int effectiveRange = Math.max(configuredRange, serverViewRange);
            boolean debug = settings.isDebugMode() || HeatDebugPlayers.isEnabled(player);
            double playerThreshold = debug ? 0.0 : threshold;
            syncToPlayer(player, sim, playerThreshold, effectiveRange, debug);
        }
    }

    public static void forceResync(ServerPlayer player) {
        lastSynced.remove(player);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            lastSynced.remove(player);
            HeatDebugPlayers.remove(player);
        }
    }

    private static void syncToPlayer(ServerPlayer player, HeatSimulationManager sim,
            double threshold, int range, boolean debug) {
        ServerLevel level = player.serverLevel();
        ResourceLocation dim = level.dimension().location();
        BlockPos playerPos = player.blockPosition();
        Vec3 playerWorldPos = player.position();
        ChunkPos playerChunk = new ChunkPos(playerPos);
        int chunkRange = (range >> 4) + 1;

        Map<BlockPos, Double> playerLastSynced = lastSynced.computeIfAbsent(player, p -> new HashMap<>());

        for (int cx = playerChunk.x - chunkRange; cx <= playerChunk.x + chunkRange; cx++) {
            for (int cz = playerChunk.z - chunkRange; cz <= playerChunk.z + chunkRange; cz++) {
                Map<BlockPos, Double> temps = sim.getChunkTemperatures(dim, cx, cz);
                if (temps.isEmpty())
                    continue;

                Map<BlockPos, ChunkHeatSyncPacket.HeatData> toSync = new HashMap<>();

                for (Map.Entry<BlockPos, Double> entry : temps.entrySet()) {
                    BlockPos pos = entry.getKey();
                    double temp = entry.getValue();

                    if (pos.distSqr(playerPos) > (long) range * range)
                        continue;

                    Double lastTemp = playerLastSynced.get(pos);
                    if (!debug && lastTemp != null && Math.abs(temp - lastTemp) < threshold)
                        continue;

                    toSync.put(pos, new ChunkHeatSyncPacket.HeatData(temp, pos, Vec3.atCenterOf(pos)));
                    playerLastSynced.put(pos, temp);
                }

                if (!toSync.isEmpty()) {
                    HeatNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new ChunkHeatSyncPacket(new ChunkPos(cx, cz), toSync));
                }
            }
        }

        syncShipHeatToPlayer(player, sim, dim, playerWorldPos, threshold, range, debug, playerLastSynced);
    }

    private static void syncShipHeatToPlayer(ServerPlayer player, HeatSimulationManager sim, ResourceLocation dim,
            Vec3 playerWorldPos, double threshold, int range, boolean debug, Map<BlockPos, Double> playerLastSynced) {
        int chunkRange = (range >> 4) + 1;
        double rangeSq = (double) range * range;

        for (Object ship : ValkyrienSkiesCompat.getAllLoadedShips(player.serverLevel())) {
            Vec3 shipLocalPlayerPos = ValkyrienSkiesCompat.toShipCoordinatesWithShip(ship, playerWorldPos);
            BlockPos shipPlayerBlockPos = BlockPos.containing(shipLocalPlayerPos);
            int centerChunkX = shipPlayerBlockPos.getX() >> 4;
            int centerChunkZ = shipPlayerBlockPos.getZ() >> 4;

            for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
                for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                    Map<BlockPos, Double> temps = sim.getChunkTemperatures(dim, cx, cz);
                    if (temps.isEmpty()) {
                        continue;
                    }

                    Map<BlockPos, ChunkHeatSyncPacket.HeatData> toSync = new HashMap<>();

                    for (Map.Entry<BlockPos, Double> entry : temps.entrySet()) {
                        BlockPos pos = entry.getKey();
                        Vec3 worldPos = ValkyrienSkiesCompat.toWorldCoordinatesWithShip(ship, Vec3.atCenterOf(pos));
                        BlockPos worldBlockPos = BlockPos.containing(worldPos);
                        if (worldPos.distanceToSqr(playerWorldPos) > rangeSq) {
                            continue;
                        }

                        double temp = entry.getValue();
                        Double lastTemp = playerLastSynced.get(worldBlockPos);
                        if (!debug && lastTemp != null && Math.abs(temp - lastTemp) < threshold) {
                            continue;
                        }

                        toSync.put(worldBlockPos, new ChunkHeatSyncPacket.HeatData(temp, pos, worldPos));
                        playerLastSynced.put(worldBlockPos, temp);
                    }

                    if (!toSync.isEmpty()) {
                        HeatNetwork.CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> player),
                                new ChunkHeatSyncPacket(new ChunkPos(cx, cz), toSync));
                    }
                }
            }
        }
    }
}
