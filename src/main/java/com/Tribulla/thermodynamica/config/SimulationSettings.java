package com.Tribulla.thermodynamica.config;

import com.Tribulla.thermodynamica.api.HeatTier;
import com.google.gson.JsonObject;

public class SimulationSettings implements ConfigSection {

    private int workerThreads = 2;
    private int workBudgetPerTick = 50000;
    private boolean gracefulDegradation = true;
    private int simulationIntervalTicks = 20;
    private double deltaThreshold = 0.5;
    private boolean airInsulates = true;
    private double waterTransferMultiplier = 2.0;
    private double dissipationMultiplier = 1.0;
    private int smoothingRadius = 2;
    private int smoothingBudget = 500;
    private boolean smoothingEnabled = true;
    private double syncThreshold = 20.0;
    private int syncRange = 64;
    private boolean debugMode = false;
    private int maxPropagationRadius = 16;
    private int ticksPerRadiusStep = 5;
    private double temperatureRampRate = 0.15;
    private HeatTier ambientTier = HeatTier.POS1;

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
        if (json.has("ambient_tier"))
            ambientTier = HeatTier.fromId(json.get("ambient_tier").getAsString());
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
        json.addProperty("smoothing_enabled", smoothingEnabled);
        json.addProperty("smoothing_radius", smoothingRadius);
        json.addProperty("smoothing_budget", smoothingBudget);
        json.addProperty("sync_threshold", syncThreshold);
        json.addProperty("sync_range", syncRange);
        json.addProperty("debug_mode", debugMode);
        json.addProperty("max_propagation_radius", maxPropagationRadius);
        json.addProperty("ticks_per_radius_step", ticksPerRadiusStep);
        json.addProperty("temperature_ramp_rate", temperatureRampRate);
        json.addProperty("ambient_tier", ambientTier.getId());

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

    public HeatTier getAmbientTier() {
        return ambientTier;
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
}
