package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.function.Consumer;

public abstract class HeatAPI {
    private static HeatAPI INSTANCE;

    public static HeatAPI get() {
        if (INSTANCE == null)
            throw new IllegalStateException("Thermodynamica has not been initialized yet");
        return INSTANCE;
    }

    public static void setInstance(HeatAPI instance) {
        INSTANCE = instance;
    }

    public abstract HeatTier getResolvedTier(ResourceLocation block);

    public abstract double getResolvedCelsius(ResourceLocation block, Level level, BlockPos pos);

    public abstract double getSimulatedCelsius(Level level, BlockPos pos);

    public abstract HeatTier getAmbientTier();

    public abstract void registerBlockTier(ResourceLocation block, HeatTier tier);

    public abstract void registerBlockCelsius(ResourceLocation block, double celsius);

    @Nullable
    public abstract TierResolution resolveBlockTier(ResourceLocation block);

    public abstract void onTierChange(Consumer<TierChangeEvent> listener);

    public abstract void onTemperatureChange(Consumer<TemperatureChangeEvent> listener);

    public abstract boolean isInTier(ResourceLocation block, HeatTier tier);

    public abstract ThermalProperties getThermalProperties(ResourceLocation block);

    public abstract double getTierCelsius(HeatTier tier);

    public abstract double getBiomeOffset(Level level, BlockPos pos);
}
