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

public class BFSHeatEngine {

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
        final boolean isAir;
        final boolean isWater;

        CachedProps(double conductivity, double heatCapacity, double dissipationRate,
                boolean isAir, boolean isWater) {
            this.conductivity = conductivity;
            this.heatCapacity = heatCapacity;
            this.dissipationRate = dissipationRate;
            this.isAir = isAir;
            this.isWater = isWater;
        }
    }

    private static final CachedProps AIR_PROPS = new CachedProps(0.0, Double.POSITIVE_INFINITY, 0.0, true, false);
    private static final CachedProps WATER_PROPS = new CachedProps(0.0, Double.POSITIVE_INFINITY, 0.0, true, true);
    private static final CachedProps DEFAULT_PROPS = new CachedProps(
            ThermalProperties.defaults().getConductivity(),
            ThermalProperties.defaults().getHeatCapacity(),
            ThermalProperties.defaults().getDissipationRate(),
            false, false);

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, CachedProps>> propsCache = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final HeatConfigManager configManager;
    private final SimulationSettings settings;

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, AtomicCell>> grids = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, Set<Long>>> chunkCellMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<ResourceLocation, Set<Long>> dirtyCells = new ConcurrentHashMap<>();

    private volatile ConcurrentLinkedQueue<Long> frontier = new ConcurrentLinkedQueue<>();
    private volatile Set<Long> frontierSet = ConcurrentHashMap.newKeySet();

    private volatile ConcurrentLinkedQueue<Long> nextFrontier = new ConcurrentLinkedQueue<>();
    private volatile Set<Long> nextFrontierSet = ConcurrentHashMap.newKeySet();

    private volatile Set<Long> stepFrontierSnapshot = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<Long, ResourceLocation> positionDimensions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, Double>> sourceTemps = new ConcurrentHashMap<>();

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
    private int gameTicksSinceLastSwap = 0;

    private volatile long lastTickNanos;
    private volatile int lastFrontierSize;
    private volatile int lastBlocksProcessed;
    private final AtomicLong totalTickNanos = new AtomicLong();
    private final AtomicLong totalTicks = new AtomicLong();
    private final AtomicLong totalBlocksProcessed = new AtomicLong();

    public BFSHeatEngine(MinecraftServer server, HeatConfigManager configManager) {
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
        // Keep the adaptive budget within whatever the new safety cap allows.
        if (adaptiveCellBudget > workBudget) adaptiveCellBudget = workBudget;
        if (adaptiveCellBudget < MIN_ADAPTIVE_BUDGET) adaptiveCellBudget = MIN_ADAPTIVE_BUDGET;
    }

    private volatile boolean processing = false;

    public void start() {
        int threads = Math.max(1, settings.getWorkerThreads());
        pool = new ForkJoinPool(threads,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t, e) -> Thermodynamica.LOGGER.error("BFS worker error", e),
                true);
        Thermodynamica.LOGGER.info("BFS heat engine started with {} worker threads", threads);
    }

    public void stopProcessing() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        Thermodynamica.LOGGER.info("BFS heat engine processing stopped");
    }

    public void stop() {
        stopProcessing();
        grids.clear();
        chunkCellMap.clear();
        dirtyCells.clear();
        frontier.clear();
        frontierSet.clear();
        nextFrontier.clear();
        nextFrontierSet.clear();
        stepFrontierSnapshot.clear();
        gameTicksSinceLastSwap = 0;
        adaptiveCellBudget = INITIAL_ADAPTIVE_BUDGET;
        positionDimensions.clear();
        sourceTemps.clear();
        propsCache.clear();
        Thermodynamica.LOGGER.info("BFS heat engine stopped");
    }

    public void tick() {
        if (pool == null || pool.isShutdown())
            return;

        long start = System.nanoTime();

        // Periodically clean up the property cache to prevent unbounded growth
        long tickNum = totalTicks.get();
        if (tickNum % 10 == 0) {
            trimPropsCache();
        }

        injectSources();

        // --- Step swap pacing ------------------------------------------------------
        // If the current step is finished (frontier empty) and enough game ticks have
        // passed, promote nextFrontier and snapshot it for the next step. Increment
        // the cooldown counter *before* the threshold check so a configured
        // simulation_interval_ticks=N produces a swap every N game ticks (matching
        // the prior fixed-interval behavior on average).
        if (frontier.isEmpty()) {
            if (nextFrontier.isEmpty()) {
                // Truly idle — no work pending.
                recordTickStats(start, 0, 0);
                return;
            }
            gameTicksSinceLastSwap++;
            if (gameTicksSinceLastSwap < simulationIntervalTicks) {
                recordTickStats(start, 0, 0);
                return;
            }
            frontier = nextFrontier;
            frontierSet = nextFrontierSet;
            nextFrontier = new ConcurrentLinkedQueue<>();
            nextFrontierSet = ConcurrentHashMap.newKeySet();
            Set<Long> snap = ConcurrentHashMap.newKeySet(frontier.size());
            snap.addAll(frontier);
            stepFrontierSnapshot = snap;
            gameTicksSinceLastSwap = 0;
        } else if (stepFrontierSnapshot.isEmpty()) {
            // Cold start (e.g. just after loadFromNBT): frontier is non-empty but we
            // never got to take a snapshot. Build one now so pair-dedup is correct.
            Set<Long> snap = ConcurrentHashMap.newKeySet(frontier.size());
            snap.addAll(frontier);
            stepFrontierSnapshot = snap;
        }

        // --- Pull this tick's slice (adaptive, capped by safety budget) -----------
        int cellsThisTick = Math.min(adaptiveCellBudget, workBudget);
        List<long[]> batches = new ArrayList<>();
        long[] batch = new long[BATCH_SIZE];
        int batchIdx = 0;

        List<Long> tickPositions = new ArrayList<>();
        int count = 0;
        while (count < cellsThisTick) {
            Long packed = frontier.poll();
            if (packed == null) break;
            frontierSet.remove(packed);
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
                futures.add(pool.submit(() -> computeBatch(b, stepFrontierSnapshot)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    Thermodynamica.LOGGER.warn("BFS compute timed out, skipping tick");
                    break;
                } catch (Exception e) {
                    Thermodynamica.LOGGER.error("BFS compute error", e);
                }
            }
        }

        int blocksProcessed = applyDeltas();

        long elapsed = System.nanoTime() - start;

        // --- Adaptive sizing ------------------------------------------------------
        // Cheap exponential controller: if we breezed through the work, take more
        // next time; if we blew the budget, take less. Only adjusts when we actually
        // ran the budget — if the frontier was small we just exit early without
        // changing the target.
        if (count >= cellsThisTick) {
            double elapsedMs = elapsed / 1_000_000.0;
            if (elapsedMs < timeBudgetMs * 0.5) {
                int next = adaptiveCellBudget * 2;
                adaptiveCellBudget = Math.min(next, workBudget);
            } else if (elapsedMs > timeBudgetMs * 1.5) {
                int next = adaptiveCellBudget / 2;
                adaptiveCellBudget = Math.max(next, MIN_ADAPTIVE_BUDGET);
            }
        }

        recordTickStats(start, count, blocksProcessed);
    }

    private void recordTickStats(long startNanos, int frontierSize, int blocksProcessed) {
        long elapsed = System.nanoTime() - startNanos;
        lastTickNanos = elapsed;
        lastFrontierSize = frontierSize;
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
                    if (frontierSet.add(pos)) frontier.add(pos);
                    positionDimensions.putIfAbsent(pos, dim);
                    trackCellInChunk(dim, pos);
                }
            }
        }
    }

    public java.util.Map<ResourceLocation, ConcurrentHashMap<Long, Double>> getSourceTemps() {
        return sourceTemps;
    }

    private void injectSources() {
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, Double>> dimEntry : sourceTemps.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            ConcurrentHashMap<Long, AtomicCell> grid = grids.computeIfAbsent(dim,
                    k -> new ConcurrentHashMap<>());

            for (Map.Entry<Long, Double> srcEntry : dimEntry.getValue().entrySet()) {
                long pos = srcEntry.getKey();
                double targetTemp = srcEntry.getValue();
                AtomicCell cell = grid.computeIfAbsent(pos, k -> new AtomicCell(ambientTemp, 0.0));

                double oldTemp = cell.getCurrent();
                cell.setCurrent(targetTemp);

                trackCellInChunk(dim, pos);

                fireTemperatureChange(dim, pos, oldTemp, targetTemp);

                if (Math.abs(targetTemp - oldTemp) > deltaThreshold) {
                    positionDimensions.putIfAbsent(pos, dim);
                    if (frontierSet.add(pos)) frontier.add(pos);
                }
            }
        }
    }

    private void trackCellInChunk(ResourceLocation dim, long pos) {
        long chunkPos = ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
        chunkCellMap.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkPos, k -> ConcurrentHashMap.newKeySet())
                .add(pos);
    }

    private void resolvePropertiesForTick(List<Long> frontierPositions) {
        for (long packedPos : frontierPositions) {
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
                if (ny < -64 || ny > 319)
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

            if (state.isAir())
                return AIR_PROPS;

            if (!state.getFluidState().isEmpty()) {
                boolean water = state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
                return water ? WATER_PROPS : AIR_PROPS;
            }

            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            ThermalPropertiesRegistry registry = configManager.getThermalPropertiesRegistry();

            if (registry != null && blockId != null) {
                ThermalProperties props = registry.get(blockId);
                if (props != null) {
                    return new CachedProps(props.getConductivity(), props.getHeatCapacity(),
                            props.getDissipationRate(), false, false);
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

    // Centralized helper to fire TemperatureChangeEvent with biome offset and safety checks.
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

    private void computeBatch(long[] batch, Set<Long> currentFrontier) {
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
            if (myProps.isAir)
                continue; // Air cells should not exist in the grid; defensive skip.

            double myTemp;
            myTemp = myCell.getCurrent();
            double myCond = myProps.conductivity;
            double myCp = myProps.heatCapacity;

            int bx = BlockPos.getX(packedPos);
            int by = BlockPos.getY(packedPos);
            int bz = BlockPos.getZ(packedPos);

            for (int[] offset : NEIGHBORS) {
                int nx = bx + offset[0];
                int ny = by + offset[1];
                int nz = bz + offset[2];

                if (ny < -64 || ny > 319)
                    continue;

                long neighborPacked = BlockPos.asLong(nx, ny, nz);
                CachedProps nProps = getCachedProps(dim, neighborPacked);

                if (nProps.isAir) {
                    applyNewtonianCooling(dim, packedPos, myCell, myTemp, myCp,
                            myProps.dissipationRate, nProps.isWater);
                    continue;
                }

                if (nProps.conductivity <= 0.0)
                    continue;

                if (currentFrontier.contains(neighborPacked) && packedPos > neighborPacked) {
                    continue;
                }

                AtomicCell neighborCell = grid.get(neighborPacked);
                double neighborTemp;
                if (neighborCell != null) {
                    neighborTemp = neighborCell.getCurrent();
                } else {
                    neighborTemp = ambientTemp;
                }

                double tempDiff = myTemp - neighborTemp;
                if (Math.abs(tempDiff) < deltaThreshold * 0.5)
                    continue;

                if (neighborCell == null) {
                    neighborCell = grid.computeIfAbsent(neighborPacked,
                            k -> new AtomicCell(ambientTemp, 0.0));
                }

                applyFourierConduction(dim, packedPos, neighborPacked, myCell, neighborCell,
                        myCond, myCp, nProps.conductivity, nProps.heatCapacity, tempDiff);
            }
        }
    }

    private void applyFourierConduction(ResourceLocation dim, long fromPacked, long toPacked,
            AtomicCell fromCell, AtomicCell toCell, double kFrom, double cpFrom,
            double kTo, double cpTo, double tempDiff) {

        double kEff = (kFrom + kTo) > 0.0 ? (2.0 * kFrom * kTo) / (kFrom + kTo) : 0.0;
        if (kEff <= 0.0)
            return;

        double q = kEff * tempDiff * dissipationMult;

        double dTfrom = q / cpFrom;
        double dTto = q / cpTo;

        double maxAbs = Math.abs(tempDiff) * 0.45;
        double worst = Math.max(Math.abs(dTfrom), Math.abs(dTto));
        if (worst > maxAbs && worst > 0.0) {
            double scale = maxAbs / worst;
            dTfrom *= scale;
            dTto *= scale;
        }

        if (Math.abs(dTfrom) < 1e-5 && Math.abs(dTto) < 1e-5)
            return;

        fromCell.addDelta(-dTfrom);
        toCell.addDelta(dTto);

        trackCellInChunk(dim, toPacked);
        positionDimensions.putIfAbsent(toPacked, dim);
        Set<Long> dimDirty = dirtyCells.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet());
        dimDirty.add(toPacked);
        dimDirty.add(fromPacked);
    }

    private void applyNewtonianCooling(ResourceLocation dim, long packedPos, AtomicCell myCell, double myTemp, double myCp, double h, boolean isWater) {

        double tempDiff = myTemp - ambientTemp;
        if (Math.abs(tempDiff) < deltaThreshold * 0.5)
            return;

        double hEff = h;
        if (isWater) {
            hEff *= waterTransferMult;
        }

        double q = hEff * tempDiff * dissipationMult;
        double dT = q / myCp;

        double maxAbs = Math.abs(tempDiff) * 0.45;
        if (Math.abs(dT) > maxAbs) {
            dT = Math.signum(dT) * maxAbs;
        }

        if (Math.abs(dT) < 1e-5)
            return;

        myCell.addDelta(-dT);

        positionDimensions.putIfAbsent(packedPos, dim);
        dirtyCells.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(packedPos);
    }

    private int applyDeltas() {
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

                double delta;
                delta = cell.getDelta();
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
                    if (Math.abs(delta) > (deltaThreshold * 0.5)) {
                        positionDimensions.putIfAbsent(pos, dim);
                        if (nextFrontierSet.add(pos)) nextFrontier.add(pos);
                    }
                } else if (Math.abs(delta) > 0.001) {
                    double oldTemp = cell.getCurrent();
                    double newTemp = oldTemp + delta;
                    cell.setCurrent(newTemp);
                    changed++;

                    fireTemperatureChange(dim, pos, oldTemp, newTemp);

                    if (Math.abs(delta) > (deltaThreshold * 0.1)) {
                        positionDimensions.putIfAbsent(pos, dim);
                        if (nextFrontierSet.add(pos)) nextFrontier.add(pos);
                    }
                }

                double current;
                current = cell.getCurrent();

                if (Math.abs(current - ambientTemp) < deltaThreshold
                        && Math.abs(delta) < 0.01) {
                    if (!isSource) {
                        if (toEvict == null)
                            toEvict = new ArrayList<>();
                        toEvict.add(pos);
                    }
                }
            }
            dirties.clear();

            if (toEvict != null) {
                for (long pos : toEvict) {
                    grid.remove(pos);
                    positionDimensions.remove(pos);
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

        return changed;
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
        if (frontierSet.add(packedPos)) frontier.add(packedPos);

        propsCache.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .put(packedPos, resolveProps(dim, packedPos));

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

        if (Math.abs(temperature - oldTemp) > deltaThreshold) {
            if (frontierSet.add(packedPos)) frontier.add(packedPos);
        }

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
        if (frontierSet.add(packedPos)) frontier.add(packedPos);
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
                if (frontierSet.remove(pos)) frontier.remove(pos);
                if (nextFrontierSet.remove(pos)) nextFrontier.remove(pos);
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

    public int getLastFrontierSize() {
        return lastFrontierSize;
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

    public int getCurrentFrontierSize() {
        return frontier.size();
    }

    public double getAmbientTemp() {
        return ambientTemp;
    }
}
