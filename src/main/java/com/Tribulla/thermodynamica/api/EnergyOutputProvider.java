package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.OptionalDouble;

public interface EnergyOutputProvider {
    OptionalDouble getEnergyOutput(Level level, BlockPos pos);
}
