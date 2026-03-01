package com.Tribulla.thermodynamica.simulation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public class HeatSavedData extends SavedData {

    private final HeatSimulationManager manager;

    public HeatSavedData(HeatSimulationManager manager) {
        this.manager = manager;
    }

    public static HeatSavedData load(CompoundTag tag, HeatSimulationManager manager) {
        HeatSavedData data = new HeatSavedData(manager);
        manager.loadFromNBT(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        manager.saveToNBT(tag);
        return tag;
    }
}
