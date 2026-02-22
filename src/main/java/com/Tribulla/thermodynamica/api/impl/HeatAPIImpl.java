package com.Tribulla.thermodynamica.api.impl;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.*;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class HeatAPIImpl extends HeatAPI {

    private final HeatConfigManager configManager;
    private final TierRegistry tierRegistry;
    private final List<Consumer<TierChangeEvent>> tierChangeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TemperatureChangeEvent>> tempChangeListeners = new CopyOnWriteArrayList<>();
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
    public double getSimulatedCelsius(Level level, BlockPos pos) {
        if (simulationManager != null) {
            return simulationManager.getTemperature(level, pos);
        }
        ResourceLocation blockId = level.getBlockState(pos).getBlock().builtInRegistryHolder().key().location();
        return getResolvedCelsius(blockId, level, pos);
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
}
