package com.Tribulla.thermodynamica.simulation;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.PressureChangeEvent;
import com.Tribulla.thermodynamica.api.impl.FluidAPIImpl;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.config.SimulationSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

public class AirFluidEngine {

    public record FluidCellData(double celsius, double pressure, Vec3 velocity) {
    }

    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private static final int WORLD_MIN_Y = -64;
    private static final int WORLD_MAX_Y = 319;
    private static final int PRESSURE_ITERATIONS = 10;
    private static final int BATCH_SIZE = 256;
    private static final int NEIGHBOR_SOLID = -1;
    private static final int NEIGHBOR_OPEN = -2;
    private static final double DT = 1.0;
    private static final double DX = 1.0;

    static final class AirCell {
        double temperature;
        double pressure;
        double u; // +X face
        double v; // +Y face
        double w; // +Z face

        AirCell(double temperature, double pressure) {
            this.temperature = temperature;
            this.pressure = pressure;
        }
    }

    static final class BlockDelta {
        double deltaTemperature;

        synchronized void add(double delta) {
            deltaTemperature += delta;
        }

        synchronized double drain() {
            double result = deltaTemperature;
            deltaTemperature = 0.0;
            return result;
        }
    }

    private final MinecraftServer server;
    private final SimulationSettings settings;
    private final FluidAPIImpl fluidApi;

    private FluidSimulationManager manager;

    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, AirCell>> grids = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, Set<Long>>> chunkCellMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceLocation, Set<Long>> activeCells = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ResourceLocation> positionDimensions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, BlockDelta>> pendingSolidHeat = new ConcurrentHashMap<>();

    private ForkJoinPool pool;

    private double ambientTemp;
    private double baselinePressure;
    private double airHeatCapacity;
    private double airConductivity;
    private double buoyancyStrength;
    private double heatAdvectionStrength;
    private double pressureDeltaThreshold;
    private int simulationIntervalTicks;
    private int workBudget;
    private int gameTicksSinceLastStep = 0;
    private int activeScanCursor = 0;

    private volatile long lastTickNanos;
    private volatile int lastCellsProcessed;
    private volatile int lastChangedCells;
    private final AtomicLong totalTickNanos = new AtomicLong();
    private final AtomicLong totalTicks = new AtomicLong();
    private final AtomicLong totalCellsProcessed = new AtomicLong();

    public AirFluidEngine(MinecraftServer server, HeatConfigManager configManager, FluidAPIImpl fluidApi) {
        this.server = server;
        this.settings = configManager.getSettings();
        this.fluidApi = fluidApi;
        refreshConfig();
    }

    public void setManager(FluidSimulationManager manager) {
        this.manager = manager;
    }

    public void refreshConfig() {
        ambientTemp = settings.getAmbientTemperature();
        baselinePressure = settings.getAirBaselinePressure();
        airHeatCapacity = settings.getAirHeatCapacity();
        airConductivity = settings.getAirConductivity();
        buoyancyStrength = settings.getBuoyancyStrength();
        heatAdvectionStrength = settings.getHeatAdvectionStrength();
        pressureDeltaThreshold = settings.getPressureDeltaThreshold();
        simulationIntervalTicks = settings.getFluidSimulationIntervalTicks();
        workBudget = settings.getFluidWorkBudgetPerTick();
    }

