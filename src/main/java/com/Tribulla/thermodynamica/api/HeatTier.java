package com.Tribulla.thermodynamica.api;

public enum HeatTier {
    NEG5(-5, "neg5"),
    NEG4(-4, "neg4"),
    NEG3(-3, "neg3"),
    NEG2(-2, "neg2"),
    NEG1(-1, "neg1"),
    ZERO(0, "zero"),
    POS1(1, "pos1"),
    POS2(2, "pos2"),
    POS3(3, "pos3"),
    POS4(4, "pos4"),
    POS5(5, "pos5");

    private final int index;
    private final String id;

    HeatTier(int index, String id) {
        this.index = index;
        this.id = id;
    }

    public int getIndex() {
        return index;
    }

    public String getId() {
        return id;
    }

    public static HeatTier fromId(String id) {
        for (HeatTier tier : values()) {
            if (tier.id.equals(id))
                return tier;
        }
        throw new IllegalArgumentException("Unknown heat tier: " + id);
    }

    public static HeatTier fromIndex(int index) {
        for (HeatTier tier : values()) {
            if (tier.index == index)
                return tier;
        }
        throw new IllegalArgumentException("No heat tier for index: " + index);
    }

    public static HeatTier nearestTier(double celsius, double[] tierCelsius) {
        HeatTier best = ZERO;
        double bestDist = Double.MAX_VALUE;
        for (HeatTier tier : values()) {
            double dist = Math.abs(celsius - tierCelsius[tier.ordinal()]);
            if (dist < bestDist) {
                bestDist = dist;
                best = tier;
            }
        }
        return best;
    }
}
