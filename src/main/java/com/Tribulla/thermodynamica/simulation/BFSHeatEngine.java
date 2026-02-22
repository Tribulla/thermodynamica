package com.Tribulla.thermodynamica.simulation;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.config.SimulationSettings;
import com.Tribulla.thermodynamica.config.ThermalPropertiesRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class BFSHeatEngine {

    private static final int CELL_CURRENT = 0;
    private static final int CELL_DELTA = 1;

    private static final int[][] NEIGHBORS = {
            { 1, 0, 0 }, { -1, 0, 0 },
            { 0, 1, 0 }, { 0, -1, 0 },
            { 0, 0, 1 }, { 0, 0, -1 }
    };

    static final class CachedProps {
        final double conductivity;
        final double transferRate;
        final boolean isAir;
        final boolean isWater;

        CachedProps(double conductivity, double transferRate, boolean isAir, boolean isWater) {
            this.conductivity = conductivity;
            this.transferRate = transferRate;
            this.isAir = isAir;
            this.isWater = isWater;
        }
    }

    private static final CachedProps AIR_PROPS = new CachedProps(0.0, 0.0, true, false);
    private static final CachedProps DEFAULT_PROPS = new CachedProps(
            ThermalProperties.defaults().getConductivity(),
            ThermalProperties.defaults().getTransferRate(),
            false, false);

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, CachedProps>> propsCache = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final HeatConfigManager configManager;
    private final SimulationSettings settings;

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, double[]>> grids = new ConcurrentHashMap<>();

    // Tracks chunk heat nodes explicitly for O(1) removals during unload
    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, Set<Long>>> chunkCellMap = new ConcurrentHashMap<>();

    // Tracks only the cells modified this tick for O(delta) apply time instead of
    // O(grid)
    private final ConcurrentHashMap<ResourceLocation, Set<Long>> dirtyCells = new ConcurrentHashMap<>();

    private volatile Set<Long> frontier = ConcurrentHashMap.newKeySet();

    private volatile Set<Long> nextFrontier = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<Long, ResourceLocation> positionDimensions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, Double>> sourceTemps = new ConcurrentHashMap<>();

    private ForkJoinPool pool;

    private static final int BATCH_SIZE = 256;

    private double ambientTemp;
    private double deltaThreshold;
    private boolean airInsulates;
    private double waterTransferMult;
    private double dissipationMult;
    private int workBudget;

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
        this.ambientTemp = configManager.getTierDefinitions().getCelsius(settings.getAmbientTier());
        this.deltaThreshold = settings.getDeltaThreshold();
        this.airInsulates = settings.isAirInsulates();
        this.waterTransferMult = settings.getWaterTransferMultiplier();
        this.dissipationMult = settings.getDissipationMultiplier();
        this.workBudget = settings.getWorkBudgetPerTick();
    }

    public void start() {
        int threads = Math.max(1, settings.getWorkerThreads());
        pool = new ForkJoinPool(threads,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t, e) -> Thermodynamica.LOGGER.error("BFS worker error", e),
                true);
        Thermodynamica.LOGGER.info("BFS heat engine started with {} worker threads", threads);
    }

    public void stop() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        grids.clear();
        chunkCellMap.clear();
        dirtyCells.clear();
        frontier.clear();
        nextFrontier.clear();
        positionDimensions.clear();
        sourceTemps.clear();
        propsCache.clear();
        Thermodynamica.LOGGER.info("BFS heat engine stopped");
    }

    public void tick() {
        if (pool == null || pool.isShutdown())
            return;

        long start = System.nanoTime();

        injectSources();

        Set<Long> currentFrontier = frontier;
        int frontierSize = 0;

        List<long[]> batches = new ArrayList<>();
        long[] batch = new long[BATCH_SIZE];
        int batchIdx = 0;
        int totalQueued = 0;

        List<Long> tickPositions = new ArrayList<>();
        int count = 0;
        for (Long packed : currentFrontier) {
            if (count >= workBudget) {
                nextFrontier.add(packed);
            } else {
                tickPositions.add(packed);
                batch[batchIdx++] = packed;
                totalQueued++;
                frontierSize++;
                count++;
                if (batchIdx == BATCH_SIZE) {
                    batches.add(batch);
                    batch = new long[BATCH_SIZE];
                    batchIdx = 0;
                }
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

        frontier = nextFrontier;
        nextFrontier = ConcurrentHashMap.newKeySet();
        long elapsed = System.nanoTime() - start;
        lastTickNanos = elapsed;
        lastFrontierSize = frontierSize;
        lastBlocksProcessed = blocksProcessed;
        totalTickNanos.addAndGet(elapsed);
        totalTicks.incrementAndGet();
        totalBlocksProcessed.addAndGet(blocksProcessed);
    }

    private void injectSources() {
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, Double>> dimEntry : sourceTemps.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            ConcurrentHashMap<Long, double[]> grid = grids.computeIfAbsent(dim,
                    k -> new ConcurrentHashMap<>());

            for (Map.Entry<Long, Double> srcEntry : dimEntry.getValue().entrySet()) {
                long pos = srcEntry.getKey();
                double targetTemp = srcEntry.getValue();
                double[] cell = grid.computeIfAbsent(pos, k -> new double[] { ambientTemp, 0.0 });

                double oldTemp = cell[CELL_CURRENT];
                cell[CELL_CURRENT] = targetTemp;

                trackCellInChunk(dim, pos);

                if (Math.abs(targetTemp - oldTemp) > deltaThreshold) {
                    positionDimensions.putIfAbsent(pos, dim);
                    frontier.add(pos);
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

            boolean isWater = state.is(Blocks.WATER);
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            ThermalPropertiesRegistry registry = configManager.getThermalPropertiesRegistry();

            if (registry != null && blockId != null) {
                ThermalProperties props = registry.get(blockId);
                if (props != null) {
                    return new CachedProps(props.getConductivity(), props.getTransferRate(),
                            false, isWater);
                }
            }
            return new CachedProps(DEFAULT_PROPS.conductivity, DEFAULT_PROPS.transferRate,
                    false, isWater);
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

    private void computeBatch(long[] batch) {
        for (long packedPos : batch) {
            ResourceLocation dim = positionDimensions.get(packedPos);
            if (dim == null)
                continue;

            ConcurrentHashMap<Long, double[]> grid = grids.get(dim);
            if (grid == null)
                continue;

            double[] myCell = grid.get(packedPos);
            if (myCell == null)
                continue;

            double myTemp = myCell[CELL_CURRENT];

            CachedProps myProps = getCachedProps(dim, packedPos);
            double myCond = myProps.conductivity;
            double myTransfer = myProps.transferRate;
            if (myProps.isWater)
                myTransfer *= waterTransferMult;

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
                if (airInsulates && nProps.isAir)
                    continue;

                if (nProps.conductivity <= 0.0)
                    continue;

                // Check existing cell first — only create if there's actual heat to transfer
                double[] neighborCell = grid.get(neighborPacked);
                double neighborTemp;
                if (neighborCell != null) {
                    neighborTemp = neighborCell[CELL_CURRENT];
                } else {
                    neighborTemp = ambientTemp;
                }

                double tempDiff = myTemp - neighborTemp;

                if (Math.abs(tempDiff) < deltaThreshold * 0.5)
                    continue;

                // Now create the cell if needed — we know there's real heat flow
                if (neighborCell == null) {
                    neighborCell = grid.computeIfAbsent(neighborPacked,
                            k -> new double[] { ambientTemp, 0.0 });
                }

                double nTransfer = nProps.transferRate;
                if (nProps.isWater)
                    nTransfer *= waterTransferMult;

                double effectiveCond = Math.min(myCond, nProps.conductivity);
                double avgTransfer = (myTransfer + nTransfer) * 0.5;

                double transfer = tempDiff * effectiveCond * avgTransfer * dissipationMult;

                double maxTransfer = Math.abs(tempDiff) / 6.0;
                transfer = Math.max(-maxTransfer, Math.min(maxTransfer, transfer));

                if (Math.abs(transfer) < 0.01)
                    continue;

                synchronized (neighborCell) {
                    neighborCell[CELL_DELTA] += transfer;
                }

                trackCellInChunk(dim, neighborPacked);
                positionDimensions.putIfAbsent(neighborPacked, dim);
                Set<Long> dimDirty = dirtyCells.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet());
                dimDirty.add(neighborPacked);
                dimDirty.add(packedPos);
            }
        }
    }

    private int applyDeltas() {
        int changed = 0;
        List<Long> toEvict = null;

        for (Map.Entry<ResourceLocation, Set<Long>> dimEntry : dirtyCells.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            Set<Long> dirties = dimEntry.getValue();
            ConcurrentHashMap<Long, double[]> grid = grids.get(dim);

            if (grid == null)
                continue;

            for (long pos : dirties) {
                double[] cell = grid.get(pos);
                if (cell == null)
                    continue;

                double delta = cell[CELL_DELTA];
                cell[CELL_DELTA] = 0.0;

                ConcurrentHashMap<Long, Double> dimSources = sourceTemps.get(dim);
                boolean isSource = (dimSources != null && dimSources.containsKey(pos));

                if (isSource) {
                    Double sourceTemp = dimSources.get(pos);
                    if (sourceTemp != null) {
                        cell[CELL_CURRENT] = sourceTemp;
                    }
                    changed++;
                    if (Math.abs(delta) > (deltaThreshold * 0.5)) {
                        positionDimensions.putIfAbsent(pos, dim);
                        nextFrontier.add(pos);
                    }
                } else if (Math.abs(delta) > 0.001) {
                    cell[CELL_CURRENT] += delta;
                    changed++;

                    // Keeps smaller spreads active, solving the 'heat stopped spreading' bug
                    if (Math.abs(delta) > (deltaThreshold * 0.1)) {
                        positionDimensions.putIfAbsent(pos, dim);
                        nextFrontier.add(pos);
                    }
                }

                if (Math.abs(cell[CELL_CURRENT] - ambientTemp) < deltaThreshold) {
                    if (!isSource) {
                        if (toEvict == null)
                            toEvict = new ArrayList<>();
                        toEvict.add(pos);
                    }
                }
            }
            dirties.clear(); // We cleared the dirties block for this tick

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
        ConcurrentHashMap<Long, double[]> grid = grids.get(dim);
        if (grid == null)
            return ambientTemp;
        double[] cell = grid.get(packedPos);
        return cell != null ? cell[CELL_CURRENT] : ambientTemp;
    }

    public void addSource(ResourceLocation dim, long packedPos, double temperature) {
        sourceTemps.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .put(packedPos, temperature);

        ConcurrentHashMap<Long, double[]> grid = grids.computeIfAbsent(dim,
                k -> new ConcurrentHashMap<>());
        double[] cell = grid.computeIfAbsent(packedPos, k -> new double[] { ambientTemp, 0.0 });
        cell[CELL_CURRENT] = temperature;

        trackCellInChunk(dim, packedPos);
        positionDimensions.putIfAbsent(packedPos, dim);
        frontier.add(packedPos);

        propsCache.computeIfAbsent(dim, k -> new ConcurrentHashMap<>())
                .put(packedPos, resolveProps(dim, packedPos));
    }

    public void updateSource(ResourceLocation dim, long packedPos, double temperature) {
        addSource(dim, packedPos, temperature);
    }

    public void removeSource(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, Double> dimSources = sourceTemps.get(dim);
        if (dimSources != null) {
            dimSources.remove(packedPos);
        }

        // Reset cell to ambient so heat dissipates naturally
        ConcurrentHashMap<Long, double[]> grid = grids.get(dim);
        if (grid != null) {
            double[] cell = grid.get(packedPos);
            if (cell != null) {
                cell[CELL_CURRENT] = ambientTemp;
                cell[CELL_DELTA] = 0.0;
            }
        }

        positionDimensions.putIfAbsent(packedPos, dim);
        frontier.add(packedPos);
        dirtyCells.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(packedPos);
    }

    public void clearChunk(ResourceLocation dim, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, double[]> grid = grids.get(dim);
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
                // Also remove from frontiers so stale positions don't accumulate
                frontier.remove(pos);
                nextFrontier.remove(pos);
            }
        }
    }

    public Map<BlockPos, Double> getChunkTemperatures(ResourceLocation dim, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, double[]> grid = grids.get(dim);
        if (grid == null)
            return Collections.emptyMap();

        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        Map<BlockPos, Double> result = new HashMap<>();
        for (Map.Entry<Long, double[]> entry : grid.entrySet()) {
            long packed = entry.getKey();
            int x = BlockPos.getX(packed);
            int z = BlockPos.getZ(packed);
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                result.put(BlockPos.of(packed), entry.getValue()[CELL_CURRENT]);
            }
        }
        return result;
    }

    public void invalidateCache(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, CachedProps> dimCache = propsCache.get(dim);
        if (dimCache != null)
            dimCache.remove(packedPos);
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
        for (ConcurrentHashMap<Long, double[]> grid : grids.values()) {
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
