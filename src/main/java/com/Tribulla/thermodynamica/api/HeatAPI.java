package com.Tribulla.thermodynamica.api;

import com.Tribulla.thermodynamica.api.targeting.HeatTarget;
import com.Tribulla.thermodynamica.api.targeting.HeatTargeting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Consumer;

public abstract class HeatAPI {
    private static HeatAPI INSTANCE;

    public static HeatAPI get() {
        if (INSTANCE == null)
            throw new IllegalStateException("Thermodynamica has not been initialized yet");
        return INSTANCE;
    }

    public static void setInstance(HeatAPI instance) {
        INSTANCE = instance;
    }

    public abstract HeatTier getResolvedTier(ResourceLocation block);

    public abstract double getResolvedCelsius(ResourceLocation block, Level level, BlockPos pos);

    public abstract OptionalDouble getSimulatedCelsius(Level level, BlockPos pos);

    public abstract double getVisualCelsius(Level level, BlockPos pos);

    public abstract Map<BlockPos, Double> getSimulatedSourcesInChunk(Level level, ChunkPos pos);

    public abstract void forceProcessChunks(int ticks);

    public abstract HeatTier getAmbientTier();

    public abstract void registerBlockTier(ResourceLocation block, HeatTier tier);

    public abstract void registerBlockCelsius(ResourceLocation block, double celsius);

    @Nullable
    public abstract TierResolution resolveBlockTier(ResourceLocation block);

    public abstract void onTierChange(Consumer<TierChangeEvent> listener);

    public abstract void onTemperatureChange(Consumer<TemperatureChangeEvent> listener);

    public abstract boolean isInTier(ResourceLocation block, HeatTier tier);

    public abstract ThermalProperties getThermalProperties(ResourceLocation block);

    public abstract double getTierCelsius(HeatTier tier);

    public abstract double getBiomeOffset(Level level, BlockPos pos);

    // ==================== Targeting API ====================
    
    /**
     * Returns all active heat sources in the given level above the minimum temperature.
     * This uses the simulation's source index for efficient lookup.
     * 
     * @param level The level to search
     * @param minCelsius Minimum temperature threshold
     * @return Map of positions to temperatures for all active sources
     */
    public abstract Map<BlockPos, Double> getActiveHeatSources(Level level, double minCelsius);

    /**
     * Finds the hottest heat source within radius from origin.
     * Does not check line of sight.
     *
     * @param level The level to search in
     * @param origin The origin position
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @return The hottest target, or null if none found
     */
    @Nullable
    public HeatTarget getHottestInRadius(Level level, Vec3 origin, double radius, double minCelsius) {
        return HeatTargeting.getHottestInRadius(level, origin, radius, minCelsius);
    }

    /**
     * Finds the hottest heat source within radius that has line of sight.
     * Iterates from hottest to coldest, checking LOS until a visible source is found.
     *
     * @param level The level to search in
     * @param origin The origin position
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @return The hottest visible target, or null if none found
     */
    @Nullable
    public HeatTarget getHottestWithLOS(Level level, Vec3 origin, double radius, double minCelsius) {
        return HeatTargeting.getHottestWithLOS(level, origin, radius, minCelsius);
    }

    /**
     * Finds all heat sources within radius, sorted by temperature (hottest first).
     *
     * @param level The level to search in
     * @param origin The origin position
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @return List of heat targets sorted by temperature
     */
    public List<HeatTarget> getHeatSourcesInRadius(Level level, Vec3 origin, double radius, double minCelsius) {
        return HeatTargeting.getHeatSourcesInRadius(level, origin, radius, minCelsius);
    }

    /**
     * Finds all heat sources within radius that have line of sight.
     *
     * @param level The level to search in
     * @param origin The origin position
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @return List of visible heat targets sorted by temperature
     */
    public List<HeatTarget> getHeatSourcesWithLOS(Level level, Vec3 origin, double radius, double minCelsius) {
        return HeatTargeting.getHeatSourcesWithLOS(level, origin, radius, minCelsius);
    }

    /**
     * Finds the best target considering both temperature and distance.
     * Uses scoring: score = temperature / (1 + distance/10)
     *
     * @param level The level to search in
     * @param origin The origin position
     * @param radius The search radius in blocks
     * @param minCelsius Minimum temperature to consider
     * @param requireLOS If true, only considers targets with line of sight
     * @return The best scoring target, or null if none found
     */
    @Nullable
    public HeatTarget getBestTarget(Level level, Vec3 origin, double radius, double minCelsius, boolean requireLOS) {
        return HeatTargeting.getBestTarget(level, origin, radius, minCelsius, requireLOS);
    }

    /**
     * Finds targets within a cone from origin in the given direction.
     * Useful for forward-looking heat seekers.
     *
     * @param level The level to search in
     * @param origin The origin position
     * @param direction The look direction (will be normalized)
     * @param radius The search radius in blocks
     * @param coneAngle The half-angle of the cone in degrees
     * @param minCelsius Minimum temperature to consider
     * @param requireLOS If true, only considers targets with line of sight
     * @return List of targets within the cone, sorted by temperature
     */
    public List<HeatTarget> getTargetsInCone(Level level, Vec3 origin, Vec3 direction, 
            double radius, double coneAngle, double minCelsius, boolean requireLOS) {
        return HeatTargeting.getTargetsInCone(level, origin, direction, radius, coneAngle, minCelsius, requireLOS);
    }

    /**
     * Checks if there's a clear line of sight between two positions.
     *
     * @param level The level to check in
     * @param from Starting position
     * @param to Target position
     * @return true if there's unobstructed line of sight
     */
    public boolean hasLineOfSight(Level level, Vec3 from, Vec3 to) {
        return HeatTargeting.hasLineOfSight(level, from, to);
    }
}
