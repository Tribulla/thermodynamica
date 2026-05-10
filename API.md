# Thermodynamica API Documentation

> **Mod ID:** `thermodynamica`  
> **Minecraft:** 1.20.1 · **Forge:** 47.4.10+  
> **Version:** 0.3.1  
> **Package:** `com.Tribulla.thermodynamica.api`

---

## Table of Contents

- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
  - [Heat Tiers](#heat-tiers)
  - [Heat Diffusion (BFS Engine)](#heat-diffusion-bfs-engine)
  - [Thermal Properties](#thermal-properties)
- [API Reference](#api-reference)
  - [HeatAPI — Main Entry Point](#heatapi--main-entry-point)
  - [HeatTier Enum](#heattier-enum)
  - [CachedHeatEntry & ClientHeatCache](#cachedheatentry--clientheatcache)
- [Dynamic Energy Output](#dynamic-energy-output)
  - [EnergyOutputProvider Interface](#energyoutputprovider-interface)
  - [Per-Position Overrides](#per-position-overrides)
  - [Priority Order](#priority-order)
- [Heat Targeting API](#heat-targeting-api)
  - [HeatTarget Record](#heattarget-record)
  - [HeatTargeting Utility Class](#heattargeting-utility-class)
  - [Active Source Index](#active-source-index)
- [Valkyrien Skies Compatibility](#valkyrien-skies-compatibility)
  - [How It Works](#how-it-works)
  - [Coordinate Transformation](#coordinate-transformation)
- [Configuration](#configuration)
- [In-Game Commands](#in-game-commands)
- [Adding as a Dependency](#adding-thermodynamica-as-a-dependency)

---

## Quick Start

Thermodynamica is a high-performance heat simulation mod designed for deep integration.

```java
import com.Tribulla.thermodynamica.api.*;
import net.minecraft.resources.ResourceLocation;

HeatAPI api = HeatAPI.get();

// 1. Query visual temperature at a position
double temp = api.getVisualCelsius(level, pos);

// 2. Register a block as a heat source
api.registerBlockTier(new ResourceLocation("mymod:hot_block"), HeatTier.POS4);

// 3. Set a dynamic energy provider for a block type
api.registerEnergyOutputProvider(new ResourceLocation("mymod:engine"), (level, p) -> {
    // Return current temperature based on your mod's state
    return OptionalDouble.of(getEngineTemp(p));
});

// 4. Listen for temperature changes
api.onTemperatureChange(event -> {
    System.out.println("Temperature changed at " + event.getPos() + " to " + event.getNewCelsius() + "°C");
});
```

---

## Core Concepts

### Heat Tiers

Blocks are assigned to one of **11 discrete heat tiers**. Each tier maps to a nominal Celsius value.

| Tier | ID | Index | Default °C | Description |
|------|------|-------|-----------|-------------|
| `NEG5` | `neg5` | −5 | −200 | Extreme cold |
| `NEG4` | `neg4` | −4 | −150 | Very cold |
| `NEG3` | `neg3` | −3 | −100 | Cold |
| `NEG2` | `neg2` | −2 | −50 | Cool |
| `NEG1` | `neg1` | −1 | −20 | Chilly |
| `ZERO` | `zero` | 0 | 0 | Freezing |
| **`POS1`** | `pos1` | 1 | **20** | **Ambient (default)** |
| `POS2` | `pos2` | 2 | 100 | Warm |
| `POS3` | `pos3` | 3 | 500 | Hot |
| `POS4` | `pos4` | 4 | 1000 | Very hot |
| `POS5` | `pos5` | 5 | 3000 | Extreme heat |

### Heat Diffusion (BFS Engine)

Thermodynamica uses a high-performance, asynchronous **Breadth-First Search (BFS)** engine to simulate heat propagation:

- **Parallel Processing:** Uses a dedicated `ForkJoinPool` to avoid blocking the main server thread.
- **Solid Conduction:** Heat flows through solid blocks based on their thermal conductivity.
- **Air Insulation:** Air blocks act as insulators (configurable).
- **Fluid Handling:** Water and lava are treated as insulators for the simulation for performance stability.
- **Asynchronous Execution:** Most simulation work happens off-thread, with results synced back to the main thread.

### Thermal Properties

Every block has three properties that determine its thermal behavior:

| Property | Default | Description |
|----------|---------|-------------|
| `conductivity` | 1.0 | Speed of heat transfer through the block. |
| `heatCapacity` | 1000.0 | Resistance to temperature changes. Higher values change temperature slower. |
| `dissipationRate` | 0.05 | Rate at which heat is lost to the environment (convection). |

---

## API Reference

### `HeatAPI` — Main Entry Point

Obtain the singleton instance via `HeatAPI.get()`.

#### Temperature Queries

| Method | Returns | Description |
|--------|---------|-------------|
| `getVisualCelsius(Level, BlockPos)` | `double` | The best available temperature at a position. |
| `getSimulatedCelsius(Level, BlockPos)` | `OptionalDouble` | The current BFS-simulated temperature, if any. |
| `getResolvedCelsius(ResourceLocation, Level, BlockPos)` | `double` | Static tier temperature for a block ID + biome offset. |
| `getResolvedTier(ResourceLocation)` | `HeatTier` | The heat tier a block type resolves to. |
| `getTierCelsius(HeatTier)` | `double` | The nominal temperature of a specific tier. |
| `getBiomeOffset(Level, BlockPos)` | `double` | The temperature offset for the biome at a position. |
| `getAmbientTier()` | `HeatTier` | Returns the mod's configured ambient tier (usually `POS1`). |

#### Registration & Control

| Method | Description |
|--------|-------------|
| `registerBlockTier(ResourceLocation, HeatTier)` | Manually assign a block to a heat tier. |
| `registerBlockCelsius(ResourceLocation, double)` | Map a block to the nearest heat tier based on Celsius. |
| `setTemperature(Level, BlockPos, double)` | Forcefully set a temperature at a position (creates a source). |
| `forceProcessChunks(int)` | Force the engine to process a specific number of simulation ticks immediately. |

#### Callbacks & Events

| Method | Description |
|--------|-------------|
| `onTemperatureChange(Consumer<TemperatureChangeEvent>)` | Subscribe to live temperature changes in the world. |
| `onTierChange(Consumer<TierChangeEvent>)` | Subscribe to changes in block tier assignments (e.g. on config reload). |

---

### `HeatTier` Enum

Represents the 11 discrete levels of heat.

- `getIndex()`: Returns index from -5 to 5.
- `getId()`: Returns the string ID (e.g., `"pos3"`).
- `fromId(String)`: Static lookup by ID.
- `fromIndex(int)`: Static lookup by index.
- `nearestTier(double, double[])`: Static helper to find the closest tier to a Celsius value.

---

### `CachedHeatEntry` & `ClientHeatCache`

Used on the client-side to access heat data synced from the server.

- `ClientHeatCache.get(BlockPos)`: Returns the `CachedHeatEntry` for a position.
- `ClientHeatCache.getSnapshot()`: Returns a thread-safe map of all cached data.
- `entry.celsius()`: Temperature in Celsius.
- `entry.tierOrdinal()`: Ordinal of the tier (-1 if none).

---

## Dynamic Energy Output

Thermodynamica allows blocks to change their energy output dynamically at runtime, rather than being stuck at a fixed tier temperature.

### `EnergyOutputProvider` Interface

Implement this functional interface to provide live temperature data for your blocks:

```java
@FunctionalInterface
public interface EnergyOutputProvider {
    /**
     * Return OptionalDouble.of(celsius) to override output, 
     * or OptionalDouble.empty() to use default tier values.
     */
    OptionalDouble getEnergyOutput(Level level, BlockPos pos);
}
```

Register it via:
- `api.registerEnergyOutputProvider(ResourceLocation block, EnergyOutputProvider provider)`
- `api.unregisterEnergyOutputProvider(ResourceLocation block)`

### Per-Position Overrides

For simple, one-off overrides that don't require a dedicated provider:

- `api.setBlockEnergyOutput(Level, BlockPos, double)`: Set a specific temperature for a single block instance.
- `api.clearBlockEnergyOutput(Level, BlockPos)`: Clear the override and revert to provider or tier defaults.

### Priority Order

When determining heat output, Thermodynamica checks:
1. **Per-position override** (Highest)
2. **Block-type provider**
3. **Static tier assignment** (Lowest)

---

## Heat Targeting API

Located in `com.Tribulla.thermodynamica.api.targeting`.

### `HeatTarget` Record

Contains data about a detected heat source:
- `blockPos()`: The source position.
- `celsius()`: Source temperature.
- `distanceSquared()`: Distance from the seeker.
- `hasLineOfSight()`: Whether LOS is confirmed.
- `getCenter()`: Returns center of source as `Vec3`.
- `getTargetScore()`: Calculates a weighted score: `celsius / (1 + distance/10)`.

### `HeatTargeting` Utility Class

Static methods for finding heat sources:

| Method | Description |
|--------|-------------|
| `getHeatSourcesInRadius(Level, Vec3, double, double)` | List of sources sorted hottest first. |
| `getHottestWithLOS(Level, Vec3, double, double)` | Hottest source with confirmed line of sight. |
| `getBestTarget(Level, Vec3, double, double, boolean)` | Hottest/closest balanced target using scoring. |
| `getTargetsInCone(Level, Vec3, Vec3, double, double, double, boolean)` | Sources within a specific FOV cone. |
| `hasLineOfSight(Level, Vec3, Vec3)` | Advanced LOS check (ship-aware). |

### Active Source Index

For high-performance targeting, use `HeatAPI.getActiveHeatSources(Level, double)`. It returns a map of all currently active heat sources in the dimension, bypassing the need for an expensive block scan.

---

## Valkyrien Skies Compatibility

Thermodynamica features native support for Valkyrien Skies 2.

### How It Works

Heat simulation runs in the ship's **native coordinate space** (shipyard). However, targeting and visual calculations often require **world space** transformations.

### Coordinate Transformation

Use `ValkyrienSkiesCompat` for all coordinate math:

```java
import com.Tribulla.thermodynamica.api.compat.ValkyrienSkiesCompat;

// Check if a position is on a ship
if (ValkyrienSkiesCompat.isOnShip(level, pos)) {
    // Convert ship-local BlockPos to world space BlockPos
    BlockPos worldPos = ValkyrienSkiesCompat.toWorldPos(level, pos);
    
    // Convert ship-local Vec3 to world space Vec3
    Vec3 worldVec = ValkyrienSkiesCompat.toWorldCoordinates(level, pos, localVec);
}
```

---

## Configuration

Configuration is located in `config/Thermodynamica/settings.json`.

| Key | Default | Description |
|-----|---------|-------------|
| `worker_threads` | 2 | CPU threads for simulation. |
| `work_budget_per_tick` | 50000 | Max blocks to process per tick. |
| `simulation_interval_ticks` | 20 | Ticks between simulation steps (20 = 1s). |
| `delta_threshold` | 0.5 | Minimum temperature change to propagate. |
| `air_insulates` | true | Whether air blocks prevent heat transfer. |
| `time_budget_ms_per_tick` | 200.0 | Time limit for the engine per tick. |
| `sync_threshold` | 20.0 | Temperature change required to sync to client. |
| `sync_range` | 64 | Radius for syncing data to players. |
| `max_propagation_radius` | 16 | Maximum distance heat travels from a source. |
| `temperature_ramp_rate` | 0.15 | Speed at which blocks approach target temperature. |
| `ambient_tier` | `"pos1"` | Default ambient heat tier. |

---

## In-Game Commands

- `/td status`: View engine status, source count, and memory usage.
- `/td tps`: Detailed performance statistics for the heat simulation.
- `/td debug`: Provides detailed information about the nearest heat source.
- `/td reset`: Resets performance monitoring counters.

---

## Adding Thermodynamica as a Dependency

In your `build.gradle`:

```groovy
repositories {
    maven { url "https://maven.pkg.github.com/Tribulla/thermodynamica" }
}

dependencies {
    compileOnly fg.deobf("com.github.thermodynamica:thermodynamica:0.3.1")
}
```

In your `mods.toml`:

```toml
[[dependencies.yourmodid]]
    modId="thermodynamica"
    mandatory=false
    versionRange="[0.3.1,)"
    ordering="AFTER"
    side="BOTH"
```
