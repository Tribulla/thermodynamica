package com.Tribulla.thermodynamica.api;

/**
 * Represents a cached heat entry from the client-side heat cache.
 * Contains the temperature in Celsius and optionally the heat tier ordinal.
 */
public record CachedHeatEntry(double celsius, int tierOrdinal) {
    
    /**
     * Get the temperature in Celsius.
     * @return temperature in degrees Celsius
     */
    public double getCelsius() {
        return celsius;
    }
    
    /**
     * Get the heat tier ordinal, or -1 if not available.
     * @return heat tier ordinal (0-10) or -1
     */
    public int getTierOrdinal() {
        return tierOrdinal;
    }
    
    /**
     * Get the heat tier if available.
     * @return the HeatTier or null if ordinal is invalid
     */
    public HeatTier getTier() {
        if (tierOrdinal < 0 || tierOrdinal >= HeatTier.values().length) {
            return null;
        }
        return HeatTier.values()[tierOrdinal];
    }
}
