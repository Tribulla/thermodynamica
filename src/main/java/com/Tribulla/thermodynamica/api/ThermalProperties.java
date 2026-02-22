package com.Tribulla.thermodynamica.api;

public class ThermalProperties {

    public static final double DEFAULT_CONDUCTIVITY = 1.0;
    public static final double DEFAULT_TRANSFER_RATE = 1.0;
    public static final double DEFAULT_DISSIPATION_RATE = 0.05;

    private final double conductivity;
    private final double transferRate;
    private final double dissipationRate;

    public ThermalProperties(double conductivity, double transferRate, double dissipationRate) {
        this.conductivity = conductivity;
        this.transferRate = transferRate;
        this.dissipationRate = dissipationRate;
    }

    public static ThermalProperties defaults() {
        return new ThermalProperties(DEFAULT_CONDUCTIVITY, DEFAULT_TRANSFER_RATE, DEFAULT_DISSIPATION_RATE);
    }

    public double getConductivity() {
        return conductivity;
    }

    public double getTransferRate() {
        return transferRate;
    }

    public double getDissipationRate() {
        return dissipationRate;
    }

    @Override
    public String toString() {
        return "ThermalProperties{conductivity=" + conductivity +
                ", transferRate=" + transferRate +
                ", dissipationRate=" + dissipationRate + "}";
    }
}
