package com.Tribulla.thermodynamica.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class VariableHeatBlockEntity extends BlockEntity {

    private double temperature = 100.0; // Default

    public VariableHeatBlockEntity(BlockPos pos, BlockState state) {
        super(DebugRegistry.VARIABLE_HEAT_BLOCK_ENTITY.get(), pos, state);
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("Temperature", temperature);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Temperature")) {
            temperature = tag.getDouble("Temperature");
        }
    }
}
