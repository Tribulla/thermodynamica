package com.Tribulla.thermodynamica.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMaps;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

public class ChunkHeatData {

    private final ChunkPos chunkPos;
    private final Long2DoubleMap temperatures = Long2DoubleMaps.synchronize(new Long2DoubleOpenHashMap());
    private final ConcurrentHashMap<BlockPos, ResourceLocation> blockIds = new ConcurrentHashMap<>();

    public ChunkHeatData(ChunkPos chunkPos) {
        this.chunkPos = chunkPos;
        // Optional: change default return value to NaN to differentiate from 0.0
        // but default 0.0 might be fine.
    }

    public Double getTemperature(BlockPos pos) {
        long key = pos.asLong();
        if (temperatures.containsKey(key)) {
            return temperatures.get(key);
        }
        return null;
    }

    public void setTemperature(BlockPos pos, double celsius) {
        temperatures.put(pos.asLong(), celsius);
    }

    public void removeTemperature(BlockPos pos) {
        temperatures.remove(pos.asLong());
    }

    public Long2DoubleMap getTemperatures() {
        return temperatures;
    }

    public ResourceLocation getBlockId(BlockPos pos) {
        return blockIds.get(pos);
    }

    public void setBlockId(BlockPos pos, ResourceLocation id) {
        blockIds.put(pos.immutable(), id);
    }

    public Map<BlockPos, Double> getEdgeTemperatures(ChunkPos neighborChunk, int radius) {
        Map<BlockPos, Double> result = new HashMap<>();
        int dx = neighborChunk.x - chunkPos.x;
        int dz = neighborChunk.z - chunkPos.z;

        for (Long2DoubleMap.Entry entry : temperatures.long2DoubleEntrySet()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            int localX = pos.getX() - chunkPos.getMinBlockX();
            int localZ = pos.getZ() - chunkPos.getMinBlockZ();

            boolean isEdge = false;
            if (dx < 0 && localX < radius)
                isEdge = true;
            if (dx > 0 && localX >= 16 - radius)
                isEdge = true;
            if (dz < 0 && localZ < radius)
                isEdge = true;
            if (dz > 0 && localZ >= 16 - radius)
                isEdge = true;

            if (isEdge) {
                result.put(pos, entry.getDoubleValue());
            }
        }
        return result;
    }

    public void applyUpdates(List<HeatUpdate> updates) {
        for (HeatUpdate update : updates) {
            temperatures.put(update.pos().asLong(), update.celsius());
        }
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }
}
