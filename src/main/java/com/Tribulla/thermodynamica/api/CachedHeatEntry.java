package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record CachedHeatEntry(double celsius, BlockPos renderStatePos, Vec3 worldCenter) {}
