package com.Tribulla.thermodynamica.simulation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public class FluidSavedData extends SavedData {

    private final FluidSimulationManager manager;

    public FluidSavedData(FluidSimulationManager manager) {
        this.manager = manager;
    }

    public static FluidSavedData load(CompoundTag tag, FluidSimulationManager manager) {
        FluidSavedData data = new FluidSavedData(manager);
        manager.loadFromNBT(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        manager.saveToNBT(tag);
        return tag;
    }
}
