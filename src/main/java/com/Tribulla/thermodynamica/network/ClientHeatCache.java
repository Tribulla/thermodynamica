package com.Tribulla.thermodynamica.network;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHeatCache {

    private static final ConcurrentHashMap<BlockPos, CachedHeatEntry> cache = new ConcurrentHashMap<>();
    private static volatile DebugInfoPacket lastDebugInfo;

    public static void update(BlockPos pos, double celsius, int tierOrdinal) {
        cache.put(pos.immutable(), new CachedHeatEntry(celsius, tierOrdinal));
    }

    @Nullable
    public static CachedHeatEntry get(BlockPos pos) {
        return cache.get(pos);
    }

    public static void clear() {
        cache.clear();
    }

    public static void setDebugInfo(DebugInfoPacket packet) {
        lastDebugInfo = packet;
    }

    @Nullable
    public static DebugInfoPacket getLastDebugInfo() {
        return lastDebugInfo;
    }

    public record CachedHeatEntry(double celsius, int tierOrdinal) {
    }
}
