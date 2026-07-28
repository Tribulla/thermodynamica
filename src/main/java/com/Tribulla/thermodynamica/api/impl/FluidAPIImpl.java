package com.Tribulla.thermodynamica.api.impl;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.FluidAPI;
import com.Tribulla.thermodynamica.api.PressureChangeEvent;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.simulation.FluidSimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class FluidAPIImpl extends FluidAPI {

    private final HeatConfigManager configManager;
    private final CopyOnWriteArrayList<Consumer<PressureChangeEvent>> pressureChangeListeners = new CopyOnWriteArrayList<>();
    private volatile FluidSimulationManager simulationManager;

    public FluidAPIImpl(HeatConfigManager configManager) {
        this.configManager = configManager;
    }

    public void setSimulationManager(FluidSimulationManager simulationManager) {
        this.simulationManager = simulationManager;
    }

    @Override
    public boolean isFluidSimulationEnabled() {
        return configManager.getSettings().isFluidSimulationEnabled();
    }

    @Override
    public OptionalDouble getAirTemperature(Level level, BlockPos pos) {
        if (simulationManager == null || !isFluidSimulationEnabled())
            return OptionalDouble.empty();
        return simulationManager.getAirTemperature(level.dimension().location(), pos.asLong());
    }

    @Override
    public OptionalDouble getAirPressure(Level level, BlockPos pos) {
        if (simulationManager == null || !isFluidSimulationEnabled())
            return OptionalDouble.empty();
        return simulationManager.getAirPressure(level.dimension().location(), pos.asLong());
    }

    @Override
    public Vec3 getAirVelocity(Level level, BlockPos pos) {
        if (simulationManager == null || !isFluidSimulationEnabled())
            return Vec3.ZERO;
        return simulationManager.getAirVelocity(level.dimension().location(), pos.asLong());
    }

    @Override
    public void onPressureChange(Consumer<PressureChangeEvent> listener) {
        pressureChangeListeners.add(listener);
    }

    public void firePressureChange(PressureChangeEvent event) {
        for (Consumer<PressureChangeEvent> listener : pressureChangeListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                Thermodynamica.LOGGER.error("Error in pressure change listener", e);
            }
        }
    }
}
