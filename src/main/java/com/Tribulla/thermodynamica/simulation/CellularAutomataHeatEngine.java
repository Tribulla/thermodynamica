package com.Tribulla.thermodynamica.simulation;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.TemperatureChangeEvent;
import com.Tribulla.thermodynamica.api.impl.HeatAPIImpl;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.config.SimulationSettings;
import com.Tribulla.thermodynamica.config.ThermalPropertiesRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.OptionalDouble;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

public class CellularAutomataHeatEngine {

    static final class AtomicCell {
        final AtomicLong current;
        final AtomicLong delta;

        AtomicCell(double current, double delta) {
            this.current = new AtomicLong(Double.doubleToRawLongBits(current));
            this.delta = new AtomicLong(Double.doubleToRawLongBits(delta));
        }

        double getCurrent() {
            return Double.longBitsToDouble(current.get());
        }

        void setCurrent(double value) {
            current.set(Double.doubleToRawLongBits(value));
        }

        double getDelta() {
            return Double.longBitsToDouble(delta.get());
        }

        void setDelta(double value) {
            delta.set(Double.doubleToRawLongBits(value));
        }

        void addDelta(double value) {
            long currentRaw, newRaw;
            do {
                currentRaw = delta.get();
                newRaw = Double.doubleToRawLongBits(Double.longBitsToDouble(currentRaw) + value);
            } while (!delta.compareAndSet(currentRaw, newRaw));
        }
    }

    private static final int[][] NEIGHBORS = {
            { 1, 0, 0 }, { -1, 0, 0 },
            { 0, 1, 0 }, { 0, -1, 0 },
            { 0, 0, 1 }, { 0, 0, -1 }
    };

    static final class CachedProps {
        final double conductivity;
        final double heatCapacity;
        final double dissipationRate;
        final boolean isFluid;
        final boolean isWater;
        final boolean conductive;

        CachedProps(double conductivity, double heatCapacity, double dissipationRate,
                boolean isFluid, boolean isWater, boolean conductive) {
            this.conductivity = conductivity;
            this.heatCapacity = heatCapacity;
            this.dissipationRate = dissipationRate;
            this.isFluid = isFluid;
            this.isWater = isWater;
            this.conductive = conductive;
        }
    }

    private static final CachedProps AIR_INSULATING = new CachedProps(
            0.0, Double.POSITIVE_INFINITY, 0.0, true, false, false);
    private static final CachedProps WATER_INSULATING = new CachedProps(
            0.0, Double.POSITIVE_INFINITY, 0.0, true, true, false);

    private static final CachedProps AIR_CONDUCTIVE = new CachedProps(
            0.5, 50.0, 0.15, true, false, true);
    private static final CachedProps WATER_CONDUCTIVE = new CachedProps(
            2.0, 100.0, 0.25, true, true, true);

    private static final CachedProps DEFAULT_PROPS = new CachedProps(
            ThermalProperties.defaults().getConductivity(),
            ThermalProperties.defaults().getHeatCapacity(),
            ThermalProperties.defaults().getDissipationRate(),
            false, false, true);

    private static final int WORLD_MIN_Y = -64;
    private static final int WORLD_MAX_Y = 319;

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, CachedProps>> propsCache = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final HeatConfigManager configManager;
    private final SimulationSettings settings;

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, AtomicCell>> grids = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, Set<Long>>> chunkCellMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceLocation, Set<Long>> dirtyCells = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ResourceLocation> positionDimensions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, Double>> sourceTemps = new ConcurrentHashMap<>();

    private final Set<Long> activeSet = ConcurrentHashMap.newKeySet();

    private ConcurrentLinkedQueue<Long> generationQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> generationPending = ConcurrentHashMap.newKeySet();

    private final Set<Long> pendingActivation = ConcurrentHashMap.newKeySet();

    private ForkJoinPool pool;

    private static final int BATCH_SIZE = 256;
    private static final int INITIAL_ADAPTIVE_BUDGET = 1000;
    private static final int MIN_ADAPTIVE_BUDGET = 100;

    private double ambientTemp;
    private double deltaThreshold;
    private boolean airInsulates;
    private double waterTransferMult;
    private double dissipationMult;
    private int workBudget;
    private double timeBudgetMs;
    private int simulationIntervalTicks;
    private volatile int adaptiveCellBudget = INITIAL_ADAPTIVE_BUDGET;
    private int gameTicksSinceLastGeneration = 0;
    private boolean generationInProgress = false;

