package com.Tribulla.thermodynamica;

import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.HeatTier;
import com.Tribulla.thermodynamica.api.impl.HeatAPIImpl;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import com.Tribulla.thermodynamica.debug.DebugCreativeTab;
import com.Tribulla.thermodynamica.debug.DebugRegistry;
import com.Tribulla.thermodynamica.debug.HeatSourceBlock;
import com.Tribulla.thermodynamica.network.HeatNetwork;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import com.Tribulla.thermodynamica.resource.HeatTierResourceLoader;
import com.Tribulla.thermodynamica.resource.ThermalPropertyResourceLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
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
        DebugCreativeTab.register(modBus);
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

        simulationManager = new HeatSimulationManager(event.getServer(), configManager);
        simulationManager.start();
        heatApi.setSimulationManager(simulationManager);
        LOGGER.info("Thermodynamica heat simulation engine started");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
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
