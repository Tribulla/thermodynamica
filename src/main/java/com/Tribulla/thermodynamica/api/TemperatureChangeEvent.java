package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public class TemperatureChangeEvent {

    private final Level level;
    private final BlockPos pos;
    private final double oldCelsius;
    private final double newCelsius;

    public TemperatureChangeEvent(Level level, BlockPos pos, double oldCelsius, double newCelsius) {
        this.level = level;
        this.pos = pos;
        this.oldCelsius = oldCelsius;
        this.newCelsius = newCelsius;
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
}
