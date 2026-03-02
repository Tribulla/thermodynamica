package com.Tribulla.thermodynamica;

import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.HeatTier;
import com.Tribulla.thermodynamica.api.impl.HeatAPIImpl;
import com.Tribulla.thermodynamica.api.targeting.HeatTargetingInternal;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.debug.DebugRegistry;
import com.Tribulla.thermodynamica.network.HeatNetwork;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import com.Tribulla.thermodynamica.resource.HeatTierResourceLoader;
import com.Tribulla.thermodynamica.resource.ThermalPropertyResourceLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(Thermodynamica.MODID)
public class Thermodynamica {
    public static final String MODID = "thermodynamica";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    private static Thermodynamica instance;

    private final HeatConfigManager configManager;
    private final HeatAPIImpl heatApi;
    private HeatSimulationManager simulationManager;

    public Thermodynamica() {
        instance = this;
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        this.configManager = new HeatConfigManager();
        this.heatApi = new HeatAPIImpl(configManager);
        HeatAPI.setInstance(heatApi);

        DebugRegistry.register(modBus);
        HeatNetwork.register();

        modBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("Thermodynamica heat library initialized");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            configManager.loadAll();
            LOGGER.info("Thermodynamica configuration loaded");
        });
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Create the simulation manager early, before levels load.
        // LevelEvent.Load fires between ServerAboutToStartEvent and ServerStartingEvent,
        // and needs the manager to exist so saved data can be loaded.
        simulationManager = new HeatSimulationManager(event.getServer(), configManager);
        simulationManager.start();
        heatApi.setSimulationManager(simulationManager);
        
        // Initialize targeting API with source provider
        HeatTargetingInternal.setSourceProvider((level, minCelsius) -> 
            simulationManager.getActiveHeatSources(level.dimension().location(), minCelsius));
        
        LOGGER.info("Thermodynamica heat simulation engine started");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        for (HeatTier tier : HeatTier.values()) {
            var blockRO = DebugRegistry.HEAT_SOURCE_BLOCKS.get(tier);
            if (blockRO != null && blockRO.isPresent()) {
                Block block = blockRO.get();
                ResourceLocation blockId = block.builtInRegistryHolder().key().location();
                heatApi.registerBlockTier(blockId, tier);
                LOGGER.debug("Registered debug heat source {} → {}", blockId, tier);
            }
        }

        // Safety net: if LevelEvent.Load didn't attach saved data (e.g. ordering edge case),
        // load it now since the overworld is definitely available at this point.
        if (simulationManager.getSavedData() == null) {
            net.minecraft.server.level.ServerLevel overworld = event.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (overworld != null) {
                com.Tribulla.thermodynamica.simulation.HeatSavedData data = overworld.getDataStorage().computeIfAbsent(
                        (tag) -> com.Tribulla.thermodynamica.simulation.HeatSavedData.load(tag, simulationManager),
                        () -> new com.Tribulla.thermodynamica.simulation.HeatSavedData(simulationManager),
                        "thermodynamica_heat");
                simulationManager.setSavedData(data);
                LOGGER.info("Thermodynamica saved data loaded (fallback path)");
            }
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (simulationManager != null) {
            // Mark data dirty so it will be written during the upcoming saveAllChunks().
            // Only stop the thread pool here; keep simulation data intact for the save.
            com.Tribulla.thermodynamica.simulation.HeatSavedData data = simulationManager.getSavedData();
            if (data != null) {
                data.setDirty();
            }
            simulationManager.stopProcessing();
        }
        LOGGER.info("Thermodynamica heat simulation engine stopping");
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        if (simulationManager != null) {
            simulationManager.stop();
            simulationManager = null;
        }
        LOGGER.info("Thermodynamica heat simulation engine stopped");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ThermodynamicaCommand.register(event.getDispatcher());
        LOGGER.info("Thermodynamica commands registered");
    }

    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new HeatTierResourceLoader(heatApi.getTierRegistry()));
        event.addListener(new ThermalPropertyResourceLoader(configManager.getThermalPropertiesRegistry()));
        LOGGER.info("Thermodynamica resource reload listeners registered");
    }

    public static Thermodynamica getInstance() {
        return instance;
    }

    public HeatConfigManager getConfigManager() {
        return configManager;
    }

    public HeatSimulationManager getSimulationManager() {
        return simulationManager;
    }
}