    public void start() {
        int threads = Math.max(1, settings.getWorkerThreads());
        pool = new ForkJoinPool(threads,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t, e) -> Thermodynamica.LOGGER.error("Air fluid worker error", e),
                true);
        Thermodynamica.LOGGER.info(
                "Air fluid engine started (Bridson MAC + red-black Gauss–Seidel) with {} worker threads",
                threads);
    }

    public void stopProcessing() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        Thermodynamica.LOGGER.info("Air fluid engine processing stopped");
    }

    public void stop() {
        stopProcessing();
        grids.clear();
        chunkCellMap.clear();
        activeCells.clear();
        positionDimensions.clear();
        pendingSolidHeat.clear();
        gameTicksSinceLastStep = 0;
        activeScanCursor = 0;
    }

    public void tick() {
        long start = System.nanoTime();
        if (totalActiveCellCount() == 0) {
            recordStats(start, 0, 0);
            return;
        }

        gameTicksSinceLastStep++;
        if (gameTicksSinceLastStep < simulationIntervalTicks) {
            recordStats(start, 0, 0);
            return;
        }
        gameTicksSinceLastStep = 0;

        int processed = 0;
        int changed = 0;
        for (Map.Entry<ResourceLocation, Set<Long>> dimEntry : activeCells.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            ServerLevel level = getLevelForDim(dim);
            if (level == null)
                continue;

            ConcurrentHashMap<Long, AirCell> grid = grids.get(dim);
            if (grid == null || grid.isEmpty())
                continue;

            List<Long> active = new ArrayList<>(dimEntry.getValue());
            active.sort(Long::compareUnsigned);
            if (active.size() > workBudget) {
                int startIdx = activeScanCursor % active.size();
                List<Long> window = new ArrayList<>(workBudget);
                for (int i = 0; i < workBudget; i++) {
                    window.add(active.get((startIdx + i) % active.size()));
                }
                activeScanCursor = (startIdx + workBudget) % active.size();
                active = window;
            } else {
                activeScanCursor = 0;
            }
            processed += active.size();

            List<Long> fluidCells = new ArrayList<>(active.size());
            Set<Long> fluidSet = ConcurrentHashMap.newKeySet();
            for (long packed : active) {
                BlockPos pos = BlockPos.of(packed);
                if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
                    evictCell(packed);
                    continue;
                }
                if (grid.containsKey(packed)) {
                    fluidCells.add(packed);
                    fluidSet.add(packed);
                }
            }

            expandFront(dim, level, grid, fluidCells);
            for (long packed : dimEntry.getValue()) {
                if (fluidCells.size() >= workBudget)
                    break;
                if (!fluidSet.add(packed))
                    continue;
                if (!grid.containsKey(packed))
                    continue;
                BlockPos pos = BlockPos.of(packed);
                if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
                    fluidSet.remove(packed);
                    continue;
                }
                fluidCells.add(packed);
            }

            applyBuoyancy(dim, level, grid, fluidCells);
            enforceSolidWalls(level, grid, fluidCells);
            changed += projectPressure(level, grid, fluidCells);
            transportTemperatureFlux(level, grid, fluidCells);
            diffuseAirTemperature(level, grid, fluidCells);
            exchangeWithSolids(dim, level, grid, fluidCells);
            applyDrag(grid, fluidCells);
            cullSettled(dim, grid, fluidCells);
        }

        flushSolidHeatDeltas();
        recordStats(start, processed, changed);
    }

    private void applyBuoyancy(ResourceLocation dim, ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;

            double dT = cell.temperature - ambientTemp;
            if (Math.abs(dT) < 0.05)
                continue;

            double buoyancy = buoyancyStrength * (dT / 25.0) * DT;
            BlockPos pos = BlockPos.of(packed);
            BlockPos above = pos.above();
            boolean blockedAbove = !level.isLoaded(above) || !level.getBlockState(above).isAir();

            if (blockedAbove) {
                cell.v = 0.0;
                if (dT <= 0.5)
                    continue;

                double push = Math.abs(buoyancy) * 1.25;
                int open = 0;
                boolean east = isOpenAir(level, pos.east());
                boolean west = isOpenAir(level, pos.west());
                boolean south = isOpenAir(level, pos.south());
                boolean north = isOpenAir(level, pos.north());
                if (east) open++;
                if (west) open++;
                if (south) open++;
                if (north) open++;
                if (open == 0)
                    continue;

                double each = push / open;
                if (east) {
                    wakeAir(dim, pos.east().asLong());
                    cell.u += each;
                }
                if (west) {
                    wakeAir(dim, pos.west().asLong());
                    AirCell westCell = grid.get(pos.west().asLong());
                    if (westCell != null)
                        westCell.u -= each;
                }
                if (south) {
                    wakeAir(dim, pos.south().asLong());
                    cell.w += each;
                }
                if (north) {
                    wakeAir(dim, pos.north().asLong());
                    AirCell northCell = grid.get(pos.north().asLong());
                    if (northCell != null)
                        northCell.w -= each;
                }
                cell.u = clamp(cell.u, -1.5, 1.5);
                cell.w = clamp(cell.w, -1.5, 1.5);
            } else {
                cell.v += buoyancy;
                cell.v = clamp(cell.v, -1.5, 1.5);
            }
        }
    }

    private static boolean isOpenAir(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).isAir();
    }

    private void diffuseAirTemperature(ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        if (cells.isEmpty())
            return;

        final double diff = Math.min(airConductivity * 0.35, 0.12);
        if (diff <= 0.0)
            return;

        Map<Long, Double> next = new HashMap<>(cells.size() * 2);
        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;
            BlockPos pos = BlockPos.of(packed);
            double sum = 0.0;
            int count = 0;
            for (int[] offset : NEIGHBORS) {
                int ny = pos.getY() + offset[1];
                if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y)
                    continue;
                BlockPos nPos = new BlockPos(pos.getX() + offset[0], ny, pos.getZ() + offset[2]);
                if (!isOpenAir(level, nPos))
                    continue;
                AirCell neighbor = grid.get(nPos.asLong());
                if (neighbor == null)
                    continue;
                sum += neighbor.temperature - cell.temperature;
                count++;
            }
            if (count > 0)
                next.put(packed, cell.temperature + diff * (sum / count));
        }
        for (Map.Entry<Long, Double> entry : next.entrySet()) {
            AirCell cell = grid.get(entry.getKey());
            if (cell != null)
                cell.temperature = entry.getValue();
        }
    }

    private void transportTemperatureFlux(ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        if (heatAdvectionStrength <= 0.0 || cells.isEmpty())
            return;

        Map<Long, Double> delta = new HashMap<>(cells.size() * 2);
        double strength = Math.min(Math.max(heatAdvectionStrength, 0.15), 0.35);

        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;
            BlockPos pos = BlockPos.of(packed);

            fluxAcrossFace(level, grid, delta, packed, cell, pos.east().asLong(), cell.u, strength);
            fluxAcrossFace(level, grid, delta, packed, cell, pos.above().asLong(), cell.v, strength);
            fluxAcrossFace(level, grid, delta, packed, cell, pos.south().asLong(), cell.w, strength);
        }

        for (Map.Entry<Long, Double> entry : delta.entrySet()) {
            AirCell cell = grid.get(entry.getKey());
            if (cell != null)
                cell.temperature += entry.getValue();
        }
    }

    private void fluxAcrossFace(ServerLevel level, ConcurrentHashMap<Long, AirCell> grid,
            Map<Long, Double> delta, long packed, AirCell cell, long neighborPacked,
            double faceVelocity, double strength) {
        if (Math.abs(faceVelocity) < 1e-5)
            return;
        BlockPos nPos = BlockPos.of(neighborPacked);
        if (!level.isLoaded(nPos) || !level.getBlockState(nPos).isAir())
            return;
        AirCell neighbor = grid.get(neighborPacked);
        if (neighbor == null)
            return;

        double frac = clamp(Math.abs(faceVelocity) * DT * strength, 0.0, 0.25);
        if (faceVelocity > 0.0) {
            double transfer = frac * (cell.temperature - neighbor.temperature);
            delta.merge(packed, -transfer, Double::sum);
            delta.merge(neighborPacked, transfer, Double::sum);
        } else {
            double transfer = frac * (neighbor.temperature - cell.temperature);
            delta.merge(neighborPacked, -transfer, Double::sum);
            delta.merge(packed, transfer, Double::sum);
        }
    }

    private void applyDrag(ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;
            cell.u *= 0.97;
            cell.v *= 0.995;
            cell.w *= 0.97;
            if (Math.abs(cell.u) < 1e-4) cell.u = 0.0;
            if (Math.abs(cell.v) < 1e-4) cell.v = 0.0;
            if (Math.abs(cell.w) < 1e-4) cell.w = 0.0;
        }
    }

    private void advectTemperature(ResourceLocation dim, ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        Map<Long, Double> nextTemp = new HashMap<>(cells.size() * 2);
        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;

            Vec3 vel = sampleCellVelocity(grid, packed);
            double speed = vel.length();
            if (speed < 1e-6 || heatAdvectionStrength <= 0.0) {
                nextTemp.put(packed, cell.temperature);
                continue;
            }

            BlockPos pos = BlockPos.of(packed);
            double backX = pos.getX() + 0.5 - vel.x * DT * heatAdvectionStrength;
            double backY = pos.getY() + 0.5 - vel.y * DT * heatAdvectionStrength;
            double backZ = pos.getZ() + 0.5 - vel.z * DT * heatAdvectionStrength;
            nextTemp.put(packed, sampleTemperatureTrilinear(dim, level, grid, backX, backY, backZ));
        }

        for (Map.Entry<Long, Double> entry : nextTemp.entrySet()) {
            AirCell cell = grid.get(entry.getKey());
            if (cell != null)
                cell.temperature = entry.getValue();
        }
    }

    private void exchangeWithSolids(ResourceLocation dim, ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        if (manager == null || airConductivity <= 0.0)
            return;

        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;
            BlockPos pos = BlockPos.of(packed);
            double net = 0.0;
            for (int[] offset : NEIGHBORS) {
                int ny = pos.getY() + offset[1];
                if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y)
                    continue;
                BlockPos nPos = new BlockPos(pos.getX() + offset[0], ny, pos.getZ() + offset[2]);
                if (!level.isLoaded(nPos))
                    continue;
                BlockState state = level.getBlockState(nPos);
                if (state.isAir())
                    continue;

                double solidTemp = manager.sampleSolidTemperature(dim, nPos.asLong());
                double dT = cell.temperature - solidTemp;
                if (Math.abs(dT) < 0.01)
                    continue;

                double rate = dT < 0.0
                        ? airConductivity * 1.5   // solid hotter → air gains heat
                        : airConductivity * 0.15; // air hotter → slow loss
                double heat = rate * dT;
                net -= heat;
                queueSolidHeatDelta(dim, nPos.asLong(), heat / Math.max(airHeatCapacity, 1.0));
            }
            if (Math.abs(net) > 1e-9) {
                cell.temperature += net / Math.max(airHeatCapacity, 0.01);
            }
        }
    }

    private void enforceSolidWalls(ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;
            BlockPos pos = BlockPos.of(packed);

            if (!level.isLoaded(pos.east()) || !level.getBlockState(pos.east()).isAir())
                cell.u = 0.0;
            if (!level.isLoaded(pos.above()) || !level.getBlockState(pos.above()).isAir())
                cell.v = 0.0;
            if (!level.isLoaded(pos.south()) || !level.getBlockState(pos.south()).isAir())
                cell.w = 0.0;
        }
    }

    private int projectPressure(ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        if (cells.isEmpty())
            return 0;

        final int n = cells.size();
        final long[] packed = new long[n];
        final AirCell[] cellArr = new AirCell[n];
        final double[] oldPressure = new double[n];
        final double[] pressure = new double[n];
        final double[] divergence = new double[n];
        final int[] neighbors = new int[n * 6];
        final boolean[] faceOpen = new boolean[n * 3];

        Map<Long, Integer> indexOf = new HashMap<>(n * 2);
        int write = 0;
        for (long p : cells) {
            AirCell cell = grid.get(p);
            if (cell == null)
                continue;
            indexOf.put(p, write);
            packed[write] = p;
            cellArr[write] = cell;
            oldPressure[write] = cell.pressure;
            pressure[write] = cell.pressure;
            write++;
        }
        final int count = write;
        if (count == 0)
            return 0;

        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.of(packed[i]);
            for (int f = 0; f < 6; f++) {
                int[] offset = NEIGHBORS[f];
                int ny = pos.getY() + offset[1];
                int slot = i * 6 + f;
                if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y) {
                    neighbors[slot] = NEIGHBOR_SOLID;
                    continue;
                }
                BlockPos nPos = new BlockPos(pos.getX() + offset[0], ny, pos.getZ() + offset[2]);
                if (!level.isLoaded(nPos) || !level.getBlockState(nPos).isAir()) {
                    neighbors[slot] = NEIGHBOR_SOLID;
                    continue;
                }
                Integer idx = indexOf.get(nPos.asLong());
                neighbors[slot] = idx != null ? idx : NEIGHBOR_OPEN;
            }
            faceOpen[i * 3] = neighbors[i * 6] >= 0 || neighbors[i * 6] == NEIGHBOR_OPEN;
            faceOpen[i * 3 + 1] = neighbors[i * 6 + 2] >= 0 || neighbors[i * 6 + 2] == NEIGHBOR_OPEN;
            faceOpen[i * 3 + 2] = neighbors[i * 6 + 4] >= 0 || neighbors[i * 6 + 4] == NEIGHBOR_OPEN;
        }

        int[] red = new int[count];
        int[] black = new int[count];
        int redCount = 0;
        int blackCount = 0;
        for (int i = 0; i < count; i++) {
            int x = BlockPos.getX(packed[i]);
            int y = BlockPos.getY(packed[i]);
            int z = BlockPos.getZ(packed[i]);
            if (((x + y + z) & 1) == 0) {
                red[redCount++] = i;
            } else {
                black[blackCount++] = i;
            }
        }
        final int[] redIdx = trim(red, redCount);
        final int[] blackIdx = trim(black, blackCount);

        parallelFor(0, count, i -> divergence[i] = computeDivergenceIndexed(cellArr, neighbors, faceOpen, i));

        final double scale = (DX * DX) / DT;
        for (int iter = 0; iter < PRESSURE_ITERATIONS; iter++) {
            parallelForIndices(redIdx, i -> gaussSeidelUpdate(pressure, divergence, neighbors, i, scale));
            parallelForIndices(blackIdx, i -> gaussSeidelUpdate(pressure, divergence, neighbors, i, scale));
        }

        parallelFor(0, count, i -> pressure[i] = pressure[i] * 0.98 + baselinePressure * 0.02);

        AtomicInteger changed = new AtomicInteger();
        List<PressureChangeEvent> events = Collections.synchronizedList(new ArrayList<>());

        parallelFor(0, count, i -> {
            AirCell cell = cellArr[i];
            double p = pressure[i];
            double oldP = oldPressure[i];
            cell.pressure = p;

            BlockPos pos = BlockPos.of(packed[i]);
            if (faceOpen[i * 3]) {
                int nIdx = neighbors[i * 6]; // +X
                double pRight = nIdx >= 0 ? pressure[nIdx] : baselinePressure;
                cell.u -= DT * (pRight - p) / (DX * baselinePressure);
                cell.u = clamp(cell.u, -1.0, 1.0);
            } else {
                cell.u = 0.0;
            }
            if (faceOpen[i * 3 + 1]) {
                int nIdx = neighbors[i * 6 + 2]; // +Y
                double pUp = nIdx >= 0 ? pressure[nIdx] : baselinePressure;
                cell.v -= DT * (pUp - p) / (DX * baselinePressure);
                cell.v = clamp(cell.v, -1.0, 1.0);
            } else {
                cell.v = 0.0;
            }
            if (faceOpen[i * 3 + 2]) {
                int nIdx = neighbors[i * 6 + 4]; // +Z
                double pFwd = nIdx >= 0 ? pressure[nIdx] : baselinePressure;
                cell.w -= DT * (pFwd - p) / (DX * baselinePressure);
                cell.w = clamp(cell.w, -1.0, 1.0);
            } else {
                cell.w = 0.0;
            }

            if (Math.abs(p - oldP) > 0.01 || Math.abs(cell.temperature - ambientTemp) > 0.05) {
                changed.incrementAndGet();
            }
            if (Math.abs(p - oldP) > pressureDeltaThreshold) {
                events.add(new PressureChangeEvent(level, pos, oldP, p, sampleCellVelocity(grid, packed[i])));
            }
        });

        for (PressureChangeEvent event : events) {
            fluidApi.firePressureChange(event);
        }
        return changed.get();
    }

    private void gaussSeidelUpdate(double[] pressure, double[] divergence, int[] neighbors,
            int i, double scale) {
        double sum = 0.0;
        int count = 0;
        int base = i * 6;
        for (int f = 0; f < 6; f++) {
            int n = neighbors[base + f];
            if (n == NEIGHBOR_SOLID) {
                sum += pressure[i];
            } else if (n == NEIGHBOR_OPEN) {
                sum += baselinePressure;
            } else {
                sum += pressure[n];
            }
            count++;
        }
        if (count == 0)
            return;
        pressure[i] = (sum - divergence[i] * scale) / count;
    }

    private double computeDivergenceIndexed(AirCell[] cellArr, int[] neighbors, boolean[] faceOpen, int i) {
        AirCell cell = cellArr[i];
        double uRight = faceOpen[i * 3] ? cell.u : 0.0;
        double vUp = faceOpen[i * 3 + 1] ? cell.v : 0.0;
        double wFwd = faceOpen[i * 3 + 2] ? cell.w : 0.0;

        int leftIdx = neighbors[i * 6 + 1];
        int downIdx = neighbors[i * 6 + 3];
        int backIdx = neighbors[i * 6 + 5];
        double uLeft = leftIdx >= 0 ? cellArr[leftIdx].u : 0.0;
        double vDown = downIdx >= 0 ? cellArr[downIdx].v : 0.0;
        double wBack = backIdx >= 0 ? cellArr[backIdx].w : 0.0;
        return (uRight - uLeft + vUp - vDown + wFwd - wBack) / DX;
    }

    private void parallelFor(int fromInclusive, int toExclusive, IntConsumer consumer) {
        int size = toExclusive - fromInclusive;
        if (size <= 0)
            return;
        if (pool == null || pool.isShutdown() || size < BATCH_SIZE) {
            for (int i = fromInclusive; i < toExclusive; i++) {
                consumer.accept(i);
            }
            return;
        }
        List<Future<?>> futures = new ArrayList<>((size + BATCH_SIZE - 1) / BATCH_SIZE);
        for (int start = fromInclusive; start < toExclusive; start += BATCH_SIZE) {
            final int from = start;
            final int to = Math.min(start + BATCH_SIZE, toExclusive);
            futures.add(pool.submit(() -> {
                for (int i = from; i < to; i++) {
                    consumer.accept(i);
                }
            }));
        }
        awaitAll(futures);
    }

    private void parallelForIndices(int[] indices, IntConsumer consumer) {
        if (indices.length == 0)
            return;
        if (pool == null || pool.isShutdown() || indices.length < BATCH_SIZE) {
            for (int idx : indices) {
                consumer.accept(idx);
            }
            return;
        }
        List<Future<?>> futures = new ArrayList<>((indices.length + BATCH_SIZE - 1) / BATCH_SIZE);
        for (int start = 0; start < indices.length; start += BATCH_SIZE) {
            final int from = start;
            final int to = Math.min(start + BATCH_SIZE, indices.length);
            futures.add(pool.submit(() -> {
                for (int i = from; i < to; i++) {
                    consumer.accept(indices[i]);
                }
            }));
        }
        awaitAll(futures);
    }

    private static void awaitAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                throw new RuntimeException("Air fluid parallel task failed", e);
            }
        }
    }

    private static int[] trim(int[] src, int length) {
        if (length == src.length)
            return src;
        int[] out = new int[length];
        System.arraycopy(src, 0, out, 0, length);
        return out;
    }

    private Vec3 sampleCellVelocity(ConcurrentHashMap<Long, AirCell> grid, long packed) {
        AirCell cell = grid.get(packed);
        if (cell == null)
            return Vec3.ZERO;
        BlockPos pos = BlockPos.of(packed);
        AirCell left = grid.get(pos.west().asLong());
        AirCell down = grid.get(pos.below().asLong());
        AirCell back = grid.get(pos.north().asLong());
        double ux = 0.5 * (cell.u + (left != null ? left.u : 0.0));
        double uy = 0.5 * (cell.v + (down != null ? down.v : 0.0));
        double uz = 0.5 * (cell.w + (back != null ? back.w : 0.0));
        return new Vec3(ux, uy, uz);
    }

    private double sampleTemperatureTrilinear(ResourceLocation dim, ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, double x, double y, double z) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fy = y - y0;
        double fz = z - z0;

        double c000 = sampleTemperature(dim, level, grid, x0, y0, z0);
        double c100 = sampleTemperature(dim, level, grid, x0 + 1, y0, z0);
        double c010 = sampleTemperature(dim, level, grid, x0, y0 + 1, z0);
        double c110 = sampleTemperature(dim, level, grid, x0 + 1, y0 + 1, z0);
        double c001 = sampleTemperature(dim, level, grid, x0, y0, z0 + 1);
        double c101 = sampleTemperature(dim, level, grid, x0 + 1, y0, z0 + 1);
        double c011 = sampleTemperature(dim, level, grid, x0, y0 + 1, z0 + 1);
        double c111 = sampleTemperature(dim, level, grid, x0 + 1, y0 + 1, z0 + 1);

        double c00 = lerp(c000, c100, fx);
        double c10 = lerp(c010, c110, fx);
        double c01 = lerp(c001, c101, fx);
        double c11 = lerp(c011, c111, fx);
        double c0 = lerp(c00, c10, fy);
        double c1 = lerp(c01, c11, fy);
        return lerp(c0, c1, fz);
    }

    private double sampleTemperature(ResourceLocation dim, ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, int x, int y, int z) {
        if (y < WORLD_MIN_Y || y > WORLD_MAX_Y)
            return ambientTemp;
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
            if (manager != null && level.isLoaded(pos) && !level.getBlockState(pos).isAir()) {
                return manager.sampleSolidTemperature(dim, pos.asLong());
            }
            return ambientTemp;
        }
        AirCell cell = grid.get(pos.asLong());
        return cell != null ? cell.temperature : ambientTemp;
    }

    private void cullSettled(ResourceLocation dim, ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;
            Vec3 vel = sampleCellVelocity(grid, packed);
            boolean settled = Math.abs(cell.temperature - ambientTemp) < 0.75
                    && Math.abs(cell.pressure - baselinePressure) < pressureDeltaThreshold
                    && vel.lengthSqr() < 0.0004;
            if (settled) {
                evictCell(packed);
            }
        }
    }

    private void expandFront(ResourceLocation dim, ServerLevel level,
            ConcurrentHashMap<Long, AirCell> grid, List<Long> cells) {
        for (long packed : cells) {
            AirCell cell = grid.get(packed);
            if (cell == null)
                continue;
            double dT = cell.temperature - ambientTemp;
            Vec3 vel = sampleCellVelocity(grid, packed);
            if (Math.abs(dT) < 0.5
                    && Math.abs(cell.pressure - baselinePressure) < pressureDeltaThreshold * 2.0
                    && vel.lengthSqr() < 0.01) {
                continue;
            }

            BlockPos pos = BlockPos.of(packed);
            boolean blockedAbove = !level.isLoaded(pos.above()) || !level.getBlockState(pos.above()).isAir();

            if (cell.u > 0.01)
                wakeAir(dim, pos.east().asLong());
            if (cell.u < -0.01)
                wakeAir(dim, pos.west().asLong());
            if (cell.v > 0.01 || (dT > 0.5 && !blockedAbove))
                wakeAir(dim, pos.above().asLong());
            if (cell.v < -0.01 || dT < -0.5)
                wakeAir(dim, pos.below().asLong());
            if (cell.w > 0.01)
                wakeAir(dim, pos.south().asLong());
            if (cell.w < -0.01)
                wakeAir(dim, pos.north().asLong());

            if ((blockedAbove && dT > 0.5) || Math.abs(dT) > 1.5) {
                wakeAir(dim, pos.east().asLong());
                wakeAir(dim, pos.west().asLong());
                wakeAir(dim, pos.south().asLong());
                wakeAir(dim, pos.north().asLong());
            }
        }
    }

    private void wakeAir(ResourceLocation dim, long packedPos) {
        ServerLevel level = getLevelForDim(dim);
        if (level == null)
            return;
        BlockPos pos = BlockPos.of(packedPos);
        int y = pos.getY();
        if (y < WORLD_MIN_Y || y > WORLD_MAX_Y)
            return;
        if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir())
            return;

        ConcurrentHashMap<Long, AirCell> grid = grids.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
        if (grid.containsKey(packedPos)) {
            activeCells.computeIfAbsent(dim, d -> ConcurrentHashMap.newKeySet()).add(packedPos);
            return;
        }
        activateAirCell(dim, packedPos, ambientTemp, baselinePressure);
    }

    private void wakeNeighbors(ResourceLocation dim, long packedPos) {
        activeCells.computeIfAbsent(dim, d -> ConcurrentHashMap.newKeySet()).add(packedPos);
        int bx = BlockPos.getX(packedPos);
        int by = BlockPos.getY(packedPos);
        int bz = BlockPos.getZ(packedPos);
        for (int[] offset : NEIGHBORS) {
            int ny = by + offset[1];
            if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y)
                continue;
            wakeAir(dim, BlockPos.asLong(bx + offset[0], ny, bz + offset[2]));
        }
    }

    public void activateAirAround(ResourceLocation dim, long solidPackedPos, double sourceTemperature) {
        int bx = BlockPos.getX(solidPackedPos);
        int by = BlockPos.getY(solidPackedPos);
        int bz = BlockPos.getZ(solidPackedPos);
        double seededTemp = ambientTemp + (sourceTemperature - ambientTemp) * 0.65;
        for (int[] offset : NEIGHBORS) {
            int ny = by + offset[1];
            if (ny < WORLD_MIN_Y || ny > WORLD_MAX_Y)
                continue;
            long neighborPacked = BlockPos.asLong(bx + offset[0], ny, bz + offset[2]);
            activateAirCell(dim, neighborPacked, seededTemp, baselinePressure);
        }
    }

    public void activateAirCell(ResourceLocation dim, long packedPos, double initialTemp, double initialPressure) {
        ServerLevel level = getLevelForDim(dim);
        if (level == null)
            return;
        BlockPos pos = BlockPos.of(packedPos);
        if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir())
            return;

        ConcurrentHashMap<Long, AirCell> grid = grids.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
        AirCell existing = grid.get(packedPos);
        if (existing == null) {
            grid.put(packedPos, new AirCell(initialTemp, initialPressure));
        } else if (initialTemp > existing.temperature) {
            existing.temperature += (initialTemp - existing.temperature) * 0.35;
            existing.pressure += (initialPressure - existing.pressure) * 0.1;
        }
        positionDimensions.putIfAbsent(packedPos, dim);
        trackCellInChunk(dim, packedPos);
        activeCells.computeIfAbsent(dim, d -> ConcurrentHashMap.newKeySet()).add(packedPos);
    }

    public void clearChunk(ResourceLocation dim, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, AirCell> grid = grids.get(dim);
        ConcurrentHashMap<Long, Set<Long>> chunkCells = chunkCellMap.get(dim);
        Set<Long> dimActive = activeCells.get(dim);
        if (grid == null || chunkCells == null)
            return;

        Set<Long> cells = chunkCells.remove(ChunkPos.asLong(chunkX, chunkZ));
        if (cells == null)
            return;
        for (long pos : cells) {
            grid.remove(pos);
            if (dimActive != null)
                dimActive.remove(pos);
            positionDimensions.remove(pos);
        }
    }

    public void onBlockChanged(ResourceLocation dim, long packedPos) {
        ServerLevel level = getLevelForDim(dim);
        if (level == null)
            return;
        BlockPos pos = BlockPos.of(packedPos);
        if (!level.isLoaded(pos))
            return;
        if (level.getBlockState(pos).isAir()) {
            activateAirCell(dim, packedPos, ambientTemp, baselinePressure);
        } else {
            evictCell(packedPos);
            activateAirAround(dim, packedPos,
                    manager != null ? manager.sampleSolidTemperature(dim, packedPos) : ambientTemp);
        }
    }

    private void evictCell(long packedPos) {
        ResourceLocation dim = positionDimensions.remove(packedPos);
        if (dim == null)
            return;
        ConcurrentHashMap<Long, AirCell> grid = grids.get(dim);
        if (grid != null)
            grid.remove(packedPos);
        Set<Long> dimActive = activeCells.get(dim);
        if (dimActive != null)
            dimActive.remove(packedPos);
        long chunkPos = ChunkPos.asLong(BlockPos.getX(packedPos) >> 4, BlockPos.getZ(packedPos) >> 4);
        ConcurrentHashMap<Long, Set<Long>> chunks = chunkCellMap.get(dim);
        if (chunks != null) {
            Set<Long> cells = chunks.get(chunkPos);
            if (cells != null)
                cells.remove(packedPos);
        }
    }

    private void trackCellInChunk(ResourceLocation dim, long pos) {
        long chunkPos = ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
        chunkCellMap.computeIfAbsent(dim, d -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkPos, c -> ConcurrentHashMap.newKeySet())
                .add(pos);
    }

    private void queueSolidHeatDelta(ResourceLocation dim, long packedPos, double deltaTemp) {
        pendingSolidHeat.computeIfAbsent(dim, d -> new ConcurrentHashMap<>())
                .computeIfAbsent(packedPos, p -> new BlockDelta())
                .add(deltaTemp);
    }

    private void flushSolidHeatDeltas() {
        if (manager == null)
            return;
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, BlockDelta>> dimEntry : pendingSolidHeat.entrySet()) {
            ResourceLocation dim = dimEntry.getKey();
            for (Map.Entry<Long, BlockDelta> blockEntry : dimEntry.getValue().entrySet()) {
                double delta = blockEntry.getValue().drain();
                if (Math.abs(delta) > 0.0001) {
                    manager.applySolidTemperatureDelta(dim, blockEntry.getKey(), delta);
                }
            }
            dimEntry.getValue().clear();
        }
    }

    public OptionalDouble getAirTemperature(ResourceLocation dim, long packedPos) {
        AirCell cell = getCell(dim, packedPos);
        return cell != null ? OptionalDouble.of(cell.temperature) : OptionalDouble.empty();
    }

    public OptionalDouble getAirPressure(ResourceLocation dim, long packedPos) {
        AirCell cell = getCell(dim, packedPos);
        return cell != null ? OptionalDouble.of(cell.pressure) : OptionalDouble.empty();
    }

    public Vec3 getAirVelocity(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, AirCell> grid = grids.get(dim);
        if (grid == null)
            return Vec3.ZERO;
        return sampleCellVelocity(grid, packedPos);
    }

    private AirCell getCell(ResourceLocation dim, long packedPos) {
        ConcurrentHashMap<Long, AirCell> grid = grids.get(dim);
        return grid != null ? grid.get(packedPos) : null;
    }

    public Map<BlockPos, FluidCellData> getChunkFluidData(ResourceLocation dim, int chunkX, int chunkZ) {
        ConcurrentHashMap<Long, Set<Long>> dimChunks = chunkCellMap.get(dim);
        ConcurrentHashMap<Long, AirCell> grid = grids.get(dim);
        if (dimChunks == null || grid == null)
            return Collections.emptyMap();
        Set<Long> cells = dimChunks.get(ChunkPos.asLong(chunkX, chunkZ));
        if (cells == null || cells.isEmpty())
            return Collections.emptyMap();

        Map<BlockPos, FluidCellData> result = new HashMap<>();
        for (long pos : cells) {
            AirCell cell = grid.get(pos);
            if (cell != null) {
                result.put(BlockPos.of(pos), new FluidCellData(cell.temperature, cell.pressure,
                        sampleCellVelocity(grid, pos)));
            }
        }
        return result;
    }

    public void saveToNBT(CompoundTag tag) {
        CompoundTag dimsTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, ConcurrentHashMap<Long, AirCell>> dimEntry : grids.entrySet()) {
            ListTag cellList = new ListTag();
            for (Map.Entry<Long, AirCell> entry : dimEntry.getValue().entrySet()) {
                AirCell cell = entry.getValue();
                CompoundTag cellTag = new CompoundTag();
                cellTag.putLong("Pos", entry.getKey());
                cellTag.putDouble("Temp", cell.temperature);
                cellTag.putDouble("Pressure", cell.pressure);
                cellTag.putDouble("U", cell.u);
                cellTag.putDouble("V", cell.v);
                cellTag.putDouble("W", cell.w);
                cellList.add(cellTag);
            }
            dimsTag.put(dimEntry.getKey().toString(), cellList);
        }
        tag.put("FluidAir", dimsTag);
    }

    public void loadFromNBT(CompoundTag tag) {
        if (!tag.contains("FluidAir"))
            return;
        CompoundTag dimsTag = tag.getCompound("FluidAir");
        for (String dimKey : dimsTag.getAllKeys()) {
            ResourceLocation dim = ResourceLocation.tryParse(dimKey);
            if (dim == null)
                continue;
            ListTag list = dimsTag.getList(dimKey, 10);
            ConcurrentHashMap<Long, AirCell> grid = grids.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag cellTag = list.getCompound(i);
                long pos = cellTag.getLong("Pos");
                AirCell cell = new AirCell(cellTag.getDouble("Temp"), cellTag.getDouble("Pressure"));
                if (cellTag.contains("U")) {
                    cell.u = cellTag.getDouble("U");
                    cell.v = cellTag.getDouble("V");
                    cell.w = cellTag.getDouble("W");
                } else {
                    cell.u = cellTag.getDouble("Vx");
                    cell.v = cellTag.getDouble("Vy");
                    cell.w = cellTag.getDouble("Vz");
                }
                grid.put(pos, cell);
                positionDimensions.put(pos, dim);
                trackCellInChunk(dim, pos);
                activeCells.computeIfAbsent(dim, d -> ConcurrentHashMap.newKeySet()).add(pos);
            }
        }
    }

    private int totalActiveCellCount() {
        int total = 0;
        for (Set<Long> cells : activeCells.values()) {
            total += cells.size();
        }
        return total;
    }

    private void recordStats(long startNanos, int processed, int changed) {
        long elapsed = System.nanoTime() - startNanos;
        lastTickNanos = elapsed;
        lastCellsProcessed = processed;
        lastChangedCells = changed;
        totalTickNanos.addAndGet(elapsed);
        totalTicks.incrementAndGet();
        totalCellsProcessed.addAndGet(processed);
    }

    public double getLastTickMs() {
        return lastTickNanos / 1_000_000.0;
    }

    public int getLastCellsProcessed() {
        return lastCellsProcessed;
    }

    public int getLastChangedCells() {
        return lastChangedCells;
    }

    public long getTotalTicks() {
        return totalTicks.get();
    }

    public long getTotalCellsProcessed() {
        return totalCellsProcessed.get();
    }

    public double getAverageTickMs() {
        long count = totalTicks.get();
        if (count == 0)
            return 0.0;
        return (totalTickNanos.get() / (double) count) / 1_000_000.0;
    }

    public int getGridSize() {
        int total = 0;
        for (ConcurrentHashMap<Long, AirCell> grid : grids.values()) {
            total += grid.size();
        }
        return total;
    }

    public int getActiveCellCount() {
        return totalActiveCellCount();
    }

    public void forceProcessChunks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            gameTicksSinceLastStep = simulationIntervalTicks;
            tick();
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private ServerLevel getLevelForDim(ResourceLocation dim) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dim))
                return level;
        }
        return null;
    }
}
