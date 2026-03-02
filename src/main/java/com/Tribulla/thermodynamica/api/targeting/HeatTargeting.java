package com.Tribulla.thermodynamica.api.targeting;

import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.compat.ValkyrienSkiesCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Utility class for heat-seeking and thermal targeting systems.
 * Provides methods to find heat sources in an area with optional line-of-sight checking.
 * 
 * <p>Example usage for a heat-seeking missile:
 * <pre>{@code
 * Vec3 missilePos = missile.position();
 * HeatTarget target = HeatTargeting.getHottestWithLOS(level, missilePos, 64.0, 100.0);
 * if (target != null) {
 *     Vec3 targetDir = target.getCenter().subtract(missilePos).normalize();
 *     // Guide missile toward target
 * }
 * }</pre>
 */
public final class HeatTargeting {

    private HeatTargeting() {}

    /**
     * Finds all heat sources within radius, sorted by temperature (hottest first).
     * Does not check line of sight.
     *
     * @param level The level to search in
     * @param origin The origin position to search from
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider (filters out ambient)
     * @return List of heat targets sorted by temperature (hottest first)
     */
    public static List<HeatTarget> getHeatSourcesInRadius(Level level, Vec3 origin, double radius, double minCelsius) {
        return getHeatSourcesFiltered(level, origin, radius, minCelsius, t -> true, false);
    }

    /**
     * Finds all heat sources within radius that have line of sight, sorted by temperature.
     *
     * @param level The level to search in
     * @param origin The origin position to search from
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @return List of heat targets with LOS, sorted by temperature (hottest first)
     */
    public static List<HeatTarget> getHeatSourcesWithLOS(Level level, Vec3 origin, double radius, double minCelsius) {
        return getHeatSourcesFiltered(level, origin, radius, minCelsius, HeatTarget::hasLineOfSight, true);
    }

    /**
     * Finds the hottest heat source within radius, regardless of line of sight.
     *
     * @param level The level to search in
     * @param origin The origin position to search from
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @return The hottest target, or null if none found
     */
    @Nullable
    public static HeatTarget getHottestInRadius(Level level, Vec3 origin, double radius, double minCelsius) {
        List<HeatTarget> targets = getHeatSourcesInRadius(level, origin, radius, minCelsius);
        return targets.isEmpty() ? null : targets.get(0);
    }

    /**
     * Finds the hottest heat source within radius that has line of sight.
     * Iterates from hottest to coldest, checking LOS until a visible source is found.
     *
     * @param level The level to search in
     * @param origin The origin position to search from
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @return The hottest visible target, or null if none found
     */
    @Nullable
    public static HeatTarget getHottestWithLOS(Level level, Vec3 origin, double radius, double minCelsius) {
        List<HeatTarget> targets = getHeatSourcesInRadius(level, origin, radius, minCelsius);
        
        // Iterate from hottest to coldest, checking LOS
        for (HeatTarget target : targets) {
            if (hasLineOfSight(level, origin, target.getCenter())) {
                return target.withLOS(true);
            }
        }
        return null;
    }

    /**
     * Finds the best target considering both temperature and distance.
     * Uses a scoring system: score = temperature / (1 + distance/10)
     *
     * @param level The level to search in
     * @param origin The origin position to search from
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @param requireLOS If true, only considers targets with line of sight
     * @return The best scoring target, or null if none found
     */
    @Nullable
    public static HeatTarget getBestTarget(Level level, Vec3 origin, double radius, double minCelsius, boolean requireLOS) {
        List<HeatTarget> targets = requireLOS 
                ? getHeatSourcesWithLOS(level, origin, radius, minCelsius)
                : getHeatSourcesInRadius(level, origin, radius, minCelsius);
        
        return targets.stream()
                .max(Comparator.comparingDouble(HeatTarget::getTargetScore))
                .orElse(null);
    }

    /**
     * Finds targets within a cone from origin in the given direction.
     *
     * @param level The level to search in
     * @param origin The origin position
     * @param direction The direction to look (will be normalized)
     * @param radius The search radius in blocks
     * @param coneAngle The half-angle of the cone in degrees (e.g., 45 for 90° FOV)
     * @param minCelsius Minimum temperature to consider
     * @param requireLOS If true, only considers targets with line of sight
     * @return List of targets within the cone, sorted by temperature
     */
    public static List<HeatTarget> getTargetsInCone(Level level, Vec3 origin, Vec3 direction, 
            double radius, double coneAngle, double minCelsius, boolean requireLOS) {
        Vec3 normalizedDir = direction.normalize();
        double cosAngle = Math.cos(Math.toRadians(coneAngle));
        
        List<HeatTarget> targets = requireLOS 
                ? getHeatSourcesWithLOS(level, origin, radius, minCelsius)
                : getHeatSourcesInRadius(level, origin, radius, minCelsius);
        
        return targets.stream()
                .filter(target -> {
                    Vec3 toTarget = target.getCenter().subtract(origin).normalize();
                    double dot = normalizedDir.dot(toTarget);
                    return dot >= cosAngle;
                })
                .collect(Collectors.toList());
    }

