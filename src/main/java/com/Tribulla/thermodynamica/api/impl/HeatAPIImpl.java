package com.Tribulla.thermodynamica.api.impl;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.*;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import com.Tribulla.thermodynamica.simulation.GlobalBlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class HeatAPIImpl extends HeatAPI {

    private final HeatConfigManager configManager;
    private final TierRegistry tierRegistry;
    private final List<Consumer<TierChangeEvent>> tierChangeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TemperatureChangeEvent>> tempChangeListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<ResourceLocation, EnergyOutputProvider> energyOutputProviders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<GlobalBlockPos, Double> perPositionOverrides = new ConcurrentHashMap<>();
    private volatile HeatSimulationManager simulationManager;

    public HeatAPIImpl(HeatConfigManager configManager) {
        this.configManager = configManager;
        this.tierRegistry = new TierRegistry(configManager);
    }

    public void setSimulationManager(HeatSimulationManager manager) {
        this.simulationManager = manager;
    }

    public TierRegistry getTierRegistry() {
        return tierRegistry;
    }

    @Override
    public HeatTier getResolvedTier(ResourceLocation block) {
        TierResolution resolution = tierRegistry.resolve(block);
        return resolution != null ? resolution.getTier() : getAmbientTier();
    }

    @Override
    public double getResolvedCelsius(ResourceLocation block, Level level, BlockPos pos) {
        HeatTier tier = getResolvedTier(block);
        double baseCelsius = configManager.getTierDefinitions().getCelsius(tier);
        double biomeOffset = getBiomeOffset(level, pos);
        return baseCelsius + biomeOffset;
    }

    @Override
    public OptionalDouble getSimulatedCelsius(Level level, BlockPos pos) {
        if (simulationManager != null) {
            return simulationManager.getExactTemperature(level, pos);
        }
        return OptionalDouble.empty();
    }

    @Override
    public void setTemperature(Level level, BlockPos pos, double celsius) {
        if (simulationManager != null) {
            simulationManager.setTemperature(level, pos, celsius);
        }
    }

    @Override
    public double getVisualCelsius(Level level, BlockPos pos) {
        OptionalDouble simHeat = getSimulatedCelsius(level, pos);
        if (simHeat.isPresent()) {
            return simHeat.getAsDouble();
        }
        ResourceLocation blockId = level.getBlockState(pos).getBlock().builtInRegistryHolder().key().location();
        return getResolvedCelsius(blockId, level, pos);
    }

    @Override
    public Map<BlockPos, Double> getSimulatedSourcesInChunk(Level level, ChunkPos pos) {
        if (simulationManager != null) {
            return simulationManager.getChunkTemperatures(level.dimension().location(), pos.x, pos.z);
        }
        return Collections.emptyMap();
    }

    @Override
    public void forceProcessChunks(int ticks) {
        if (simulationManager != null) {
            simulationManager.forceProcessChunks(ticks);
        }
    }

    @Override
    public HeatTier getAmbientTier() {
        return configManager.getSettings().getAmbientTier();
    }

    @Override
    public void registerBlockTier(ResourceLocation block, HeatTier tier) {
        HeatTier oldTier = getResolvedTier(block);
        tierRegistry.registerRuntime(block, tier);
        HeatTier newTier = getResolvedTier(block);
        if (oldTier != newTier) {
            fireTierChange(new TierChangeEvent(block, oldTier, newTier));
        }
    }

    @Override
    public void registerBlockCelsius(ResourceLocation block, double celsius) {
        double[] tierCelsius = configManager.getTierDefinitions().getAllCelsius();
        HeatTier nearest = HeatTier.nearestTier(celsius, tierCelsius);
        registerBlockTier(block, nearest);
    }

    @Override
    @Nullable
    public TierResolution resolveBlockTier(ResourceLocation block) {
        return tierRegistry.resolve(block);
    }

    @Override
    public void onTierChange(Consumer<TierChangeEvent> listener) {
        tierChangeListeners.add(listener);
    }

    @Override
    public void onTemperatureChange(Consumer<TemperatureChangeEvent> listener) {
        tempChangeListeners.add(listener);
    }

    public void fireTierChange(TierChangeEvent event) {
        for (Consumer<TierChangeEvent> listener : tierChangeListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                Thermodynamica.LOGGER.error("Error in tier change listener", e);
            }
        }
    }

    public void fireTemperatureChange(TemperatureChangeEvent event) {
        for (Consumer<TemperatureChangeEvent> listener : tempChangeListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                Thermodynamica.LOGGER.error("Error in temperature change listener", e);
            }
        }
    }

    @Override
    public boolean isInTier(ResourceLocation block, HeatTier tier) {
        return getResolvedTier(block) == tier;
    }

    @Override
    public ThermalProperties getThermalProperties(ResourceLocation block) {
        ThermalProperties props = configManager.getThermalPropertiesRegistry().get(block);
        return props != null ? props : ThermalProperties.defaults();
    }

    @Override
    public double getTierCelsius(HeatTier tier) {
        return configManager.getTierDefinitions().getCelsius(tier);
    }

    @Override
    public double getBiomeOffset(Level level, BlockPos pos) {
        Holder<Biome> biomeHolder = level.getBiome(pos);
        return configManager.getBiomeConfig().getOffset(biomeHolder);
    }

    @Override
    public Map<BlockPos, Double> getActiveHeatSources(Level level, double minCelsius) {
        if (simulationManager != null) {
            return simulationManager.getActiveHeatSources(level.dimension().location(), minCelsius);
        }
        return Collections.emptyMap();
    }

    @Override
    public void registerEnergyOutputProvider(ResourceLocation block, EnergyOutputProvider provider) {
        energyOutputProviders.put(block, provider);
        Thermodynamica.LOGGER.debug("Registered energy output provider for {}", block);
    }

    @Override
    public void unregisterEnergyOutputProvider(ResourceLocation block) {
        energyOutputProviders.remove(block);
        Thermodynamica.LOGGER.debug("Unregistered energy output provider for {}", block);
    }

    @Override
    public void setBlockEnergyOutput(Level level, BlockPos pos, double celsius) {
        ResourceLocation dim = level.dimension().location();
        GlobalBlockPos key = new GlobalBlockPos(dim, pos.asLong());
        perPositionOverrides.put(key, celsius);

        // Immediately update the simulation source so the change takes effect right away
        if (simulationManager != null) {
            simulationManager.setTemperature(level, pos, celsius);
        }
    }

    @Override
    public void clearBlockEnergyOutput(Level level, BlockPos pos) {
        ResourceLocation dim = level.dimension().location();
        GlobalBlockPos key = new GlobalBlockPos(dim, pos.asLong());
        perPositionOverrides.remove(key);

        // Re-activate with default tier temperature
        if (simulationManager != null) {
            simulationManager.markActive(level, pos);
        }
    }

    @Nullable
    public EnergyOutputProvider getEnergyOutputProvider(ResourceLocation block) {
        return energyOutputProviders.get(block);
    }

    @Nullable
    public Double getPerPositionOverride(ResourceLocation dim, long packedPos) {
        return perPositionOverrides.get(new GlobalBlockPos(dim, packedPos));
    }

    public Map<ResourceLocation, EnergyOutputProvider> getEnergyOutputProviders() {
        return Collections.unmodifiableMap(energyOutputProviders);
    }
}
