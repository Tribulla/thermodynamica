package com.Tribulla.thermodynamica.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public class GlobalBlockPos {
    final ResourceLocation dimension;
    final BlockPos pos;

    public GlobalBlockPos(ResourceLocation dimension, BlockPos pos) {
        this.dimension = dimension;
        this.pos = pos.immutable();
    }

    public ResourceLocation getDimension() {
        return dimension;
    }

    public BlockPos getPos() {
        return pos;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof GlobalBlockPos other))
            return false;
        return dimension.equals(other.dimension) && pos.equals(other.pos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, pos);
    }
}
