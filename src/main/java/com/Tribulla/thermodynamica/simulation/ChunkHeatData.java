package com.Tribulla.thermodynamica.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkHeatData {

    private final ChunkPos chunkPos;
    private final ConcurrentHashMap<BlockPos, Double> temperatures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockPos, ResourceLocation> blockIds = new ConcurrentHashMap<>();

    public ChunkHeatData(ChunkPos chunkPos) {
        this.chunkPos = chunkPos;
    }

    public Double getTemperature(BlockPos pos) {
        return temperatures.get(pos);
    }

    public void setTemperature(BlockPos pos, double celsius) {
        temperatures.put(pos.immutable(), celsius);
    }

    public void removeTemperature(BlockPos pos) {
        temperatures.remove(pos);
    }

    public ConcurrentHashMap<BlockPos, Double> getTemperatures() {
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

        for (Map.Entry<BlockPos, Double> entry : temperatures.entrySet()) {
            BlockPos pos = entry.getKey();
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
                result.put(pos, entry.getValue());
            }
        }
        return result;
    }

    public void applyUpdates(List<HeatUpdate> updates) {
        for (HeatUpdate update : updates) {
            temperatures.put(update.pos().immutable(), update.celsius());
        }
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }
}
