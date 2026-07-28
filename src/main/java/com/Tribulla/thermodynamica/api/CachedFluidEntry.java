package com.Tribulla.thermodynamica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record CachedFluidEntry(
        double celsius,
        double pressure,
        Vec3 velocity,
        BlockPos renderStatePos,
        Vec3 worldCenter
) {
}
