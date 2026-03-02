package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache for heat data received from the server.
 * This cache is automatically updated by the server's heat sync packets.
 * 
 * Use {@link #getSnapshot()} to get a thread-safe copy of all cached heat data,
 * which is useful for rendering thermal overlays without blocking the main thread.
 */
public class ClientHeatCache {
    
    private static final ConcurrentHashMap<BlockPos, CachedHeatEntry> cache = new ConcurrentHashMap<>();
    
    /**
     * Update a position in the cache with new temperature data.
     * Called internally by network packets.
     * 
     * @param pos the block position
     * @param celsius temperature in Celsius
     * @param tierOrdinal the heat tier ordinal, or -1 if unknown
     */
    public static void update(BlockPos pos, double celsius, int tierOrdinal) {
        cache.put(pos.immutable(), new CachedHeatEntry(celsius, tierOrdinal));
    }
    
    /**
     * Get the cached heat entry for a position.
     * 
     * @param pos the block position
     * @return the cached entry, or null if not in cache
     */
    @Nullable
    public static CachedHeatEntry get(BlockPos pos) {
        return cache.get(pos);
    }
    
    /**
     * Get a thread-safe snapshot of all cached heat data.
     * This returns a copy of the cache, so modifications to the returned map
     * will not affect the cache.
     * 
     * This is the recommended way to access heat data for rendering,
     * as it avoids concurrent modification issues.
     * 
     * @return a copy of the current cache state
     */
    public static Map<BlockPos, CachedHeatEntry> getSnapshot() {
        return new HashMap<>(cache);
    }
    
    /**
     * Check if a position is in the cache.
     * 
     * @param pos the block position
     * @return true if the position has cached heat data
     */
    public static boolean contains(BlockPos pos) {
        return cache.containsKey(pos);
    }
    
    /**
     * Get the number of entries in the cache.
     * 
     * @return cache size
     */
    public static int size() {
        return cache.size();
    }
    
    /**
     * Clear all cached heat data.
     * Called when disconnecting from a server or changing dimensions.
     */
    public static void clear() {
        cache.clear();
    }
    
    /**
     * Remove a specific position from the cache.
     * 
     * @param pos the block position to remove
     */
    public static void remove(BlockPos pos) {
        cache.remove(pos);
    }
}
