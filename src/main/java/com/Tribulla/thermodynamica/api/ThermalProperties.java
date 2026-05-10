package com.Tribulla.thermodynamica.api;

public class ThermalProperties {

    public static final double DEFAULT_CONDUCTIVITY = 1.0;
    public static final double DEFAULT_HEAT_CAPACITY = 1000.0;
    public static final double DEFAULT_DISSIPATION_RATE = 0.05;

    private final double conductivity;
    private final double heatCapacity;
    private final double dissipationRate;

    public ThermalProperties(double conductivity, double heatCapacity, double dissipationRate) {
        this.conductivity = conductivity;
        this.heatCapacity = heatCapacity;
        this.dissipationRate = dissipationRate;
    }

    public static ThermalProperties defaults() {
        return new ThermalProperties(DEFAULT_CONDUCTIVITY, DEFAULT_HEAT_CAPACITY, DEFAULT_DISSIPATION_RATE);
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

    @Override
    public String toString() {
        return "ThermalProperties{conductivity=" + conductivity +
                ", heatCapacity=" + heatCapacity +
                ", dissipationRate=" + dissipationRate + "}";
    }
}
