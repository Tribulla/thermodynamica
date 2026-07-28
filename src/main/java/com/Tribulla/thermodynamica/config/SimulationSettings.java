package com.Tribulla.thermodynamica.config;

import com.google.gson.JsonObject;

public class SimulationSettings implements ConfigSection {

    private int workerThreads = 2;
    private int workBudgetPerTick = 50000;
    private boolean gracefulDegradation = true;
    private int simulationIntervalTicks = 10;
    private double deltaThreshold = 0.5;
    private boolean airInsulates = true;
    private double waterTransferMultiplier = 2.0;
    private double dissipationMultiplier = 1.0;
    private double timeBudgetMsPerTick = 200.0;
    private int smoothingRadius = 2;
    private int smoothingBudget = 500;
    private boolean smoothingEnabled = true;
    private double syncThreshold = 20.0;
    private int syncRange = 64;
    private boolean debugMode = false;
    private int maxPropagationRadius = 16;
    private int ticksPerRadiusStep = 2;
    private double temperatureRampRate = 0.35;
    private double ambientTemperature = 20.0;
    private boolean fluidSimulationEnabled = false;
    private int fluidSimulationIntervalTicks = 4;
    private int fluidWorkBudgetPerTick = 20000;
    private double fluidTimeBudgetMsPerTick = 100.0;
    private double airBaselinePressure = 101325.0;
    private double airHeatCapacity = 20.0;
    private double airConductivity = 0.15;
    private double pressureEqualizationRate = 0.08;
    private double buoyancyStrength = 0.02;
    private double heatAdvectionStrength = 0.25;
    private double pressureDeltaThreshold = 5.0;
    private int fluidSyncRange = 48;
    private double fluidDebugSyncThreshold = 0.5;

