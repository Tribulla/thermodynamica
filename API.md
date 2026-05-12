# Thermodynamica API Documentation

> **Mod ID:** `thermodynamica`  
> **Minecraft:** 1.20.1 · **Forge:** 47.4.10+  
> **Version:** 0.4.1  
> **Package:** `com.Tribulla.thermodynamica.api`

---

## Table of Contents

- [Quick Start](#quick-start)
- [Core Concepts](#core-concepts)
  - [Celsius-Based Heat System](#celsius-based-heat-system)
  - [Heat Diffusion (BFS Engine)](#heat-diffusion-bfs-engine)
  - [Thermal Properties](#thermal-properties)
  - [Block State Awareness](#block-state-awareness)
- [API Reference](#api-reference)
  - [HeatAPI — Main Entry Point](#heatapi--main-entry-point)
  - [TemperatureChangeEvent](#temperaturechangeevent)
  - [ThermalProperties Class](#thermalproperties-class)
  - [CachedHeatEntry & ClientHeatCache](#cachedheatentry--clientheatcache)
  - [ThermodynamicaTags](#thermodynamicatags)
- [Dynamic Energy Output](#dynamic-energy-output)
  - [EnergyOutputProvider Interface](#energyoutputprovider-interface)
  - [Per-Position Overrides](#per-position-overrides)
  - [Priority Order](#priority-order)
- [Heat Targeting API](#heat-targeting-api)
  - [HeatTarget Record](#heattarget-record)
  - [HeatTargeting Utility Class](#heattargeting-utility-class)
  - [Convenience Methods on HeatAPI](#convenience-methods-on-heatapi)
  - [Active Source Index](#active-source-index)
- [Valkyrien Skies Compatibility](#valkyrien-skies-compatibility)
  - [How It Works](#how-it-works)
  - [Coordinate Transformation](#coordinate-transformation)
  - [Direction Transformation](#direction-transformation)
  - [Ship Queries](#ship-queries)
- [Datapacks & Resource Loading](#datapacks--resource-loading)
- [Configuration](#configuration)
  - [Simulation Settings](#simulation-settings)
  - [Biome Configuration](#biome-configuration)
  - [Thermal Properties Configuration](#thermal-properties-configuration)
- [In-Game Commands](#in-game-commands)
- [Adding Thermodynamica as a Dependency](#adding-thermodynamica-as-a-dependency)

---

## Quick Start

Thermodynamica is a high-performance heat simulation mod designed for deep integration. All temperatures are expressed directly in **Celsius** — there is no tier abstraction.

```java
import com.Tribulla.thermodynamica.api.*;
import net.minecraft.resources.ResourceLocation;

HeatAPI api = HeatAPI.get();

// 1. Query visual temperature at a position
double temp = api.getVisualCelsius(level, pos);

// 2. Register a block as a heat source at 500°C
api.registerBlockCelsius(new ResourceLocation("mymod:hot_block"), 500.0);

// 3. Set a dynamic energy provider for a block type
api.registerEnergyOutputProvider(new ResourceLocation("mymod:engine"), (level, p) -> {
    // Return current temperature based on your mod's state
    return OptionalDouble.of(getEngineTemp(p));
});

// 4. Listen for temperature changes
api.onTemperatureChange(event -> {
    System.out.println("Temperature changed at " + event.getPos()
            + " from " + event.getOldCelsius() + "°C to " + event.getNewCelsius() + "°C");
});

// 5. Get thermal properties of a block
ThermalProperties props = api.getThermalProperties(new ResourceLocation("minecraft:iron_block"));
double conductivity = props.getConductivity();
```

---

## Core Concepts

### Celsius-Based Heat System

Thermodynamica operates directly with Celsius temperature values. Blocks are assigned a base temperature (in °C) via configuration files or the API, and the simulation propagates heat between neighbouring blocks based on their thermal properties.

- **Ambient Temperature:** The world's base temperature (default `20.0 °C`), configurable via `ambient_temperature` in `settings.json`.
- **Biome Offsets:** Each biome can add or subtract from the ambient temperature.
- **Block Temperatures:** Individual blocks can have explicit temperatures assigned via datapacks, config files, or the runtime API.

### Heat Diffusion (BFS Engine)

Thermodynamica uses a high-performance, asynchronous **Breadth-First Search (BFS)** engine to simulate heat propagation:

- **Parallel Processing:** Uses a dedicated `ForkJoinPool` to avoid blocking the main server thread.
- **Solid Conduction:** Heat flows through solid blocks based on their thermal conductivity.
- **Air Insulation:** Air blocks act as insulators (configurable via `air_insulates`).
- **Water Transfer:** Water blocks transfer heat with a configurable multiplier (`water_transfer_multiplier`).
- **Smoothing:** Optional spatial smoothing pass for visual consistency (`smoothing_enabled`, `smoothing_radius`, `smoothing_budget`).
- **Graceful Degradation:** When enabled, the engine reduces work when performance budgets are exceeded.
- **Asynchronous Execution:** Most simulation work happens off-thread, with results synced back to the main thread.

### Thermal Properties

Every block has four properties that determine its thermal behaviour:

| Property | Default | Description |
|----------|---------|-------------|
| `conductivity` | 5.0 | Speed of heat transfer through the block. |
| `heatCapacity` | 200.0 | Resistance to temperature changes (J/K). Higher values change temperature slower. |
| `dissipationRate` | 0.08 | Rate at which heat is lost per face per tick (convection). |
| `temperature` | *(empty)* | Explicit base temperature in °C. If absent, the block uses the ambient temperature. |

### Block State Awareness

Thermodynamica automatically inspects block states to determine dynamic temperatures. The following properties are detected:

| Property | Behaviour |
|----------|-----------|
| `lit` (boolean) | If `false`, returns ambient temperature. If `true` and no explicit temperature, defaults to `500 °C`. |
| `active` / `enabled` (boolean) | Same behaviour as `lit`. Used by some mods instead of `lit`. |
| `heat_level` / `blaze` (Create's Blaze Burner) | Maps heat levels to temperature multipliers: `none` → ambient, `smouldering` → 25%, `fading` → 50%, `kindled` → 100%, `seething` → 200%. Base temperature defaults to `1000 °C` if not explicitly set. |

This means any block with a `lit` property (furnaces, campfires, torches, redstone lamps, etc.) will automatically emit heat when lit and return to ambient when unlit — no manual registration required.

---

## API Reference

### `HeatAPI` — Main Entry Point

Obtain the singleton instance via `HeatAPI.get()`. The class is an abstract class; Thermodynamica provides the implementation internally.

#### Temperature Queries

| Method | Returns | Description |
|--------|---------|-------------|
| `getVisualCelsius(Level, BlockPos)` | `double` | The best available temperature at a position. Prefers simulated values, falls back to resolved values. |
| `getSimulatedCelsius(Level, BlockPos)` | `OptionalDouble` | The current BFS-simulated temperature, if any. Empty if the position hasn't been simulated yet. |
| `getResolvedCelsius(ResourceLocation, Level, BlockPos)` | `double` | Base Celsius for a block ID, accounting for block state and biome offset. |
| `getBaseCelsiusForState(ResourceLocation, BlockState)` | `double` | Base temperature for a block ID + state, without biome offset. Applies block state detection logic. |
| `getBiomeOffset(Level, BlockPos)` | `double` | The temperature offset for the biome at a position. |
| `getAmbientTemperature()` | `double` | Returns the configured ambient temperature (default `20.0 °C`). |
| `getThermalProperties(ResourceLocation)` | `ThermalProperties` | Returns the thermal properties for a block type. Returns defaults if none are registered. |
| `getSimulatedSourcesInChunk(Level, ChunkPos)` | `Map<BlockPos, Double>` | Returns all simulated heat source temperatures in a specific chunk. |

#### Registration & Control

| Method | Description |
|--------|-------------|
| `registerBlockCelsius(ResourceLocation, double)` | Register a block type with a specific temperature in Celsius. |
| `setTemperature(Level, BlockPos, double)` | Forcefully set a temperature at a specific position (creates a simulation source). |
| `forceProcessChunks(int)` | Force the engine to process a specific number of simulation ticks immediately. |

#### Callbacks & Events

| Method | Description |
|--------|-------------|
| `onTemperatureChange(Consumer<TemperatureChangeEvent>)` | Subscribe to live temperature changes in the world. |

---

### `TemperatureChangeEvent`

Fired when a temperature changes at a position. Contains:

| Method | Returns | Description |
|--------|---------|-------------|
| `getLevel()` | `Level` | The level where the change occurred. |
| `getPos()` | `BlockPos` | The block position. |
| `getOldCelsius()` | `double` | The previous temperature. |
| `getNewCelsius()` | `double` | The new temperature. |

---

### `ThermalProperties` Class

Holds the thermal characteristics of a block type.

| Method | Returns | Description |
|--------|---------|-------------|
| `getConductivity()` | `double` | Heat transfer speed. |
| `getHeatCapacity()` | `double` | Resistance to temperature change (J/K). |
| `getDissipationRate()` | `double` | Convection loss rate per face per tick. |
| `getTemperature()` | `OptionalDouble` | Explicit base temperature, or empty for ambient. |
| `defaults()` | `ThermalProperties` | Static factory returning default values (conductivity=5.0, heatCapacity=200.0, dissipationRate=0.08). |

---

### `CachedHeatEntry` & `ClientHeatCache`

Used on the client-side to access heat data synced from the server.

**`CachedHeatEntry`** is a record with:
- `celsius()` — Temperature in Celsius.
- `renderStatePos()` — The `BlockPos` used for render state lookups.
- `worldCenter()` — The center of the source as a `Vec3` in world space.

**`ClientHeatCache`** static methods:

| Method | Returns | Description |
|--------|---------|-------------|
| `get(BlockPos)` | `CachedHeatEntry` | Returns the cached entry for a position, or `null`. |
| `getSnapshot()` | `Map<BlockPos, CachedHeatEntry>` | Thread-safe copy of all cached data. Recommended for rendering. |
| `contains(BlockPos)` | `boolean` | Checks if a position has cached data. |
| `size()` | `int` | Number of entries in the cache. |
| `clear()` | `void` | Clears all cached data. Called on disconnect or dimension change. |
| `remove(BlockPos)` | `void` | Removes a specific position from the cache. |
| `update(BlockPos, double)` | `void` | Updates cache with temperature data (called internally by network). |
| `update(BlockPos, double, BlockPos, Vec3)` | `void` | Full update including render state position and world center. |

---

### `ThermodynamicaTags`

Block tags provided by Thermodynamica:

| Tag | Description |
|-----|-------------|
| `thermodynamica:radiates_heat` | Marks a block as a heat radiator. |

---

## Dynamic Energy Output

Thermodynamica allows blocks to change their energy output dynamically at runtime, rather than being fixed to a static temperature.

### `EnergyOutputProvider` Interface

Implement this interface to provide live temperature data for your blocks:

```java
public interface EnergyOutputProvider {
    /**
     * Return OptionalDouble.of(celsius) to override output,
     * or OptionalDouble.empty() to use default values.
     */
    OptionalDouble getEnergyOutput(Level level, BlockPos pos);
}
```

Register it via:
- `api.registerEnergyOutputProvider(ResourceLocation block, EnergyOutputProvider provider)`
- `api.unregisterEnergyOutputProvider(ResourceLocation block)`

### Per-Position Overrides

For simple, one-off overrides that don't require a dedicated provider:

- `api.setBlockEnergyOutput(Level, BlockPos, double)` — Set a specific temperature for a single block instance. Immediately updates the simulation.
- `api.clearBlockEnergyOutput(Level, BlockPos)` — Clear the override and revert to provider or default values. Re-activates the block with its default temperature.

### Priority Order

When determining heat output, Thermodynamica checks:
1. **Per-position override** (Highest)
2. **Block-type provider**
3. **Static Celsius assignment / block state detection** (Lowest)

---

## Heat Targeting API

Located in `com.Tribulla.thermodynamica.api.targeting`.

### `HeatTarget` Record

Contains data about a detected heat source. Implements `Comparable<HeatTarget>` (hottest first).

| Method | Returns | Description |
|--------|---------|-------------|
| `blockPos()` | `BlockPos` | The source position. |
| `celsius()` | `double` | Source temperature. |
| `distanceSquared()` | `double` | Squared distance from the seeker. |
| `hasLineOfSight()` | `boolean` | Whether LOS is confirmed. |
| `getCenter()` | `Vec3` | Returns center of source block. |
| `getDistance()` | `double` | Actual distance (square root of `distanceSquared`). |
| `getTargetScore()` | `double` | Weighted score: `celsius / (1 + distance/10)`. |
| `withLOS(boolean)` | `HeatTarget` | Returns a copy with updated LOS status. |

### `HeatTargeting` Utility Class

Static methods for finding heat sources:

| Method | Description |
|--------|-------------|
| `getHeatSourcesInRadius(Level, Vec3, double, double)` | All sources within radius, sorted hottest first. No LOS check. |
| `getHeatSourcesWithLOS(Level, Vec3, double, double)` | All sources with confirmed line of sight, sorted hottest first. |
| `getHottestInRadius(Level, Vec3, double, double)` | Single hottest source, no LOS check. Returns `null` if none found. |
| `getHottestWithLOS(Level, Vec3, double, double)` | Hottest source with confirmed line of sight. Returns `null` if none. |
| `getBestTarget(Level, Vec3, double, double, boolean)` | Best target using temperature/distance scoring. Optional LOS. |
| `getNearestHeatSource(Level, Vec3, double, double, boolean)` | Nearest source above threshold. Optional LOS. |
| `getTargetsInCone(Level, Vec3, Vec3, double, double, double, boolean)` | Sources within a specific FOV cone. |
| `hasLineOfSight(Level, Vec3, Vec3)` | Ship-aware LOS check between two positions. |
| `getActiveHeatSources(Level, Vec3, double, double, boolean)` | Optimised lookup using the simulation's source index (much faster for large areas). |

### Convenience Methods on HeatAPI

The `HeatAPI` class also exposes targeting methods directly for convenience. These delegate to `HeatTargeting`:

```java
HeatAPI api = HeatAPI.get();

// Find the hottest source within 32 blocks, above 100°C
HeatTarget hottest = api.getHottestInRadius(level, origin, 32.0, 100.0);

// Find the hottest source with line of sight
HeatTarget visible = api.getHottestWithLOS(level, origin, 32.0, 100.0);

// Get all sources sorted by temperature
List<HeatTarget> sources = api.getHeatSourcesInRadius(level, origin, 32.0, 100.0);

// Get all sources with confirmed LOS
List<HeatTarget> visibleSources = api.getHeatSourcesWithLOS(level, origin, 32.0, 100.0);

// Best target balancing temperature and distance
HeatTarget best = api.getBestTarget(level, origin, 32.0, 100.0, true);

// Targets within a 45° cone
List<HeatTarget> cone = api.getTargetsInCone(level, origin, direction, 32.0, 45.0, 100.0, true);

// LOS check
boolean canSee = api.hasLineOfSight(level, from, to);
```

### Active Source Index

For high-performance targeting, use `HeatAPI.getActiveHeatSources(Level, double)`. It returns a map of all currently active heat sources in the dimension, bypassing the need for an expensive block scan.

```java
Map<BlockPos, Double> sources = api.getActiveHeatSources(level, 100.0);
```

---

## Valkyrien Skies Compatibility

Thermodynamica features native support for Valkyrien Skies 2 via reflection (no compile-time dependency required).

Located in `com.Tribulla.thermodynamica.api.compat.ValkyrienSkiesCompat`.

### How It Works

Heat simulation runs in the ship's **native coordinate space** (shipyard). However, targeting and visual calculations often require **world space** transformations. All methods gracefully fall back to identity transformations when VS is not installed.

### Coordinate Transformation

```java
import com.Tribulla.thermodynamica.api.compat.ValkyrienSkiesCompat;

// Check if VS is installed
if (ValkyrienSkiesCompat.isVSInstalled()) { ... }

// Check if a position is on a ship
if (ValkyrienSkiesCompat.isOnShip(level, pos)) {
    // Convert ship-local BlockPos to world space BlockPos
    BlockPos worldPos = ValkyrienSkiesCompat.toWorldPos(level, pos);

    // Convert ship-local Vec3 to world space Vec3
    Vec3 worldVec = ValkyrienSkiesCompat.toWorldCoordinates(level, pos, localVec);

    // Convert world Vec3 to ship-local Vec3
    Vec3 shipVec = ValkyrienSkiesCompat.toShipCoordinates(level, pos, worldVec);
}

// Use a known ship object for batch transformations
Object ship = ValkyrienSkiesCompat.getShipManagingPos(level, pos);
Vec3 worldVec = ValkyrienSkiesCompat.toWorldCoordinatesWithShip(ship, localVec);
Vec3 shipVec = ValkyrienSkiesCompat.toShipCoordinatesWithShip(ship, worldVec);
```

### Direction Transformation

Transform direction vectors between ship-local and world space:

```java
// Ship-local direction → world direction
Vec3 worldDir = ValkyrienSkiesCompat.transformDirectionToWorld(level, shipBlockPos, localDirection);

// World direction → ship-local direction
Vec3 shipDir = ValkyrienSkiesCompat.transformDirectionToShip(level, shipBlockPos, worldDirection);
```

### Ship Queries

| Method | Returns | Description |
|--------|---------|-------------|
| `isVSInstalled()` | `boolean` | Whether VS is available. |
| `isOnShip(Level, BlockPos)` | `boolean` | Whether a position is on a ship. |
| `getShipManagingPos(Level, BlockPos)` | `Object` | The ship managing a position (or `null`). |
| `getShipVelocity(Level, BlockPos)` | `Vec3` | The ship's velocity vector (or `Vec3.ZERO`). |
| `getShipWorldPosition(Level, BlockPos)` | `Vec3` | The ship's center of mass in world space (or `null`). |
| `getAllLoadedShips(Level)` | `Iterable<Object>` | All loaded ships in a level. |
| `isBlockInShipyard(Level, BlockPos)` | `boolean` | Whether a position is within the VS shipyard. |

---

## Datapacks & Resource Loading

Thermodynamica supports datapack-based thermal property definitions via the `ThermalPropertyResourceLoader`. This integrates with Minecraft's standard resource reload system, allowing modpacks and datapacks to define or override thermal properties for any block.

Thermal property files placed under the appropriate datapack path will be loaded during resource reloads (e.g. `/reload` command, server start).

---

## Configuration

Configuration is located in `config/thermodynamica/`.

### Simulation Settings

**File:** `config/thermodynamica/settings.json`

| Key | Default | Description |
|-----|---------|-------------|
| `worker_threads` | 2 | CPU threads for simulation. |
| `work_budget_per_tick` | 50000 | Max blocks to process per tick. |
| `graceful_degradation` | true | Reduce work when performance budgets are exceeded. |
| `simulation_interval_ticks` | 10 | Ticks between simulation steps (10 = 0.5s). |
| `delta_threshold` | 0.5 | Minimum temperature change to propagate. |
| `air_insulates` | true | Whether air blocks prevent heat transfer. |
| `water_transfer_multiplier` | 2.0 | Heat transfer multiplier through water blocks. |
| `dissipation_multiplier` | 1.0 | Global multiplier for dissipation rates. |
| `time_budget_ms_per_tick` | 200.0 | Time limit for the engine per tick (ms). |
| `smoothing_enabled` | true | Enable spatial temperature smoothing. |
| `smoothing_radius` | 2 | Radius for temperature smoothing pass. |
| `smoothing_budget` | 500 | Max blocks to smooth per tick. |
| `sync_threshold` | 20.0 | Temperature change required to sync to client (°C). |
| `sync_range` | 64 | Radius for syncing data to players (blocks). |
| `debug_mode` | false | Enable additional debug logging. |
| `max_propagation_radius` | 16 | Maximum distance heat travels from a source (blocks). |
| `ticks_per_radius_step` | 2 | Simulation ticks per radius expansion step. |
| `temperature_ramp_rate` | 0.35 | Speed at which blocks approach target temperature (0.01–1.0). |
| `ambient_temperature` | 20.0 | Default ambient temperature in Celsius. |

### Biome Configuration

**File:** `config/thermodynamica/biome_config.json`

Biome temperature offsets modify the ambient temperature based on biome. The system supports:

- **Category Offsets:** Broad groups (e.g. `cold: -30.0`, `temperate: 0.0`, `hot: 15.0`).
- **Biome Category Assignments:** Map individual biomes to categories.
- **Biome Overrides:** Per-biome Celsius offsets that override category assignments.
- **Fallback:** Unassigned biomes use Minecraft's vanilla `baseTemperature` to guess a category.

### Thermal Properties Configuration

**File:** `config/thermodynamica/thermal/block_properties.json`

Defines per-block thermal properties and temperatures:

```json
{
  "blocks": {
    "minecraft:magma_block": {
      "conductivity": 8.0,
      "heat_capacity": 300.0,
      "dissipation_rate": 0.05,
      "temperature": 800.0
    },
    "minecraft:iron_block": {
      "conductivity": 10.0,
      "heat_capacity": 500.0,
      "dissipation_rate": 0.02
    }
  },
  "tags": {}
}
```

Blocks without an entry use the default thermal properties (conductivity=5.0, heatCapacity=200.0, dissipationRate=0.08).

---

## In-Game Commands

All commands require permission level 2 (operator). Available under both `/thermodynamica` and `/td`.

| Command | Description |
|---------|-------------|
| `/td status` | Engine status: running state, loaded chunks, heat source count, propagating sources, chunks pending scan. |
| `/td tps` | Performance statistics: simulation TPS, last/average tick time, sources advanced, total ticks, total blocks processed. |
| `/td debug` | Debug info about the nearest heat source to the player (requires player context). |
| `/td reset` | Resets performance monitoring counters. |

---

## Adding Thermodynamica as a Dependency

In your `build.gradle`:

```groovy
repositories {
    maven { url "https://maven.pkg.github.com/Tribulla/thermodynamica" }
}

dependencies {
    compileOnly fg.deobf("com.github.thermodynamica:thermodynamica:0.4.1")
}
```

In your `mods.toml`:

```toml
[[dependencies.yourmodid]]
    modId="thermodynamica"
    mandatory=false
    versionRange="[0.4.1,)"
    ordering="AFTER"
    side="BOTH"
```
