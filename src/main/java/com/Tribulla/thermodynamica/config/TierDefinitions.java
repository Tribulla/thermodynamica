package com.Tribulla.thermodynamica.config;

import com.Tribulla.thermodynamica.api.HeatTier;
import com.google.gson.JsonObject;

public class TierDefinitions implements ConfigSection {

    private final double[] celsius = new double[HeatTier.values().length];

    public TierDefinitions() {
        celsius[HeatTier.NEG5.ordinal()] = -200.0;
        celsius[HeatTier.NEG4.ordinal()] = -150.0;
        celsius[HeatTier.NEG3.ordinal()] = -100.0;
        celsius[HeatTier.NEG2.ordinal()] = -50.0;
        celsius[HeatTier.NEG1.ordinal()] = -20.0;
        celsius[HeatTier.ZERO.ordinal()] = 0.0;
        celsius[HeatTier.POS1.ordinal()] = 20.0;
        celsius[HeatTier.POS2.ordinal()] = 100.0;
        celsius[HeatTier.POS3.ordinal()] = 500.0;
        celsius[HeatTier.POS4.ordinal()] = 1000.0;
        celsius[HeatTier.POS5.ordinal()] = 3000.0;
    }

    @Override
    public void load(JsonObject json) {
        for (HeatTier tier : HeatTier.values()) {
            if (json.has(tier.getId())) {
                celsius[tier.ordinal()] = json.get(tier.getId()).getAsDouble();
            }
        }
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("_comment",
                "Maps each heat tier to its nominal Celsius value. All values are fully configurable.");
        for (HeatTier tier : HeatTier.values()) {
            json.addProperty(tier.getId(), celsius[tier.ordinal()]);
        }
        return json;
    }

    public double getCelsius(HeatTier tier) {
        return celsius[tier.ordinal()];
    }

    public void setCelsius(HeatTier tier, double value) {
        celsius[tier.ordinal()] = value;
    }

    public double[] getAllCelsius() {
        return celsius.clone();
    }
}