    @Override
    public void load(JsonObject json) {
        if (json.has("worker_threads"))
            workerThreads = json.get("worker_threads").getAsInt();
        if (json.has("work_budget_per_tick"))
            workBudgetPerTick = json.get("work_budget_per_tick").getAsInt();
        if (json.has("graceful_degradation"))
            gracefulDegradation = json.get("graceful_degradation").getAsBoolean();
        if (json.has("simulation_interval_ticks"))
            simulationIntervalTicks = json.get("simulation_interval_ticks").getAsInt();
        if (json.has("delta_threshold"))
            deltaThreshold = json.get("delta_threshold").getAsDouble();
        if (json.has("air_insulates"))
            airInsulates = json.get("air_insulates").getAsBoolean();
        if (json.has("water_transfer_multiplier"))
            waterTransferMultiplier = json.get("water_transfer_multiplier").getAsDouble();
        if (json.has("dissipation_multiplier"))
            dissipationMultiplier = json.get("dissipation_multiplier").getAsDouble();
        if (json.has("time_budget_ms_per_tick"))
            timeBudgetMsPerTick = Math.max(0.1, json.get("time_budget_ms_per_tick").getAsDouble());
        if (json.has("smoothing_radius"))
            smoothingRadius = json.get("smoothing_radius").getAsInt();
        if (json.has("smoothing_budget"))
            smoothingBudget = json.get("smoothing_budget").getAsInt();
        if (json.has("smoothing_enabled"))
            smoothingEnabled = json.get("smoothing_enabled").getAsBoolean();
        if (json.has("sync_threshold"))
            syncThreshold = json.get("sync_threshold").getAsDouble();
        if (json.has("sync_range"))
            syncRange = json.get("sync_range").getAsInt();
        if (json.has("debug_mode"))
            debugMode = json.get("debug_mode").getAsBoolean();
        if (json.has("max_propagation_radius"))
            maxPropagationRadius = json.get("max_propagation_radius").getAsInt();
        if (json.has("ticks_per_radius_step"))
            ticksPerRadiusStep = Math.max(1, json.get("ticks_per_radius_step").getAsInt());
        if (json.has("temperature_ramp_rate"))
            temperatureRampRate = Math.max(0.01, Math.min(1.0, json.get("temperature_ramp_rate").getAsDouble()));
        if (json.has("ambient_temperature"))
            ambientTemperature = json.get("ambient_temperature").getAsDouble();
        if (json.has("fluid_simulation_enabled"))
            fluidSimulationEnabled = json.get("fluid_simulation_enabled").getAsBoolean();
        if (json.has("fluid_simulation_interval_ticks"))
            fluidSimulationIntervalTicks = Math.max(1, json.get("fluid_simulation_interval_ticks").getAsInt());
        if (json.has("fluid_work_budget_per_tick"))
            fluidWorkBudgetPerTick = Math.max(100, json.get("fluid_work_budget_per_tick").getAsInt());
        if (json.has("fluid_time_budget_ms_per_tick"))
            fluidTimeBudgetMsPerTick = Math.max(0.1, json.get("fluid_time_budget_ms_per_tick").getAsDouble());
        if (json.has("air_baseline_pressure"))
            airBaselinePressure = Math.max(1.0, json.get("air_baseline_pressure").getAsDouble());
        if (json.has("air_heat_capacity"))
            airHeatCapacity = Math.max(0.01, json.get("air_heat_capacity").getAsDouble());
        if (json.has("air_conductivity"))
            airConductivity = Math.max(0.0, json.get("air_conductivity").getAsDouble());
        if (json.has("pressure_equalization_rate"))
            pressureEqualizationRate = Math.max(0.0, Math.min(1.0, json.get("pressure_equalization_rate").getAsDouble()));
        if (json.has("buoyancy_strength"))
            buoyancyStrength = Math.max(0.0, json.get("buoyancy_strength").getAsDouble());
        if (json.has("heat_advection_strength"))
            heatAdvectionStrength = Math.max(0.0, json.get("heat_advection_strength").getAsDouble());
        if (json.has("pressure_delta_threshold"))
            pressureDeltaThreshold = Math.max(0.0, json.get("pressure_delta_threshold").getAsDouble());
        if (json.has("fluid_sync_range"))
            fluidSyncRange = Math.max(0, json.get("fluid_sync_range").getAsInt());
        if (json.has("fluid_debug_sync_threshold"))
            fluidDebugSyncThreshold = Math.max(0.0, json.get("fluid_debug_sync_threshold").getAsDouble());
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();

        json.addProperty("worker_threads", workerThreads);
        json.addProperty("work_budget_per_tick", workBudgetPerTick);
        json.addProperty("graceful_degradation", gracefulDegradation);
        json.addProperty("simulation_interval_ticks", simulationIntervalTicks);
        json.addProperty("delta_threshold", deltaThreshold);
        json.addProperty("air_insulates", airInsulates);
        json.addProperty("water_transfer_multiplier", waterTransferMultiplier);
        json.addProperty("dissipation_multiplier", dissipationMultiplier);
        json.addProperty("time_budget_ms_per_tick", timeBudgetMsPerTick);
        json.addProperty("smoothing_enabled", smoothingEnabled);
        json.addProperty("smoothing_radius", smoothingRadius);
        json.addProperty("smoothing_budget", smoothingBudget);
        json.addProperty("sync_threshold", syncThreshold);
        json.addProperty("sync_range", syncRange);
        json.addProperty("debug_mode", debugMode);
        json.addProperty("max_propagation_radius", maxPropagationRadius);
        json.addProperty("ticks_per_radius_step", ticksPerRadiusStep);
        json.addProperty("temperature_ramp_rate", temperatureRampRate);
        json.addProperty("ambient_temperature", ambientTemperature);
        json.addProperty("fluid_simulation_enabled", fluidSimulationEnabled);
        json.addProperty("fluid_simulation_interval_ticks", fluidSimulationIntervalTicks);
        json.addProperty("fluid_work_budget_per_tick", fluidWorkBudgetPerTick);
        json.addProperty("fluid_time_budget_ms_per_tick", fluidTimeBudgetMsPerTick);
        json.addProperty("air_baseline_pressure", airBaselinePressure);
        json.addProperty("air_heat_capacity", airHeatCapacity);
        json.addProperty("air_conductivity", airConductivity);
        json.addProperty("pressure_equalization_rate", pressureEqualizationRate);
        json.addProperty("buoyancy_strength", buoyancyStrength);
        json.addProperty("heat_advection_strength", heatAdvectionStrength);
        json.addProperty("pressure_delta_threshold", pressureDeltaThreshold);
        json.addProperty("fluid_sync_range", fluidSyncRange);
        json.addProperty("fluid_debug_sync_threshold", fluidDebugSyncThreshold);

        return json;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public int getWorkBudgetPerTick() {
        return workBudgetPerTick;
    }

    public boolean isGracefulDegradation() {
        return gracefulDegradation;
    }

    public int getSimulationIntervalTicks() {
        return simulationIntervalTicks;
    }

    public double getDeltaThreshold() {
        return deltaThreshold;
    }

    public boolean isAirInsulates() {
        return airInsulates;
    }

    public double getWaterTransferMultiplier() {
        return waterTransferMultiplier;
    }

    public double getDissipationMultiplier() {
        return dissipationMultiplier;
    }

    public double getTimeBudgetMsPerTick() {
        return timeBudgetMsPerTick;
    }

    public int getSmoothingRadius() {
        return smoothingRadius;
    }

    public int getSmoothingBudget() {
        return smoothingBudget;
    }

    public boolean isSmoothingEnabled() {
        return smoothingEnabled;
    }

    public double getSyncThreshold() {
        return syncThreshold;
    }

    public int getSyncRange() {
        return syncRange;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public double getAmbientTemperature() {
        return ambientTemperature;
    }

    public int getMaxPropagationRadius() {
        return maxPropagationRadius;
    }

    public int getTicksPerRadiusStep() {
        return ticksPerRadiusStep;
    }

    public double getTemperatureRampRate() {
        return temperatureRampRate;
    }

    public boolean isFluidSimulationEnabled() {
        return fluidSimulationEnabled;
    }

    public int getFluidSimulationIntervalTicks() {
        return fluidSimulationIntervalTicks;
    }

    public int getFluidWorkBudgetPerTick() {
        return fluidWorkBudgetPerTick;
    }

    public double getFluidTimeBudgetMsPerTick() {
        return fluidTimeBudgetMsPerTick;
    }

    public double getAirBaselinePressure() {
        return airBaselinePressure;
    }

    public double getAirHeatCapacity() {
        return airHeatCapacity;
    }

    public double getAirConductivity() {
        return airConductivity;
    }

    public double getPressureEqualizationRate() {
        return pressureEqualizationRate;
    }

    public double getBuoyancyStrength() {
        return buoyancyStrength;
    }

    public double getHeatAdvectionStrength() {
        return heatAdvectionStrength;
    }

    public double getPressureDeltaThreshold() {
        return pressureDeltaThreshold;
    }

    public int getFluidSyncRange() {
        return fluidSyncRange;
    }

    public double getFluidDebugSyncThreshold() {
        return fluidDebugSyncThreshold;
    }
}
