package com.Tribulla.thermodynamica.simulation;

import java.util.Iterator;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.HeatTier;
import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.Tribulla.thermodynamica.api.compat.ValkyrienSkiesCompat;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.config.SimulationSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

public class HeatSimulationManager {

    private final MinecraftServer server;
    private final HeatConfigManager configManager;
    private final SimulationSettings settings;
    private final BFSHeatEngine engine;

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, SourceInfo>> sourceIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkHeatKey, Set<Long>> chunkSourceIndex = new ConcurrentHashMap<>();
    private final Set<ChunkHeatKey> loadedChunks = ConcurrentHashMap.newKeySet();

    private int tickCounter = 0;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private HeatSavedData savedData;

    public void setSavedData(HeatSavedData savedData) {
        this.savedData = savedData;
    }

    public HeatSavedData getSavedData() {
        return savedData;
    }

    static record SourceInfo(double temperature, double conductivity, double transferRate) {
    }

    public HeatSimulationManager(MinecraftServer server, HeatConfigManager configManager) {
        this.server = server;
        this.configManager = configManager;
        this.settings = configManager.getSettings();
        this.engine = new BFSHeatEngine(server, configManager);
    }

    public void start() {
        running.set(true);
        engine.start();
        Thermodynamica.LOGGER.info("Heat simulation started (BFS engine, {} workers)",
                settings.getWorkerThreads());
    }

    public void stopProcessing() {
        running.set(false);
        engine.stopProcessing();
        Thermodynamica.LOGGER.info("Heat simulation processing stopped");
    }

    public void stop() {
        running.set(false);
        engine.stop();
        sourceIndex.clear();
        chunkSourceIndex.clear();
        loadedChunks.clear();
        Thermodynamica.LOGGER.info("Heat simulation stopped");
    }

    public void tick() {
        if (!running.get())
            return;

        tickCounter++;

        // Periodically purge stale loadedChunks entries
        if (tickCounter % 200 == 0) {
            cleanupStaleChunks();
        }

        if (tickCounter >= settings.getSimulationIntervalTicks()) {
            tickCounter = 0;
            engine.tick();
        }
    }

    private void cleanupStaleChunks() {
        Iterator<ChunkHeatKey> it = loadedChunks.iterator();
        while (it.hasNext()) {
            ChunkHeatKey key = it.next();
            ServerLevel level = getLevelForDim(key.getDimension());
            if (level == null || !level.hasChunk(key.getChunkPos().x, key.getChunkPos().z)) {
                it.remove();
                Set<Long> sourcesInChunk = chunkSourceIndex.remove(key);
                if (sourcesInChunk != null) {
                    ConcurrentHashMap<Long, SourceInfo> dimSources = sourceIndex.get(key.getDimension());
                    for (long packed : sourcesInChunk) {
                        if (dimSources != null)
                            dimSources.remove(packed);
                        engine.removeSource(key.getDimension(), packed);
                    }
                }
                engine.clearChunk(key.getDimension(), key.getChunkPos().x, key.getChunkPos().z);
            }
        }
    }

    public double getTemperature(net.minecraft.world.level.Level level, BlockPos pos) {
        pos = ValkyrienSkiesCompat.toWorldPos(level, pos);
        ResourceLocation dim = level.dimension().location();
        double biomeOffset = configManager.getBiomeConfig().getOffset(level.getBiome(pos));
        return engine.getTemperature(dim, pos.asLong()) + biomeOffset;
    }

    public OptionalDouble getExactTemperature(net.minecraft.world.level.Level level, BlockPos pos) {
        pos = ValkyrienSkiesCompat.toWorldPos(level, pos);
        ResourceLocation dim = level.dimension().location();
        OptionalDouble exact = engine.getExactTemperature(dim, pos.asLong());
        if (exact.isEmpty()) {
            return exact;
        }
        double biomeOffset = configManager.getBiomeConfig().getOffset(level.getBiome(pos));
        return OptionalDouble.of(exact.getAsDouble() + biomeOffset);
    }

    public void setTemperature(net.minecraft.world.level.Level level, BlockPos pos, double celsius) {
        pos = ValkyrienSkiesCompat.toWorldPos(level, pos);
        ResourceLocation dim = level.dimension().location();
        double ambient = configManager.getTierDefinitions().getCelsius(settings.getAmbientTier());
        long packed = pos.asLong();

        if (Math.abs(celsius - ambient) > settings.getDeltaThreshold()) {
            registerSource(dim, pos, packed, celsius);
        } else {
            unregisterSource(dim, pos, packed);
        }
    }

