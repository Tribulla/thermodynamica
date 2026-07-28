package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalDouble;
import java.util.function.Consumer;

public abstract class FluidAPI {
    private static FluidAPI INSTANCE;

    public static FluidAPI get() {
        if (INSTANCE == null)
            throw new IllegalStateException("Thermodynamica fluid API has not been initialized yet");
        return INSTANCE;
    }

    public static void setInstance(FluidAPI instance) {
        INSTANCE = instance;
    }

    public abstract boolean isFluidSimulationEnabled();

    public abstract OptionalDouble getAirTemperature(Level level, BlockPos pos);

    public abstract OptionalDouble getAirPressure(Level level, BlockPos pos);

    public abstract Vec3 getAirVelocity(Level level, BlockPos pos);

    public abstract void onPressureChange(Consumer<PressureChangeEvent> listener);
}
