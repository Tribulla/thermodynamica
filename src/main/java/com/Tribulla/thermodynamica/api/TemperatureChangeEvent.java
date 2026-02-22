package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public class TemperatureChangeEvent {

    private final Level level;
    private final BlockPos pos;
    private final double oldCelsius;
    private final double newCelsius;
    private final HeatTier oldTier;
    private final HeatTier newTier;

    public TemperatureChangeEvent(Level level, BlockPos pos, double oldCelsius, double newCelsius,
            HeatTier oldTier, HeatTier newTier) {
        this.level = level;
        this.pos = pos;
        this.oldCelsius = oldCelsius;
        this.newCelsius = newCelsius;
        this.oldTier = oldTier;
        this.newTier = newTier;
    }

    public Level getLevel() {
        return level;
    }

    public BlockPos getPos() {
        return pos;
    }

    public double getOldCelsius() {
        return oldCelsius;
    }

    public double getNewCelsius() {
        return newCelsius;
    }

    public HeatTier getOldTier() {
        return oldTier;
    }

    public HeatTier getNewTier() {
        return newTier;
    }
}