    private volatile long lastTickNanos;
    private volatile int lastActiveSize;
    private volatile int lastBlocksProcessed;
    private final AtomicLong totalTickNanos = new AtomicLong();
    private final AtomicLong totalTicks = new AtomicLong();
    private final AtomicLong totalBlocksProcessed = new AtomicLong();

    public CellularAutomataHeatEngine(MinecraftServer server, HeatConfigManager configManager) {
        this.server = server;
        this.configManager = configManager;
        this.settings = configManager.getSettings();
        refreshConfig();
    }

    public void refreshConfig() {
        this.ambientTemp = settings.getAmbientTemperature();
        this.deltaThreshold = settings.getDeltaThreshold();
        this.airInsulates = settings.isAirInsulates();
        this.waterTransferMult = settings.getWaterTransferMultiplier();
        this.dissipationMult = settings.getDissipationMultiplier();
        this.workBudget = settings.getWorkBudgetPerTick();
        this.timeBudgetMs = settings.getTimeBudgetMsPerTick();
        this.simulationIntervalTicks = Math.max(1, settings.getSimulationIntervalTicks());
        if (adaptiveCellBudget > workBudget) adaptiveCellBudget = workBudget;
        if (adaptiveCellBudget < MIN_ADAPTIVE_BUDGET) adaptiveCellBudget = MIN_ADAPTIVE_BUDGET;
    }

