package com.Tribulla.thermodynamica.api.targeting;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Represents a heat source that can be targeted by heat-seeking systems.
 * Contains position, temperature, and distance from the seeker.
 */
public record HeatTarget(
        BlockPos blockPos,
        double celsius,
        double distanceSquared,
        boolean hasLineOfSight
) implements Comparable<HeatTarget> {

    /**
     * Gets the center position of the heat source block as a Vec3.
     */
    public Vec3 getCenter() {
        return Vec3.atCenterOf(blockPos);
    }

    /**
     * Gets the actual distance (not squared) from the seeker.
     */
    public double getDistance() {
        return Math.sqrt(distanceSquared);
    }

    /**
     * Calculates a targeting score combining temperature and distance.
     * Higher scores indicate better targets.
     * Score = temperature / (1 + distance/10)
     */
    public double getTargetScore() {
        return celsius / (1.0 + getDistance() / 10.0);
    }

    /**
     * Compares targets by temperature (hottest first).
     */
    @Override
    public int compareTo(HeatTarget other) {
        return Double.compare(other.celsius, this.celsius);
    }

    /**
     * Creates a new HeatTarget with LOS status updated.
     */
    public HeatTarget withLOS(boolean los) {
        return new HeatTarget(blockPos, celsius, distanceSquared, los);
    }

    @Override
    public String toString() {
        return String.format("HeatTarget[pos=%s, %.1f°C, dist=%.1f, LOS=%s]",
                blockPos.toShortString(), celsius, getDistance(), hasLineOfSight);
    }
}
