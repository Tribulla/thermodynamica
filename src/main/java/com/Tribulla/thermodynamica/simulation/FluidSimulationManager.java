package com.Tribulla.thermodynamica.simulation;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.impl.FluidAPIImpl;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.config.SimulationSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Iterator;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class FluidSimulationManager {

    private final MinecraftServer server;
    private final HeatConfigManager configManager;
    private final SimulationSettings settings;
    private final HeatSimulationManager heatManager;
    private final FluidAPIImpl fluidApi;
    private final AirFluidEngine engine;
    private final Set<ChunkHeatKey> loadedChunks = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private int tickCounter = 0;
    private FluidSavedData savedData;

    public FluidSimulationManager(MinecraftServer server, HeatConfigManager configManager,
            HeatSimulationManager heatManager, FluidAPIImpl fluidApi) {
        this.server = server;
        this.configManager = configManager;
        this.settings = configManager.getSettings();
        this.heatManager = heatManager;
        this.fluidApi = fluidApi;
        this.engine = new AirFluidEngine(server, configManager, fluidApi);
        this.engine.setManager(this);
    }

    public void setSavedData(FluidSavedData savedData) {
        this.savedData = savedData;
    }

    public FluidSavedData getSavedData() {
        return savedData;
    }

    public void start() {
        running.set(true);
        engine.start();
        Thermodynamica.LOGGER.info("Fluid simulation started (enabled={})", settings.isFluidSimulationEnabled());
    }

    public void stopProcessing() {
        running.set(false);
        engine.stopProcessing();
    }

    public void stop() {
        running.set(false);
        engine.stop();
        loadedChunks.clear();
    }

    public void tick() {
        if (!running.get() || !settings.isFluidSimulationEnabled())
            return;
        tickCounter++;
        if (tickCounter % 200 == 0) {
            cleanupStaleChunks();
        }
        seedFromHeat();
        engine.tick();
    }

    private void seedFromHeat() {
        double ambient = settings.getAmbientTemperature();
        for (ChunkHeatKey key : loadedChunks) {
            Map<BlockPos, Double> temps = heatManager.getChunkTemperatures(key.getDimension(),
                    key.getChunkPos().x, key.getChunkPos().z);
            if (temps.isEmpty())
                continue;
            for (Map.Entry<BlockPos, Double> entry : temps.entrySet()) {
                double temp = entry.getValue();
                if (Math.abs(temp - ambient) < settings.getDeltaThreshold() * 0.5)
                    continue;
                engine.activateAirAround(key.getDimension(), entry.getKey().asLong(), temp);
            }
        }
    }

    private void cleanupStaleChunks() {
        Iterator<ChunkHeatKey> it = loadedChunks.iterator();
        while (it.hasNext()) {
            ChunkHeatKey key = it.next();
            ServerLevel level = getLevelForDim(key.getDimension());
            if (level == null || !level.hasChunk(key.getChunkPos().x, key.getChunkPos().z)) {
                it.remove();
                engine.clearChunk(key.getDimension(), key.getChunkPos().x, key.getChunkPos().z);
            }
        }
    }

    public OptionalDouble getAirTemperature(ResourceLocation dim, long packedPos) {
        return engine.getAirTemperature(dim, packedPos);
    }

    public OptionalDouble getAirPressure(ResourceLocation dim, long packedPos) {
        return engine.getAirPressure(dim, packedPos);
    }

    public Vec3 getAirVelocity(ResourceLocation dim, long packedPos) {
        return engine.getAirVelocity(dim, packedPos);
    }

    public void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        loadedChunks.add(new ChunkHeatKey(level.dimension().location(), chunk.getPos()));
    }

    public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        ChunkHeatKey key = new ChunkHeatKey(level.dimension().location(), chunk.getPos());
        loadedChunks.remove(key);
        if (!running.get())
            return;
        engine.clearChunk(level.dimension().location(), chunk.getPos().x, chunk.getPos().z);
    }

    public void onBlockChanged(ServerLevel level, BlockPos pos) {
        if (!settings.isFluidSimulationEnabled())
            return;
        engine.onBlockChanged(level.dimension().location(), pos.asLong());
    }

    public double sampleSolidTemperature(ResourceLocation dim, long packedPos) {
        ServerLevel level = getLevelForDim(dim);
        if (level == null)
            return settings.getAmbientTemperature();
        BlockPos pos = BlockPos.of(packedPos);
        if (!level.isLoaded(pos))
            return settings.getAmbientTemperature();
        BlockState state = level.getBlockState(pos);
        if (state.isAir())
            return settings.getAmbientTemperature();
        OptionalDouble exact = heatManager.getExactTemperature(level, pos);
        if (exact.isPresent()) {
            return exact.getAsDouble() - HeatAPI.get().getBiomeOffset(level, pos);
        }
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId == null)
            return settings.getAmbientTemperature();
        return HeatAPI.get().getResolvedCelsius(blockId, level, pos) - HeatAPI.get().getBiomeOffset(level, pos);
    }

    public void applySolidTemperatureDelta(ResourceLocation dim, long packedPos, double deltaTemp) {
        if (Math.abs(deltaTemp) < 0.0001)
            return;
        ServerLevel level = getLevelForDim(dim);
        if (level == null)
            return;
        BlockPos pos = BlockPos.of(packedPos);
        if (!level.isLoaded(pos))
            return;
        BlockState state = level.getBlockState(pos);
        if (state.isAir())
            return;
        heatManager.addTransientTemperatureDelta(level, pos, deltaTemp);
    }

    public Map<BlockPos, AirFluidEngine.FluidCellData> getChunkFluidData(ResourceLocation dim, int chunkX, int chunkZ) {
        return engine.getChunkFluidData(dim, chunkX, chunkZ);
    }

    public void saveToNBT(CompoundTag tag) {
        engine.saveToNBT(tag);
    }

    public void loadFromNBT(CompoundTag tag) {
        engine.loadFromNBT(tag);
    }

    public void forceProcessChunks(int ticks) {
        engine.forceProcessChunks(ticks);
    }

    public double getLastSimulationTimeMs() {
        return engine.getLastTickMs();
    }

    public double getAverageSimulationTimeMs() {
        return engine.getAverageTickMs();
    }

    public int getLastCellsProcessed() {
        return engine.getLastCellsProcessed();
    }

    public int getLastChangedCells() {
        return engine.getLastChangedCells();
    }

    public int getLoadedChunkCount() {
        return loadedChunks.size();
    }

    public int getActiveCellCount() {
        return engine.getActiveCellCount();
    }

    public int getGridSize() {
        return engine.getGridSize();
    }

    public long getSimulationTickCount() {
        return engine.getTotalTicks();
    }

    public long getTotalCellsProcessed() {
        return engine.getTotalCellsProcessed();
    }

    private ServerLevel getLevelForDim(ResourceLocation dim) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dim))
                return level;
        }
        return null;
    }
}
