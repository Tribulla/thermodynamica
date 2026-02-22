package com.Tribulla.thermodynamica.simulation;

import net.minecraft.core.BlockPos;

public record HeatUpdate(BlockPos pos, double celsius) {
}
