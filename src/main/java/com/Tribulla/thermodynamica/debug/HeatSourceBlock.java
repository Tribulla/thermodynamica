package com.Tribulla.thermodynamica.debug;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.HeatTier;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A debug heat source block that emits a fixed temperature corresponding to its
 * tier.
 * One block is registered per tier (11 total). Creative-only.
 *
 * <p>
 * When placed, it registers its position as thermally active and sets the
 * simulated temperature to its tier's Celsius value.
 * </p>
 */
public class HeatSourceBlock extends Block {

    private final HeatTier tier;

    public HeatSourceBlock(HeatTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public HeatTier getTier() {
        return tier;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && level instanceof ServerLevel) {
            HeatSimulationManager sim = Thermodynamica.getInstance().getSimulationManager();
            if (sim != null) {
                double celsius = HeatAPI.get().getTierCelsius(tier);
                double biomeOffset = HeatAPI.get().getBiomeOffset(level, pos);
                sim.setTemperature(level, pos, celsius + biomeOffset);
                sim.markActive(level, pos);
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            HeatSimulationManager sim = Thermodynamica.getInstance().getSimulationManager();
            if (sim != null) {
                sim.markInactive(level, pos);
            }
        }
    }
}
