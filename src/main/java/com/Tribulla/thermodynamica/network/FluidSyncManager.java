package com.Tribulla.thermodynamica.network;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.config.SimulationSettings;
import com.Tribulla.thermodynamica.simulation.AirFluidEngine;
import com.Tribulla.thermodynamica.simulation.FluidSimulationManager;
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
public class FluidSyncManager {

    private static final ConcurrentHashMap<ServerPlayer, Map<BlockPos, Long>> lastSyncedFingerprint = new ConcurrentHashMap<>();
    private static int syncTimer = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;

        Thermodynamica instance = Thermodynamica.getInstance();
        if (instance == null || instance.getFluidSimulationManager() == null)
            return;

        SimulationSettings settings = instance.getConfigManager().getSettings();
        if (!settings.isFluidSimulationEnabled())
            return;

        syncTimer++;
        if (syncTimer < settings.getFluidSimulationIntervalTicks())
            return;
        syncTimer = 0;

        FluidSimulationManager sim = instance.getFluidSimulationManager();
        double threshold = settings.getFluidDebugSyncThreshold();

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!FluidDebugPlayers.isEnabled(player))
                continue;
            int configuredRange = settings.getFluidSyncRange();
            int serverViewRange = Math.max(2, player.server.getPlayerList().getViewDistance()) * 16;
            int effectiveRange = Math.max(configuredRange, serverViewRange);
            syncToPlayer(player, sim, threshold, effectiveRange);
        }
    }

    public static void forceResync(ServerPlayer player) {
        lastSyncedFingerprint.remove(player);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            lastSyncedFingerprint.remove(player);
            FluidDebugPlayers.remove(player);
        }
    }

    private static void syncToPlayer(ServerPlayer player, FluidSimulationManager sim,
            double threshold, int range) {
        ServerLevel level = player.serverLevel();
        ResourceLocation dim = level.dimension().location();
        BlockPos playerPos = player.blockPosition();
        ChunkPos playerChunk = new ChunkPos(playerPos);
        int chunkRange = (range >> 4) + 1;

        Map<BlockPos, Long> playerLastSynced = lastSyncedFingerprint.computeIfAbsent(player, p -> new HashMap<>());

        for (int cx = playerChunk.x - chunkRange; cx <= playerChunk.x + chunkRange; cx++) {
            for (int cz = playerChunk.z - chunkRange; cz <= playerChunk.z + chunkRange; cz++) {
                Map<BlockPos, AirFluidEngine.FluidCellData> cells = sim.getChunkFluidData(dim, cx, cz);
                if (cells.isEmpty())
                    continue;

                Map<BlockPos, ChunkFluidSyncPacket.FluidData> toSync = new HashMap<>();
                for (Map.Entry<BlockPos, AirFluidEngine.FluidCellData> entry : cells.entrySet()) {
                    BlockPos pos = entry.getKey();
                    AirFluidEngine.FluidCellData cell = entry.getValue();
                    if (pos.distSqr(playerPos) > (long) range * range)
                        continue;

                    long fingerprint = fingerprint(cell, threshold);
                    Long last = playerLastSynced.get(pos);
                    if (last != null && last == fingerprint)
                        continue;

                    toSync.put(pos, new ChunkFluidSyncPacket.FluidData(
                            cell.celsius(), cell.pressure(), cell.velocity(),
                            pos, Vec3.atCenterOf(pos)));
                    playerLastSynced.put(pos, fingerprint);
                }

                if (!toSync.isEmpty()) {
                    HeatNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new ChunkFluidSyncPacket(new ChunkPos(cx, cz), toSync));
                }
            }
        }
    }

    private static long fingerprint(AirFluidEngine.FluidCellData cell, double threshold) {
        double scale = Math.max(threshold, 0.01);
        long t = Math.round(cell.celsius() / scale);
        long p = Math.round(cell.pressure() / scale);
        long vx = Math.round(cell.velocity().x / scale);
        long vy = Math.round(cell.velocity().y / scale);
        long vz = Math.round(cell.velocity().z / scale);
        return t * 31L + p * 37L + vx * 41L + vy * 43L + vz * 47L;
    }
}
