package com.Tribulla.thermodynamica.debug;

import com.Tribulla.thermodynamica.api.HeatAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class VariableHeatBlock extends Block implements EntityBlock {

    public VariableHeatBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VariableHeatBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && level instanceof ServerLevel) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof VariableHeatBlockEntity vhe) {
                HeatAPI.get().setTemperature(level, pos, vhe.getTemperature());
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide) {
                HeatAPI.get().setTemperature(level, pos, HeatAPI.get().getAmbientTemperature());
            }
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof VariableHeatBlockEntity vhe) {
            double current = vhe.getTemperature();
            double newTemp = player.isShiftKeyDown() ? current - 50.0 : current + 50.0;
            vhe.setTemperature(newTemp);
            HeatAPI.get().setTemperature(level, pos, newTemp);
            
            player.displayClientMessage(Component.literal("§eTarget Temperature: §b" + newTemp + " °C"), true);
        }
        
        return InteractionResult.CONSUME;
    }
}
