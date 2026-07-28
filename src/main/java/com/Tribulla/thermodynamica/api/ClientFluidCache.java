package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientFluidCache {

    private static final ConcurrentHashMap<BlockPos, CachedFluidEntry> cache = new ConcurrentHashMap<>();

    public static void update(BlockPos pos, double celsius, double pressure, Vec3 velocity) {
        update(pos, celsius, pressure, velocity, pos, Vec3.atCenterOf(pos));
    }

    public static void update(BlockPos pos, double celsius, double pressure, Vec3 velocity,
            BlockPos renderStatePos, Vec3 worldCenter) {
        cache.put(pos.immutable(), new CachedFluidEntry(celsius, pressure, velocity,
                renderStatePos.immutable(), worldCenter));
    }

    @Nullable
    public static CachedFluidEntry get(BlockPos pos) {
        return cache.get(pos);
    }

    public static Map<BlockPos, CachedFluidEntry> getSnapshot() {
        return new HashMap<>(cache);
    }

    public static int size() {
        return cache.size();
    }

    public static void clear() {
        cache.clear();
    }

    public static void remove(BlockPos pos) {
        cache.remove(pos);
    }
}
