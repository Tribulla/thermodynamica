package com.Tribulla.thermodynamica.resource;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.Tribulla.thermodynamica.config.ThermalPropertiesRegistry;
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
        // Since we don't have a clearSource for thermal properties yet, we'll just
        // overwrite
        // OR we should ideally add a way to clear them if they came from data packs.

        for (Map.Entry<ResourceLocation, JsonElement> entry : object.entrySet()) {
            ResourceLocation fileLocation = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();

                if (json.has("blocks")) {
                    JsonObject blocks = json.getAsJsonObject("blocks");
                    for (Map.Entry<String, JsonElement> blockEntry : blocks.entrySet()) {
                        ResourceLocation blockId = new ResourceLocation(blockEntry.getKey());
                        registry.registerOverride(blockId, parseProps(blockEntry.getValue().getAsJsonObject()));
                    }
                }

                // Add support for direct block files if preferred:
                // e.g. "thermodynamica:thermal_properties/minecraft/cobblestone.json"
                if (json.has("conductivity") || json.has("transfer_rate")) {
                    registry.registerOverride(fileLocation, parseProps(json));
                }

            } catch (Exception e) {
                Thermodynamica.LOGGER.error("Failed to load thermal properties file {}: {}", fileLocation,
                        e.getMessage());
            }
        }
        Thermodynamica.LOGGER.info("Loaded thermal properties from data packs");
    }

    private ThermalProperties parseProps(JsonObject obj) {
        double conductivity = obj.has("conductivity") ? obj.get("conductivity").getAsDouble()
                : ThermalProperties.DEFAULT_CONDUCTIVITY;
        double transferRate = obj.has("transfer_rate") ? obj.get("transfer_rate").getAsDouble()
                : ThermalProperties.DEFAULT_TRANSFER_RATE;
        double dissipationRate = obj.has("dissipation_rate") ? obj.get("dissipation_rate").getAsDouble()
                : ThermalProperties.DEFAULT_DISSIPATION_RATE;
        return new ThermalProperties(conductivity, transferRate, dissipationRate);
    }
}