    public void markActive(net.minecraft.world.level.Level level, BlockPos pos) {
        pos = ValkyrienSkiesCompat.toWorldPos(level, pos);
        ResourceLocation dim = level.dimension().location();
        BlockState state = level.getBlockState(pos);
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        HeatTier tier = HeatAPI.get().getResolvedTier(blockId);
        long packed = pos.asLong();

        if (tier == settings.getAmbientTier()) {
            ConcurrentHashMap<Long, SourceInfo> dimSources = sourceIndex.get(dim);
            if (dimSources != null && dimSources.containsKey(packed))
                return;
        }

        double celsius = configManager.getTierDefinitions().getCelsius(tier);
        registerSource(dim, pos, packed, celsius);
    }

    public void markInactive(net.minecraft.world.level.Level level, BlockPos pos) {
        pos = ValkyrienSkiesCompat.toWorldPos(level, pos);
        ResourceLocation dim = level.dimension().location();
        unregisterSource(dim, pos, pos.asLong());
    }

    private void registerSource(ResourceLocation dim, BlockPos pos, long packed, double celsius) {
        ThermalProperties props = lookupThermalProps(dim, pos);

        sourceIndex.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .put(packed, new SourceInfo(celsius, props.getConductivity(), props.getTransferRate()));

        ChunkHeatKey chunkKey = new ChunkHeatKey(dim, new ChunkPos(pos));
        chunkSourceIndex.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet())
                .add(packed);

