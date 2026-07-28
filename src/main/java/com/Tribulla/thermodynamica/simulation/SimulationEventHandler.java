package com.Tribulla.thermodynamica.simulation;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.HeatAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = Thermodynamica.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SimulationEventHandler {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;

        Thermodynamica instance = Thermodynamica.getInstance();
        if (instance == null)
            return;
        if (instance.getSimulationManager() != null) {
            instance.getSimulationManager().tick();
        }
        if (instance.getFluidSimulationManager() != null) {
            instance.getFluidSimulationManager().tick();
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level &&
                event.getChunk() instanceof LevelChunk chunk) {
            Thermodynamica instance = Thermodynamica.getInstance();
            if (instance == null)
                return;
            if (instance.getSimulationManager() != null) {
                instance.getSimulationManager().onChunkLoad(level, chunk);
            }
            if (instance.getFluidSimulationManager() != null) {
                instance.getFluidSimulationManager().onChunkLoad(level, chunk);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level &&
                event.getChunk() instanceof LevelChunk chunk) {
            Thermodynamica instance = Thermodynamica.getInstance();
            if (instance == null)
                return;
            if (instance.getSimulationManager() != null) {
                instance.getSimulationManager().onChunkUnload(level, chunk);
            }
            if (instance.getFluidSimulationManager() != null) {
                instance.getFluidSimulationManager().onChunkUnload(level, chunk);
            }
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;

        Thermodynamica instance = Thermodynamica.getInstance();
        if (instance == null || instance.getSimulationManager() == null)
            return;

        BlockPos pos = event.getPos();
        BlockState state = event.getPlacedBlock();
        instance.getSimulationManager().onBlockChanged(level, pos);
        if (instance.getFluidSimulationManager() != null) {
            instance.getFluidSimulationManager().onBlockChanged(level, pos);
        }
        checkAndRegisterSource(level, pos, state, instance);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;

        Thermodynamica instance = Thermodynamica.getInstance();
        if (instance == null || instance.getSimulationManager() == null)
            return;

        BlockPos pos = event.getPos();
        instance.getSimulationManager().onBlockChanged(level, pos);
        instance.getSimulationManager().markInactive(level, pos);
        if (instance.getFluidSimulationManager() != null) {
            instance.getFluidSimulationManager().onBlockChanged(level, pos);
        }
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;

        Thermodynamica instance = Thermodynamica.getInstance();
        if (instance == null || instance.getSimulationManager() == null)
            return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        checkAndRegisterSource(level, pos, state, instance);
    }

    private static void checkAndRegisterSource(ServerLevel level, BlockPos pos,
            BlockState state, Thermodynamica instance) {
        // Skip fluid blocks entirely — lava pools underground are the #1 source of
        // simulation lag and fluid heat is not important for gameplay.
        if (!state.getFluidState().isEmpty())
            return;

        HeatSimulationManager sim = instance.getSimulationManager();
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId == null)
            return;

        double ambientTemp = instance.getConfigManager().getSettings().getAmbientTemperature();
        double blockTemp = HeatAPI.get().getBaseCelsiusForState(blockId, state);

        if (Math.abs(blockTemp - ambientTemp) > instance.getConfigManager().getSettings().getDeltaThreshold()) {
            sim.markActive(level, pos);
        } else {
            sim.markInactive(level, pos);
        }
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            Thermodynamica instance = Thermodynamica.getInstance();
            if (instance == null)
                return;
            if (instance.getSimulationManager() != null) {
                HeatSavedData data = level.getDataStorage().computeIfAbsent(
                        (tag) -> HeatSavedData.load(tag, instance.getSimulationManager()),
                        () -> new HeatSavedData(instance.getSimulationManager()),
                        "thermodynamica_heat");
                instance.getSimulationManager().setSavedData(data);
            }
            if (instance.getFluidSimulationManager() != null) {
                FluidSavedData fluidData = level.getDataStorage().computeIfAbsent(
                        (tag) -> FluidSavedData.load(tag, instance.getFluidSimulationManager()),
                        () -> new FluidSavedData(instance.getFluidSimulationManager()),
                        "thermodynamica_fluid");
                instance.getFluidSimulationManager().setSavedData(fluidData);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            Thermodynamica instance = Thermodynamica.getInstance();
            if (instance == null)
                return;
            if (instance.getSimulationManager() != null) {
                HeatSavedData data = instance.getSimulationManager().getSavedData();
                if (data != null) {
                    data.setDirty();
                }
            }
            if (instance.getFluidSimulationManager() != null) {
                FluidSavedData data = instance.getFluidSimulationManager().getSavedData();
                if (data != null) {
                    data.setDirty();
                }
            }
        }
    }
}
