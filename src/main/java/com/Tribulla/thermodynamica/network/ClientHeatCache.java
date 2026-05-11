package com.Tribulla.thermodynamica.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * @deprecated Use {@link com.Tribulla.thermodynamica.api.ClientHeatCache} instead.
 * This class now delegates to the API package for backwards compatibility.
 */
@Deprecated
public class ClientHeatCache {

    private static volatile DebugInfoPacket lastDebugInfo;

    public static void update(BlockPos pos, double celsius) {
        com.Tribulla.thermodynamica.api.ClientHeatCache.update(pos, celsius);
    }

    public static void update(BlockPos pos, double celsius, BlockPos renderStatePos, Vec3 worldCenter) {
        com.Tribulla.thermodynamica.api.ClientHeatCache.update(pos, celsius, renderStatePos, worldCenter);
    }

    @Nullable
    public static com.Tribulla.thermodynamica.api.CachedHeatEntry get(BlockPos pos) {
        return com.Tribulla.thermodynamica.api.ClientHeatCache.get(pos);
    }

    public static Map<BlockPos, com.Tribulla.thermodynamica.api.CachedHeatEntry> getSnapshot() {
        return com.Tribulla.thermodynamica.api.ClientHeatCache.getSnapshot();
    }

    public static void clear() {
        com.Tribulla.thermodynamica.api.ClientHeatCache.clear();
    }

    public static void setDebugInfo(DebugInfoPacket packet) {
        lastDebugInfo = packet;
    }

    @Nullable
    public static DebugInfoPacket getLastDebugInfo() {
        return lastDebugInfo;
    }

    /**
     * @deprecated Use {@link com.Tribulla.thermodynamica.api.CachedHeatEntry} instead.
     */
    @Deprecated
    public record CachedHeatEntry(double celsius) {
    }
}