        engine.addSource(dim, packed, celsius);
    }

    private void unregisterSource(ResourceLocation dim, BlockPos pos, long packed) {
        ConcurrentHashMap<Long, SourceInfo> dimSources = sourceIndex.get(dim);
        if (dimSources != null)
            dimSources.remove(packed);

        ChunkHeatKey chunkKey = new ChunkHeatKey(dim, new ChunkPos(pos));
        Set<Long> chunkSet = chunkSourceIndex.get(chunkKey);
        if (chunkSet != null)
            chunkSet.remove(packed);

        engine.removeSource(dim, packed);
    }

    private ThermalProperties lookupThermalProps(ResourceLocation dim, BlockPos pos) {
        ServerLevel level = getLevelForDim(dim);
        if (level == null)
            return ThermalProperties.defaults();

        try {
            BlockState state = level.getBlockState(pos);
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            ThermalProperties props = configManager.getThermalPropertiesRegistry().get(blockId);
            return props != null ? props : ThermalProperties.defaults();
        } catch (Exception e) {
            return ThermalProperties.defaults();
        }
    }

    public void onChunkLoad(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        ResourceLocation dim = level.dimension().location();
        loadedChunks.add(new ChunkHeatKey(dim, chunk.getPos()));
    }

    public void onChunkUnload(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        ResourceLocation dim = level.dimension().location();
        ChunkPos cp = chunk.getPos();
        ChunkHeatKey key = new ChunkHeatKey(dim, cp);
        loadedChunks.remove(key);

        // During shutdown (running == false), skip cleanup so data survives for the save.
        // Chunk unloads fire before the world is saved, and clearing here would wipe
        // all grid/source data before saveToNBT() gets called.
        if (!running.get())
            return;

        Set<Long> sourcesInChunk = chunkSourceIndex.remove(key);
        if (sourcesInChunk != null) {
            ConcurrentHashMap<Long, SourceInfo> dimSources = sourceIndex.get(dim);
            for (long packed : sourcesInChunk) {
                if (dimSources != null)
                    dimSources.remove(packed);
                engine.removeSource(dim, packed);
            }
        }
        engine.clearChunk(dim, cp.x, cp.z);
    }

    public Map<BlockPos, Double> getChunkTemperatures(ResourceLocation dim, int chunkX, int chunkZ) {
        return engine.getChunkTemperatures(dim, chunkX, chunkZ);
    }

    public void forceProcessChunks(int ticks) {
        engine.forceProcessChunks(ticks);
    }

    public void saveToNBT(CompoundTag tag) {
        engine.saveToNBT(tag);
    }

    public void loadFromNBT(CompoundTag tag) {
        engine.loadFromNBT(tag);
        sourceIndex.clear();
        chunkSourceIndex.clear();
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, Double>> dimEntry : engine.getSourceTemps()
                .entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            for (Map.Entry<Long, Double> cellEntry : dimEntry.getValue().entrySet()) {
                long packed = cellEntry.getKey();
                BlockPos p = BlockPos.of(packed);
                ThermalProperties props = lookupThermalProps(dim, p);
                sourceIndex.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                        .put(packed,
                                new SourceInfo(cellEntry.getValue(), props.getConductivity(), props.getTransferRate()));
                ChunkHeatKey chunkKey = new ChunkHeatKey(dim, new ChunkPos(p));
                chunkSourceIndex.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(packed);
            }
        }
    }

    public boolean isChunkLoaded(ChunkHeatKey key) {
        return loadedChunks.contains(key);
    }

    public ChunkHeatData getChunkData(ChunkHeatKey key) {
        Map<BlockPos, Double> temps = engine.getChunkTemperatures(
                key.getDimension(), key.getChunkPos().x, key.getChunkPos().z);
        ChunkHeatData data = new ChunkHeatData(key.getChunkPos());
        for (Map.Entry<BlockPos, Double> entry : temps.entrySet()) {
            data.setTemperature(entry.getKey(), entry.getValue());
        }
        return data;
    }

    @Deprecated
    public ConcurrentHashMap<ChunkHeatKey, ChunkHeatData> getAllChunkData() {
        ConcurrentHashMap<ChunkHeatKey, ChunkHeatData> result = new ConcurrentHashMap<>();
        for (ChunkHeatKey key : loadedChunks) {
            ChunkHeatData data = getChunkData(key);
            if (!data.getTemperatures().isEmpty())
                result.put(key, data);
        }
        return result;
    }

    public double getLastSimulationTimeMs() {
        return engine.getLastTickMs();
    }

    public double getAverageSimulationTimeMs() {
        return engine.getAverageTickMs();
    }

    public int getLastChunksProcessed() {
        return engine.getLastBlocksProcessed();
    }

    public long getSimulationTickCount() {
        return engine.getTotalTicks();
    }

    public long getTotalBlocksProcessed() {
        return engine.getTotalBlocksProcessed();
    }

    public int getLoadedChunkCount() {
        return loadedChunks.size();
    }

    public int getActiveBlockCount() {
        return engine.getSourceCount();
    }

    public int getDirtyChunkCount() {
        return 0;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getSourceCount() {
        return engine.getSourceCount();
    }

    public int getPropagatingSourceCount() {
        return engine.getCurrentFrontierSize();
    }

    public double getSimulationTPS() {
        double intervalSeconds = settings.getSimulationIntervalTicks() / 20.0;
        double simTimeSeconds = getLastSimulationTimeMs() / 1000.0;
        return (simTimeSeconds >= intervalSeconds) ? 1.0 / simTimeSeconds : 1.0 / intervalSeconds;
    }

    public String getNearestSourceDebug(ResourceLocation dim, BlockPos pos) {
        ConcurrentHashMap<Long, SourceInfo> dimSources = sourceIndex.get(dim);
        if (dimSources == null || dimSources.isEmpty())
            return "No sources in dimension " + dim;

        long nearestPacked = 0;
        SourceInfo nearestInfo = null;
        double nearestDist = Double.MAX_VALUE;

        for (Map.Entry<Long, SourceInfo> entry : dimSources.entrySet()) {
            BlockPos sp = BlockPos.of(entry.getKey());
            double dist = pos.distSqr(sp);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestPacked = entry.getKey();
                nearestInfo = entry.getValue();
            }
        }

        if (nearestInfo == null)
            return "No sources found";
        BlockPos sp = BlockPos.of(nearestPacked);
        return String.format(
                "Nearest source at (%d,%d,%d) dist=%.1f | target=%.1f grid=%.1f | " +
                        "cond=%.2f xfer=%.2f | frontier=%d grid=%d",
                sp.getX(), sp.getY(), sp.getZ(), Math.sqrt(nearestDist),
                nearestInfo.temperature(), engine.getTemperature(dim, nearestPacked),
                nearestInfo.conductivity(), nearestInfo.transferRate(),
                engine.getCurrentFrontierSize(), engine.getGridSize());
    }

    private ServerLevel getLevelForDim(ResourceLocation dim) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dim))
                return level;
        }
        return null;
    }
}
