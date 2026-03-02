package com.Tribulla.thermodynamica.api.targeting;

import com.Tribulla.thermodynamica.api.HeatAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Internal implementation for targeting that accesses simulation internals.
 * This class is initialized by Thermodynamica during startup.
 */
public final class HeatTargetingInternal {

    private HeatTargetingInternal() {}

    /**
     * Function to get active sources from the simulation manager.
     * Set by Thermodynamica during initialization.
     */
    private static volatile BiFunction<Level, Double, Map<BlockPos, Double>> sourceProvider;

    /**
     * Sets the source provider function. Called by Thermodynamica during init.
     */
    public static void setSourceProvider(BiFunction<Level, Double, Map<BlockPos, Double>> provider) {
        sourceProvider = provider;
    }

    /**
     * Gets active heat sources within radius using the simulation's source index.
     * Falls back to scanning if source provider not available.
     */
    static List<HeatTarget> getActiveSourcesInRadius(Level level, Vec3 origin, double radius, 
            double minCelsius, boolean checkLOS) {
        
        List<HeatTarget> targets = new ArrayList<>();
        double radiusSq = radius * radius;
        
        BiFunction<Level, Double, Map<BlockPos, Double>> provider = sourceProvider;
        if (provider == null) {
            // Fall back to scanning
            return HeatTargeting.getHeatSourcesInRadius(level, origin, radius, minCelsius);
        }
        
        Map<BlockPos, Double> sources = provider.apply(level, minCelsius);
        if (sources == null || sources.isEmpty()) {
            return targets;
        }
        
        for (Map.Entry<BlockPos, Double> entry : sources.entrySet()) {
            BlockPos pos = entry.getKey();
            double celsius = entry.getValue();
            
            if (celsius < minCelsius) continue;
            
            Vec3 center = Vec3.atCenterOf(pos);
            double distSq = origin.distanceToSqr(center);
            
            if (distSq > radiusSq) continue;
            
            boolean los = checkLOS && HeatTargeting.hasLineOfSight(level, origin, center);
            targets.add(new HeatTarget(pos, celsius, distSq, checkLOS ? los : false));
        }
        
        // Sort by temperature (hottest first)
        targets.sort(null);
        
        if (checkLOS) {
            // Filter to only LOS targets
            targets.removeIf(t -> !t.hasLineOfSight());
        }
        
        return targets;
    }
}
