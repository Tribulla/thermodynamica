package com.Tribulla.thermodynamica.simulation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.Objects;

public class ChunkHeatKey {
    final ResourceLocation dimension;
    final ChunkPos chunkPos;

    public ChunkHeatKey(ResourceLocation dimension, ChunkPos chunkPos) {
        this.dimension = dimension;
        this.chunkPos = chunkPos;
    }

    public ResourceLocation getDimension() {
        return dimension;
    }

    public ChunkPos getChunkPos() {
        return chunkPos;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ChunkHeatKey other))
            return false;
        return dimension.equals(other.dimension) && chunkPos.equals(other.chunkPos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, chunkPos);
    }

    @Override
    public String toString() {
        return dimension + "@[" + chunkPos.x + "," + chunkPos.z + "]";
    }
}
