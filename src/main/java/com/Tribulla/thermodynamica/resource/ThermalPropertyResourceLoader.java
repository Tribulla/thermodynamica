package com.Tribulla.thermodynamica.resource;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.config.ThermalPropertiesRegistry;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;

public class ThermalPropertyResourceLoader extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private final ThermalPropertiesRegistry registry;

    public ThermalPropertyResourceLoader(ThermalPropertiesRegistry registry) {
        super(GSON, "thermal_properties");
        this.registry = registry;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> object, ResourceManager resourceManager,
            ProfilerFiller profiler) {
        registry.clearDatapackEntries();

        int files = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : object.entrySet()) {
            ResourceLocation fileLocation = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();

                if (json.has("blocks") || json.has("tags")) {
                    registry.loadDatapack(json);
                    files++;
                }

                if (!json.has("blocks") && !json.has("tags")
                        && (json.has("conductivity") || json.has("heat_capacity")
                                || json.has("dissipation_rate") || json.has("temperature"))) {
                    registry.registerDatapackBlock(fileLocation, ThermalPropertiesRegistry.parseProps(json));
                    files++;
                }

            } catch (Exception e) {
                Thermodynamica.LOGGER.error("Failed to load thermal properties file {}: {}", fileLocation,
                        e.getMessage());
            }
        }

        Thermodynamica instance = Thermodynamica.getInstance();
        if (instance != null) {
            HeatSimulationManager sim = instance.getSimulationManager();
            if (sim != null) {
                sim.invalidateAllThermalCaches();
            }
        }

        Thermodynamica.LOGGER.info("Loaded {} thermal property datapack file(s) ({} entries)",
                files, registry.getDatapackSize());
    }
}
