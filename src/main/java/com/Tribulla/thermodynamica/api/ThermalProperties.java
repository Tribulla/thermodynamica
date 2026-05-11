package com.Tribulla.thermodynamica.api;

import java.util.OptionalDouble;

public class ThermalProperties {

    public static final double DEFAULT_CONDUCTIVITY = 5.0;
    public static final double DEFAULT_HEAT_CAPACITY = 200.0;
    public static final double DEFAULT_DISSIPATION_RATE = 0.08;

    private final double conductivity;
    private final double heatCapacity;
    private final double dissipationRate;
    private final OptionalDouble temperature;

    public ThermalProperties(double conductivity, double heatCapacity, double dissipationRate, OptionalDouble temperature) {
        this.conductivity = conductivity;
        this.heatCapacity = heatCapacity;
        this.dissipationRate = dissipationRate;
        this.temperature = temperature;
    }

    public ThermalProperties(double conductivity, double heatCapacity, double dissipationRate) {
        this(conductivity, heatCapacity, dissipationRate, OptionalDouble.empty());
    }

    public static ThermalProperties defaults() {
        return new ThermalProperties(DEFAULT_CONDUCTIVITY, DEFAULT_HEAT_CAPACITY, DEFAULT_DISSIPATION_RATE, OptionalDouble.empty());
    }

    public double getConductivity() {
        return conductivity;
    }

    public double getHeatCapacity() {
        return heatCapacity;
    }

    public double getDissipationRate() {
        return dissipationRate;
    }

    public OptionalDouble getTemperature() {
        return temperature;
    }

    @Override
    public String toString() {
        return "ThermalProperties{conductivity=" + conductivity +
                ", heatCapacity=" + heatCapacity +
                ", dissipationRate=" + dissipationRate +
                ", temperature=" + (temperature.isPresent() ? temperature.getAsDouble() : "empty") + "}";
    }
}
