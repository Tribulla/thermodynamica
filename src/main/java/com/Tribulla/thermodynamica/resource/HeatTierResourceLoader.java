package com.Tribulla.thermodynamica.resource;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.HeatTier;
import com.Tribulla.thermodynamica.api.impl.TierRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;

public class HeatTierResourceLoader extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private final TierRegistry tierRegistry;

    public HeatTierResourceLoader(TierRegistry tierRegistry) {
        super(GSON, "heat_tiers");
        this.tierRegistry = tierRegistry;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> object, ResourceManager resourceManager,
            ProfilerFiller profiler) {
        tierRegistry.clearSource(com.Tribulla.thermodynamica.api.TierResolution.Source.DATA_PACK);

        for (Map.Entry<ResourceLocation, JsonElement> entry : object.entrySet()) {
            ResourceLocation fileLocation = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();
                String tierName = fileLocation.getPath(); // e.g. "pos5"
                HeatTier tier = HeatTier.fromId(tierName);
                if (tier == null) {
                    Thermodynamica.LOGGER.warn("Unknown heat tier in data pack: {}", tierName);
                    continue;
                }

                if (json.has("blocks")) {
                    JsonArray blocks = json.getAsJsonArray("blocks");
                    for (JsonElement el : blocks) {
                        ResourceLocation blockId = new ResourceLocation(el.getAsString());
                        tierRegistry.registerDataPack(blockId, tier, fileLocation.toString());
                    }
                }

                if (json.has("tags")) {
                    JsonArray tags = json.getAsJsonArray("tags");
                    for (JsonElement el : tags) {
                        String tagName = el.getAsString();
                        ResourceLocation tagId = new ResourceLocation(
                                tagName.startsWith("#") ? tagName.substring(1) : tagName);
                        tierRegistry.registerTag(tagId, tier,
                                com.Tribulla.thermodynamica.api.TierResolution.Source.DATA_PACK,
                                fileLocation.toString());
                    }
                }
            } catch (Exception e) {
                Thermodynamica.LOGGER.error("Failed to load heat tier file {}: {}", fileLocation, e.getMessage());
            }
        }
        Thermodynamica.LOGGER.info("Loaded heat tiers from data packs");
    }
}