    public void start() {
        int threads = Math.max(1, settings.getWorkerThreads());
        pool = new ForkJoinPool(threads,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t, e) -> Thermodynamica.LOGGER.error("CA heat worker error", e),
                true);
        Thermodynamica.LOGGER.info("Cellular automata heat engine started with {} worker threads", threads);
    }

    public void stopProcessing() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        Thermodynamica.LOGGER.info("Cellular automata heat engine processing stopped");
    }

    public void stop() {
        stopProcessing();
        grids.clear();
        chunkCellMap.clear();
        dirtyCells.clear();
        activeSet.clear();
        generationQueue.clear();
        generationPending.clear();
        pendingActivation.clear();
        generationInProgress = false;
        gameTicksSinceLastGeneration = 0;
        adaptiveCellBudget = INITIAL_ADAPTIVE_BUDGET;
        positionDimensions.clear();
        sourceTemps.clear();
        propsCache.clear();
        Thermodynamica.LOGGER.info("Cellular automata heat engine stopped");
    }

    public void tick() {
        if (pool == null || pool.isShutdown())
            return;

        long start = System.nanoTime();

        long tickNum = totalTicks.get();
        if (tickNum % 10 == 0) {
            trimPropsCache();
        }

        injectSources();

        if (!generationInProgress) {
            if (activeSet.isEmpty() && pendingActivation.isEmpty()) {
                recordTickStats(start, 0, 0);
                return;
            }
            gameTicksSinceLastGeneration++;
            if (gameTicksSinceLastGeneration < simulationIntervalTicks) {
                recordTickStats(start, 0, 0);
                return;
            }
            beginGeneration();
            gameTicksSinceLastGeneration = 0;
        }

        int cellsThisTick = Math.min(adaptiveCellBudget, workBudget);
        List<long[]> batches = new ArrayList<>();
        long[] batch = new long[BATCH_SIZE];
        int batchIdx = 0;

        List<Long> tickPositions = new ArrayList<>(Math.min(cellsThisTick, 1024));
        int count = 0;
        while (count < cellsThisTick) {
            Long packed = generationQueue.poll();
            if (packed == null) break;
            generationPending.remove(packed);
            tickPositions.add(packed);
            batch[batchIdx++] = packed;
            count++;
            if (batchIdx == BATCH_SIZE) {
                batches.add(batch);
                batch = new long[BATCH_SIZE];
                batchIdx = 0;
            }
        }
        if (batchIdx > 0) {
            long[] partial = new long[batchIdx];
            System.arraycopy(batch, 0, partial, 0, batchIdx);
            batches.add(partial);
        }

        if (!tickPositions.isEmpty()) {
            resolvePropertiesForTick(tickPositions);
        }

        if (!batches.isEmpty()) {
            List<Future<?>> futures = new ArrayList<>(batches.size());
            for (long[] b : batches) {
                futures.add(pool.submit(() -> computeBatch(b)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    Thermodynamica.LOGGER.warn("CA heat compute timed out, skipping remaining batches");
                    break;
                } catch (Exception e) {
                    Thermodynamica.LOGGER.error("CA heat compute error", e);
                }
            }
        }

        int blocksProcessed = 0;
        if (generationQueue.isEmpty() && generationPending.isEmpty()) {
            blocksProcessed = commitGeneration();
            generationInProgress = false;
        }

        long elapsed = System.nanoTime() - start;

        if (count >= cellsThisTick) {
            double elapsedMs = elapsed / 1_000_000.0;
            if (elapsedMs < timeBudgetMs * 0.5) {
                adaptiveCellBudget = Math.min(adaptiveCellBudget * 2, workBudget);
            } else if (elapsedMs > timeBudgetMs * 1.5) {
                adaptiveCellBudget = Math.max(adaptiveCellBudget / 2, MIN_ADAPTIVE_BUDGET);
            }
        }

        recordTickStats(start, count, blocksProcessed);
    }

    private void beginGeneration() {
        for (long pos : pendingActivation) {
            activeSet.add(pos);
        }
        pendingActivation.clear();

        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, Double>> dimEntry : sourceTemps.entrySet()) {
            for (Long pos : dimEntry.getValue().keySet()) {
                activeSet.add(pos);
            }
        }

        generationQueue = new ConcurrentLinkedQueue<>();
        generationPending.clear();
        for (long pos : activeSet) {
            generationQueue.add(pos);
            generationPending.add(pos);
        }
        generationInProgress = true;
    }

    private void recordTickStats(long startNanos, int activeProcessed, int blocksProcessed) {
        long elapsed = System.nanoTime() - startNanos;
        lastTickNanos = elapsed;
        lastActiveSize = activeProcessed;
        lastBlocksProcessed = blocksProcessed;
        totalTickNanos.addAndGet(elapsed);
        totalTicks.incrementAndGet();
        totalBlocksProcessed.addAndGet(blocksProcessed);
    }

    public void forceProcessChunks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }

    public void saveToNBT(CompoundTag tag) {
        CompoundTag dimsTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, AtomicCell>> dimEntry : grids.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            ListTag cellList = new ListTag();
            for (Map.Entry<Long, AtomicCell> cellEntry : dimEntry.getValue().entrySet()) {
                CompoundTag cellTag = new CompoundTag();
                cellTag.putLong("Pos", cellEntry.getKey());
                cellTag.putDouble("Temp", cellEntry.getValue().getCurrent());
                cellList.add(cellTag);
            }
            dimsTag.put(dim.toString(), cellList);
        }
        tag.put("Grids", dimsTag);

        CompoundTag sourcesTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, Double>> dimEntry : sourceTemps.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            ListTag cellList = new ListTag();
            for (Map.Entry<Long, Double> cellEntry : dimEntry.getValue().entrySet()) {
                CompoundTag cellTag = new CompoundTag();
                cellTag.putLong("Pos", cellEntry.getKey());
                cellTag.putDouble("Temp", cellEntry.getValue());
                cellList.add(cellTag);
            }
            sourcesTag.put(dim.toString(), cellList);
        }
        tag.put("Sources", sourcesTag);
    }

    public void loadFromNBT(CompoundTag tag) {
        if (tag.contains("Grids")) {
            CompoundTag dimsTag = tag.getCompound("Grids");
            for (String dimKey : dimsTag.getAllKeys()) {
                ResourceLocation dim = ResourceLocation.tryParse(dimKey);
                if (dim == null) continue;
                ListTag cellList = dimsTag.getList(dimKey, 10);
                ConcurrentHashMap<Long, AtomicCell> grid = grids.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
                for (int i = 0; i < cellList.size(); i++) {
                    CompoundTag cellTag = cellList.getCompound(i);
                    long pos = cellTag.getLong("Pos");
                    double temp = cellTag.getDouble("Temp");
                    grid.put(pos, new AtomicCell(temp, 0.0));
                    positionDimensions.putIfAbsent(pos, dim);
                    trackCellInChunk(dim, pos);
                    activeSet.add(pos);
                }
            }
        }

        if (tag.contains("Sources")) {
            CompoundTag sourcesTag = tag.getCompound("Sources");
            for (String dimKey : sourcesTag.getAllKeys()) {
                ResourceLocation dim = ResourceLocation.tryParse(dimKey);
                if (dim == null) continue;
                ListTag cellList = sourcesTag.getList(dimKey, 10);
                ConcurrentHashMap<Long, Double> dimSources = sourceTemps.computeIfAbsent(dim,
                        k -> new ConcurrentHashMap<>());
                for (int i = 0; i < cellList.size(); i++) {
                    CompoundTag cellTag = cellList.getCompound(i);
                    long pos = cellTag.getLong("Pos");
                    double temp = cellTag.getDouble("Temp");
                    dimSources.put(pos, temp);
                    activeSet.add(pos);
                    positionDimensions.putIfAbsent(pos, dim);
                    trackCellInChunk(dim, pos);
                }
            }
        }
    }

    public Map<ResourceLocation, ConcurrentHashMap<Long, Double>> getSourceTemps() {
        return sourceTemps;
    }

    private void injectSources() {
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, Double>> dimEntry : sourceTemps.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);

            for (Map.Entry<Long, Double> srcEntry : dimEntry.getValue().entrySet()) {
                long pos = srcEntry.getKey();
                double targetTemp = srcEntry.getValue();

                AtomicCell cell = (grid != null) ? grid.get(pos) : null;
                if (cell != null && Double.doubleToRawLongBits(cell.getCurrent())
                        == Double.doubleToRawLongBits(targetTemp)) {
                    activeSet.add(pos);
                    continue;
                }

                if (grid == null) {
                    grid = grids.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
                }
                boolean newCell = false;
                if (cell == null) {
                    cell = grid.computeIfAbsent(pos, k -> new AtomicCell(ambientTemp, 0.0));
                    newCell = true;
                }

                double oldTemp = cell.getCurrent();
                cell.setCurrent(targetTemp);

                if (newCell) {
                    trackCellInChunk(dim, pos);
                    positionDimensions.putIfAbsent(pos, dim);
                }

                fireTemperatureChange(dim, pos, oldTemp, targetTemp);
                activeSet.add(pos);
                positionDimensions.putIfAbsent(pos, dim);
            }
        }
    }

    private void trackCellInChunk(ResourceLocation dim, long pos) {
        long chunkPos = ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
        chunkCellMap.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkPos, k -> ConcurrentHashMap.newKeySet())
                .add(pos);
    }

    private void resolvePropertiesForTick(List<Long> positions) {
        for (long packedPos : positions) {
            ResourceLocation dim = positionDimensions.get(packedPos);
            if (dim == null)
                continue;

            ConcurrentHashMap<Long, CachedProps> dimCache = propsCache.computeIfAbsent(dim,
                    k -> new ConcurrentHashMap<>());

            dimCache.computeIfAbsent(packedPos, k -> resolveProps(dim, k));

            int bx = BlockPos.getX(packedPos);
            int by = BlockPos.getY(packedPos);
            int bz = BlockPos.getZ(packedPos);

            for (int[] offset : NEIGHBORS) {
                int ny = by + offset[1];
                if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y)
                    continue;

                long neighborPacked = BlockPos.asLong(
                        bx + offset[0], ny, bz + offset[2]);
                dimCache.computeIfAbsent(neighborPacked, k -> resolveProps(dim, k));
            }
        }
    }

    private CachedProps resolveProps(ResourceLocation dim, long packedPos) {
        ServerLevel level = getLevelForDim(dim);
        if (level == null)
            return DEFAULT_PROPS;

        try {
            BlockPos pos = BlockPos.of(packedPos);
            if (!level.isLoaded(pos))
                return DEFAULT_PROPS;

            BlockState state = level.getBlockState(pos);

            if (state.isAir()) {
                if (settings.isFluidSimulationEnabled()) {
                    return AIR_INSULATING;
                }
                return airInsulates ? AIR_INSULATING : AIR_CONDUCTIVE;
            }

            if (!state.getFluidState().isEmpty()) {
                boolean water = state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                if (settings.isFluidSimulationEnabled()) {
                    return water ? WATER_INSULATING : AIR_INSULATING;
                }
                if (airInsulates) {
                    return water ? WATER_INSULATING : AIR_INSULATING;
                }
                return water ? WATER_CONDUCTIVE : AIR_CONDUCTIVE;
            }

            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            ThermalPropertiesRegistry registry = configManager.getThermalPropertiesRegistry();

            if (registry != null && blockId != null) {
                ThermalProperties props = registry.get(blockId);
                if (props != null) {
                    return new CachedProps(props.getConductivity(), props.getHeatCapacity(),
                            props.getDissipationRate(), false, false, props.getConductivity() > 0.0);
                }
            }
            return DEFAULT_PROPS;
        } catch (Exception e) {
            return DEFAULT_PROPS;
        }
    }

    private CachedProps getCachedProps(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, CachedProps> dimCache = propsCache.get(dim);
        if (dimCache == null)
            return DEFAULT_PROPS;
        CachedProps props = dimCache.get(packedPos);
        return props != null ? props : DEFAULT_PROPS;
    }

    private CachedProps getOrResolveProps(ResourceLocation dim, long packedPos) {
        return propsCache.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(packedPos, k -> resolveProps(dim, k));
    }

    private void fireTemperatureChange(ResourceLocation dim, long packedPos, double oldTemp, double newTemp) {
        if (Math.abs(newTemp - oldTemp) <= deltaThreshold) return;
        HeatAPI api = HeatAPI.get();
        if (!(api instanceof HeatAPIImpl apiImpl)) return;
        ServerLevel level = getLevelForDim(dim);
        if (level == null) return;
        BlockPos bpos = BlockPos.of(packedPos);
        double biomeOffset = configManager.getBiomeConfig().getOffset(level.getBiome(bpos));
        double oldWorld = oldTemp + biomeOffset;
        double newWorld = newTemp + biomeOffset;
        if (Math.abs(newWorld - oldWorld) > deltaThreshold) {
            apiImpl.fireTemperatureChange(new TemperatureChangeEvent(level, bpos, oldWorld, newWorld));
        }
    }

    private void computeBatch(long[] batch) {
        for (long packedPos : batch) {
            ResourceLocation dim = positionDimensions.get(packedPos);
            if (dim == null)
                continue;

            ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);
            if (grid == null)
                continue;

            AtomicCell myCell = grid.get(packedPos);
            if (myCell == null)
                continue;

            CachedProps myProps = getCachedProps(dim, packedPos);
            if (!myProps.conductive) {
                continue;
            }

            ConcurrentHashMap<Long, Double> dimSources = sourceTemps.get(dim);
            if (dimSources != null && dimSources.containsKey(packedPos)) {
                dirtyCells.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(packedPos);
                continue;
            }

            double myTemp = myCell.getCurrent();
            double myCond = myProps.conductivity;
            double myCp = myProps.heatCapacity;
            if (myCp <= 0.0 || !Double.isFinite(myCp))
                continue;

            int bx = BlockPos.getX(packedPos);
            int by = BlockPos.getY(packedPos);
            int bz = BlockPos.getZ(packedPos);

            double netHeat = 0.0; // heat entering this cell
            double maxNeighborDiff = 0.0;

            for (int[] offset : NEIGHBORS) {
                int nx = bx + offset[0];
                int ny = by + offset[1];
                int nz = bz + offset[2];

                if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y)
                    continue;

                long neighborPacked = BlockPos.asLong(nx, ny, nz);
                CachedProps nProps = getCachedProps(dim, neighborPacked);

                if (!nProps.conductive) {
                    double tempDiff = myTemp - ambientTemp;
                    double absDiff = Math.abs(tempDiff);
                    if (absDiff < deltaThreshold * 0.5)
                        continue;

                    double hEff = myProps.dissipationRate;
                    if (nProps.isWater) {
                        hEff *= waterTransferMult;
                    }
                    netHeat -= hEff * tempDiff * dissipationMult;
                    if (absDiff > maxNeighborDiff) maxNeighborDiff = absDiff;
                    continue;
                }

                if (nProps.conductivity <= 0.0)
                    continue;

                AtomicCell neighborCell = grid.get(neighborPacked);
                double neighborTemp = neighborCell != null ? neighborCell.getCurrent() : ambientTemp;

                double tempDiff = myTemp - neighborTemp;
                double absDiff = Math.abs(tempDiff);
                if (absDiff < deltaThreshold * 0.5)
                    continue;

                if (absDiff > maxNeighborDiff) maxNeighborDiff = absDiff;

                double kEff = harmonicMean(myCond, nProps.conductivity);
                if (kEff <= 0.0)
                    continue;

                double q = kEff * tempDiff * dissipationMult;
                netHeat -= q;

                if (neighborCell == null && absDiff > deltaThreshold) {
                    activateNeighbor(dim, neighborPacked, grid);
                }
            }

            if (Math.abs(netHeat) < 1e-9)
                continue;

            double dT = netHeat / myCp;

            if (maxNeighborDiff > 0.0) {
                double maxAbs = maxNeighborDiff * 0.45;
                if (Math.abs(dT) > maxAbs) {
                    dT = Math.signum(dT) * maxAbs;
                }
            }

            if (Math.abs(dT) < 1e-5)
                continue;

            myCell.addDelta(dT);
            dirtyCells.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(packedPos);
        }
    }

    private static double harmonicMean(double a, double b) {
        double sum = a + b;
        return sum > 0.0 ? (2.0 * a * b) / sum : 0.0;
    }

    private void activateNeighbor(ResourceLocation dim, long neighborPacked,
            ConcurrentHashMap<Long, AtomicCell> grid) {
        grid.computeIfAbsent(neighborPacked, k -> new AtomicCell(ambientTemp, 0.0));
        trackCellInChunk(dim, neighborPacked);
        positionDimensions.putIfAbsent(neighborPacked, dim);
        pendingActivation.add(neighborPacked);
    }

    private int commitGeneration() {
        int changed = 0;
        List<Long> toEvict = null;

        for (Map.Entry<ResourceLocation, Set<Long>> dimEntry : dirtyCells.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            Set<Long> dirties = dimEntry.getValue();
            ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);
            if (grid == null)
                continue;

            for (long pos : dirties) {
                AtomicCell cell = grid.get(pos);
                if (cell == null)
                    continue;

                double delta = cell.getDelta();
                cell.setDelta(0.0);

                ConcurrentHashMap<Long, Double> dimSources = sourceTemps.get(dim);
                boolean isSource = (dimSources != null && dimSources.containsKey(pos));

                if (isSource) {
                    Double sourceTemp = dimSources.get(pos);
                    if (sourceTemp != null) {
                        double oldTemp = cell.getCurrent();
                        cell.setCurrent(sourceTemp);
                        fireTemperatureChange(dim, pos, oldTemp, sourceTemp);
                    }
                    changed++;
                    activeSet.add(pos);
                    wakeNeighbors(dim, pos, grid);
                } else if (Math.abs(delta) > 0.001) {
                    double oldTemp = cell.getCurrent();
                    double newTemp = oldTemp + delta;
                    cell.setCurrent(newTemp);
                    changed++;
                    fireTemperatureChange(dim, pos, oldTemp, newTemp);
                    activeSet.add(pos);
                    if (Math.abs(delta) > deltaThreshold * 0.1) {
                        wakeNeighbors(dim, pos, grid);
                    }
                }

                double current = cell.getCurrent();
                if (!isSource
                        && Math.abs(current - ambientTemp) < deltaThreshold
                        && Math.abs(delta) < 0.01) {
                    if (toEvict == null)
                        toEvict = new ArrayList<>();
                    toEvict.add(pos);
                }
            }
            dirties.clear();

            if (toEvict != null) {
                for (long pos : toEvict) {
                    grid.remove(pos);
                    positionDimensions.remove(pos);
                    activeSet.remove(pos);
                    ConcurrentHashMap<Long, CachedProps> dimCache = propsCache.get(dim);
                    if (dimCache != null)
                        dimCache.remove(pos);

                    long chunkPos = ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
                    ConcurrentHashMap<Long, Set<Long>> chunks = chunkCellMap.get(dim);
                    if (chunks != null) {
                        Set<Long> cells = chunks.get(chunkPos);
                        if (cells != null)
                            cells.remove(pos);
                    }
                }
                toEvict.clear();
            }
        }

        for (long pos : pendingActivation) {
            activeSet.add(pos);
        }
        pendingActivation.clear();

        return changed;
    }

    private void wakeNeighbors(ResourceLocation dim, long packedPos,
            ConcurrentHashMap<Long, AtomicCell> grid) {
        int bx = BlockPos.getX(packedPos);
        int by = BlockPos.getY(packedPos);
        int bz = BlockPos.getZ(packedPos);
        for (int[] offset : NEIGHBORS) {
            int ny = by + offset[1];
            if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y) continue;
            long neighborPacked = BlockPos.asLong(bx + offset[0], ny, bz + offset[2]);
            CachedProps nProps = getOrResolveProps(dim, neighborPacked);
            if (!nProps.conductive || nProps.conductivity <= 0.0)
                continue;
            if (grid.containsKey(neighborPacked)) {
                activeSet.add(neighborPacked);
            } else {
                activateNeighbor(dim, neighborPacked, grid);
            }
        }
    }

    private ServerLevel getLevelForDim(ResourceLocation dim) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dim))
                return level;
        }
        return null;
    }

    public double getTemperature(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);
        if (grid == null)
            return ambientTemp;
        AtomicCell cell = grid.get(packedPos);
        return cell != null ? cell.getCurrent() : ambientTemp;
    }

    public OptionalDouble getExactTemperature(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);
        if (grid == null)
            return OptionalDouble.empty();
        AtomicCell cell = grid.get(packedPos);
        return cell != null ? OptionalDouble.of(cell.getCurrent()) : OptionalDouble.empty();
    }

    public void addSource(ResourceLocation dim, long packedPos, double temperature) {
        sourceTemps.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .put(packedPos, temperature);

        ConcurrentHashMap<Long, AtomicCell> grid = grids.computeIfAbsent(dim,
                k -> new ConcurrentHashMap<>());
        AtomicCell cell = grid.computeIfAbsent(packedPos, k -> new AtomicCell(ambientTemp, 0.0));
        double oldTemp = cell.getCurrent();
        cell.setCurrent(temperature);

        trackCellInChunk(dim, packedPos);
        positionDimensions.putIfAbsent(packedPos, dim);
        activeSet.add(packedPos);

        propsCache.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .put(packedPos, resolveProps(dim, packedPos));

        wakeNeighbors(dim, packedPos, grid);
        fireTemperatureChange(dim, packedPos, oldTemp, temperature);
    }

    public void updateSource(ResourceLocation dim, long packedPos, double temperature) {
        addSource(dim, packedPos, temperature);
    }

    public void setCellTemperature(ResourceLocation dim, long packedPos, double temperature) {
        ConcurrentHashMap<Long, AtomicCell> grid = grids.computeIfAbsent(dim,
                k -> new ConcurrentHashMap<>());
        AtomicCell cell = grid.computeIfAbsent(packedPos, k -> new AtomicCell(ambientTemp, 0.0));
        double oldTemp = cell.getCurrent();

        cell.setCurrent(temperature);
        cell.setDelta(0.0);

        trackCellInChunk(dim, packedPos);
        positionDimensions.putIfAbsent(packedPos, dim);

        propsCache.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .putIfAbsent(packedPos, resolveProps(dim, packedPos));

        dirtyCells.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(packedPos);
        activeSet.add(packedPos);
        wakeNeighbors(dim, packedPos, grid);

        fireTemperatureChange(dim, packedPos, oldTemp, temperature);
    }

    public void removeSource(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, Double> dimSources = sourceTemps.get(dim);
        if (dimSources != null) {
            dimSources.remove(packedPos);
        }

        ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);
        if (grid != null) {
            AtomicCell cell = grid.get(packedPos);
            if (cell != null) {
                double oldTemp = cell.getCurrent();
                cell.setCurrent(ambientTemp);
                cell.setDelta(0.0);
                fireTemperatureChange(dim, packedPos, oldTemp, ambientTemp);
            }
        }

        positionDimensions.putIfAbsent(packedPos, dim);
        activeSet.add(packedPos);
        dirtyCells.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(packedPos);
    }

    public void clearChunk(ResourceLocation dim, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);
        ConcurrentHashMap<Long, Double> dimSources = sourceTemps.get(dim);
        ConcurrentHashMap<Long, CachedProps> dimCache = propsCache.get(dim);
        ConcurrentHashMap<Long, Set<Long>> chunkCells = chunkCellMap.get(dim);
        Set<Long> dirties = dirtyCells.get(dim);

        if (grid == null && dimSources == null)
            return;

        long chunkPosAsLong = ChunkPos.asLong(chunkX, chunkZ);
        Set<Long> cellsInChunk = (chunkCells != null) ? chunkCells.remove(chunkPosAsLong) : null;

        if (cellsInChunk != null && !cellsInChunk.isEmpty()) {
            for (long pos : cellsInChunk) {
                if (grid != null)
                    grid.remove(pos);
                if (dimCache != null)
                    dimCache.remove(pos);
                positionDimensions.remove(pos);
                if (dimSources != null)
                    dimSources.remove(pos);
                if (dirties != null)
                    dirties.remove(pos);
                activeSet.remove(pos);
                generationPending.remove(pos);
                pendingActivation.remove(pos);
            }
        }
    }

    public Map<BlockPos, Double> getChunkTemperatures(ResourceLocation dim, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);
        if (grid == null)
            return Collections.emptyMap();

        ConcurrentHashMap<Long, Set<Long>> dimChunks = chunkCellMap.get(dim);
        if (dimChunks == null)
            return Collections.emptyMap();

        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        Set<Long> cellsInChunk = dimChunks.get(chunkKey);
        if (cellsInChunk == null || cellsInChunk.isEmpty())
            return Collections.emptyMap();

        Map<BlockPos, Double> result = new HashMap<>();
        for (long packed : cellsInChunk) {
            AtomicCell cell = grid.get(packed);
            if (cell != null) {
                result.put(BlockPos.of(packed), cell.getCurrent());
            }
        }
        return result;
    }

    public void invalidateCache(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, CachedProps> dimCache = propsCache.get(dim);
        if (dimCache != null)
            dimCache.remove(packedPos);
    }

    public void clearPropsCache() {
        propsCache.clear();
    }

    public void onBlockChanged(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, CachedProps> dimCache = propsCache.get(dim);
        if (dimCache != null)
            dimCache.remove(packedPos);

        positionDimensions.putIfAbsent(packedPos, dim);

        ConcurrentHashMap<Long, AtomicCell> grid = grids.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        CachedProps props = resolveProps(dim, packedPos);
        propsCache.computeIfAbsent(dim, k -> new ConcurrentHashMap<>()).put(packedPos, props);

        if (props.conductive) {
            grid.computeIfAbsent(packedPos, k -> new AtomicCell(ambientTemp, 0.0));
            trackCellInChunk(dim, packedPos);
            activeSet.add(packedPos);
        } else {
            grid.remove(packedPos);
            activeSet.remove(packedPos);
        }

        int bx = BlockPos.getX(packedPos);
        int by = BlockPos.getY(packedPos);
        int bz = BlockPos.getZ(packedPos);
        for (int[] offset : NEIGHBORS) {
            int ny = by + offset[1];
            if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y) continue;
            long neighborPacked = BlockPos.asLong(bx + offset[0], ny, bz + offset[2]);
            if (grid.containsKey(neighborPacked)) {
                positionDimensions.putIfAbsent(neighborPacked, dim);
                activeSet.add(neighborPacked);
            }
        }
    }

    private void trimPropsCache() {
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, CachedProps>> dimEntry : propsCache.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            ConcurrentHashMap<Long, CachedProps> dimCache = dimEntry.getValue();
            ConcurrentHashMap<Long, AtomicCell> grid = grids.get(dim);

            if (grid == null) {
                dimCache.clear();
                continue;
            }

            if (dimCache.size() > grid.size() * 3) {
                Iterator<Long> it = dimCache.keySet().iterator();
                int removed = 0;
                int limit = dimCache.size() - grid.size() * 2;
                while (it.hasNext() && removed < limit) {
                    long pos = it.next();
                    if (!grid.containsKey(pos)) {
                        it.remove();
                        removed++;
                    }
                }
            }
        }
    }

    public double getLastTickMs() {
        return lastTickNanos / 1_000_000.0;
    }

    public int getLastActiveSize() {
        return lastActiveSize;
    }

    public int getLastBlocksProcessed() {
        return lastBlocksProcessed;
    }

    public long getTotalTicks() {
        return totalTicks.get();
    }

    public long getTotalBlocksProcessed() {
        return totalBlocksProcessed.get();
    }

    public double getAverageTickMs() {
        long count = totalTicks.get();
        if (count == 0)
            return 0;
        return (totalTickNanos.get() / (double) count) / 1_000_000.0;
    }

    public int getGridSize() {
        int total = 0;
        for (ConcurrentHashMap<Long, AtomicCell> grid : grids.values()) {
            total += grid.size();
        }
        return total;
    }

    public int getSourceCount() {
        int total = 0;
        for (ConcurrentHashMap<Long, Double> dimSources : sourceTemps.values()) {
            total += dimSources.size();
        }
        return total;
    }

    public int getActiveCellCount() {
        return activeSet.size();
    }

    public double getAmbientTemp() {
        return ambientTemp;
    }
}
