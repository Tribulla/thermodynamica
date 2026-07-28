package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class PressureChangeEvent {

    private final Level level;
    private final BlockPos pos;
    private final double oldPressure;
    private final double newPressure;
    private final Vec3 velocity;

    public PressureChangeEvent(Level level, BlockPos pos, double oldPressure, double newPressure, Vec3 velocity) {
        this.level = level;
        this.pos = pos;
        this.oldPressure = oldPressure;
        this.newPressure = newPressure;
        this.velocity = velocity;
    }

    public Level getLevel() {
        return level;
    }

    public BlockPos getPos() {
        return pos;
    }

    public double getOldPressure() {
        return oldPressure;
    }

    public double getNewPressure() {
        return newPressure;
    }

    public Vec3 getVelocity() {
        return velocity;
    }
}
