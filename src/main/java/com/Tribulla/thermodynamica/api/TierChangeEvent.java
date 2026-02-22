package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public class TierChangeEvent {

    private final ResourceLocation block;
    private final HeatTier oldTier;
    private final HeatTier newTier;

    public TierChangeEvent(ResourceLocation block, HeatTier oldTier, HeatTier newTier) {
        this.block = block;
        this.oldTier = oldTier;
        this.newTier = newTier;
    }

    public ResourceLocation getBlock() {
        return block;
    }

    public HeatTier getOldTier() {
        return oldTier;
    }

    public HeatTier getNewTier() {
        return newTier;
    }
}