    /**
     * Gets the nearest heat source above a temperature threshold.
     *
     * @param level The level to search in
     * @param origin The origin position
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @param requireLOS If true, only considers targets with line of sight
     * @return The nearest target, or null if none found
     */
    @Nullable
    public static HeatTarget getNearestHeatSource(Level level, Vec3 origin, double radius, 
            double minCelsius, boolean requireLOS) {
        List<HeatTarget> targets = requireLOS 
                ? getHeatSourcesWithLOS(level, origin, radius, minCelsius)
                : getHeatSourcesInRadius(level, origin, radius, minCelsius);
        
        return targets.stream()
                .min(Comparator.comparingDouble(HeatTarget::distanceSquared))
                .orElse(null);
    }

    /**
     * Checks if there's a clear line of sight between two positions.
     * Uses Minecraft's built-in raycasting.
     *
     * @param level The level to check in
     * @param from Starting position
     * @param to Target position
     * @return true if there's unobstructed line of sight
     */
    public static boolean hasLineOfSight(Level level, Vec3 from, Vec3 to) {
        // Handle VS ships - transform coordinates if needed
        BlockPos fromBlock = BlockPos.containing(from);
        BlockPos toBlock = BlockPos.containing(to);
        
        // If either position is on a VS ship, transform to world coordinates
        if (ValkyrienSkiesCompat.isVSInstalled()) {
            if (ValkyrienSkiesCompat.isOnShip(level, fromBlock)) {
                from = ValkyrienSkiesCompat.toWorldCoordinates(level, fromBlock, from);
            }
            if (ValkyrienSkiesCompat.isOnShip(level, toBlock)) {
                to = ValkyrienSkiesCompat.toWorldCoordinates(level, toBlock, to);
            }
        }
        
        ClipContext context = new ClipContext(
                from, to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                null
        );
        
        BlockHitResult result = level.clip(context);
        
        // If we hit nothing or hit the target block, we have LOS
        if (result.getType() == HitResult.Type.MISS) {
            return true;
        }
        
        // Check if we hit the target block (allow hitting the target itself)
        BlockPos hitPos = result.getBlockPos();
        return hitPos.equals(toBlock);
    }

    /**
     * Core method that gathers heat sources and optionally checks LOS.
     */
    private static List<HeatTarget> getHeatSourcesFiltered(Level level, Vec3 origin, double radius, 
            double minCelsius, Predicate<HeatTarget> filter, boolean checkLOS) {
        
        double radiusSq = radius * radius;
        List<HeatTarget> targets = new ArrayList<>();
        HeatAPI api = HeatAPI.get();
        
        // Get the ambient temperature as baseline
        double ambientCelsius = api.getTierCelsius(api.getAmbientTier());
        double effectiveMinCelsius = Math.max(minCelsius, ambientCelsius + 10); // At least 10° above ambient
        
        // Scan the area for heat sources
        BlockPos originBlock = BlockPos.containing(origin);
        int scanRadius = (int) Math.ceil(radius);
        
        for (int x = -scanRadius; x <= scanRadius; x++) {
            for (int y = -scanRadius; y <= scanRadius; y++) {
                for (int z = -scanRadius; z <= scanRadius; z++) {
                    BlockPos pos = originBlock.offset(x, y, z);
                    double distSq = origin.distanceToSqr(Vec3.atCenterOf(pos));
                    
                    if (distSq > radiusSq) continue;
                    
                    double celsius = api.getVisualCelsius(level, pos);
                    if (celsius >= effectiveMinCelsius) {
                        boolean los = checkLOS && hasLineOfSight(level, origin, Vec3.atCenterOf(pos));
                        HeatTarget target = new HeatTarget(pos, celsius, distSq, los);
                        if (filter.test(target)) {
                            targets.add(target);
                        }
                    }
                }
            }
        }
        
        // Sort by temperature (hottest first)
        targets.sort(null);
        return targets;
    }

    /**
     * Optimized method using the simulation's source index instead of scanning.
     * This is much faster for large areas but only finds active heat sources.
     * 
     * @param level The level to search in
     * @param origin The origin position
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @param checkLOS Whether to check line of sight
     * @return List of heat targets from active sources, sorted by temperature
     */
    public static List<HeatTarget> getActiveHeatSources(Level level, Vec3 origin, double radius, 
            double minCelsius, boolean checkLOS) {
        return HeatTargetingInternal.getActiveSourcesInRadius(level, origin, radius, minCelsius, checkLOS);
    }
}
