package com.Tribulla.thermodynamica.config;

import com.Tribulla.thermodynamica.Thermodynamica;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class BiomeConfig implements ConfigSection {

    private final Map<String, Double> categoryOffsets = new HashMap<>();
    private final Map<ResourceLocation, Double> biomeOverrides = new HashMap<>();
    private final Map<ResourceLocation, String> biomeCategories = new HashMap<>();
    private double defaultOffset = 0.0;

    public BiomeConfig() {
        categoryOffsets.put("cold", -30.0);
        categoryOffsets.put("temperate", 0.0);
        categoryOffsets.put("hot", 15.0);
    }

    @Override
    public void load(JsonObject json) {
        if (json.has("default_offset")) {
            defaultOffset = json.get("default_offset").getAsDouble();
        }

        if (json.has("categories")) {
            JsonObject cats = json.getAsJsonObject("categories");
            for (Map.Entry<String, JsonElement> entry : cats.entrySet()) {
                if (entry.getKey().startsWith("_"))
                    continue;
                categoryOffsets.put(entry.getKey(), entry.getValue().getAsDouble());
            }
        }

        if (json.has("biome_category_assignments")) {
            JsonObject assignments = json.getAsJsonObject("biome_category_assignments");
            for (Map.Entry<String, JsonElement> entry : assignments.entrySet()) {
                if (entry.getKey().startsWith("_"))
                    continue;
                biomeCategories.put(new ResourceLocation(entry.getKey()), entry.getValue().getAsString());
            }
        }

        if (json.has("biome_overrides")) {
            JsonObject overrides = json.getAsJsonObject("biome_overrides");
            for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
                if (entry.getKey().startsWith("_"))
                    continue;
                biomeOverrides.put(new ResourceLocation(entry.getKey()), entry.getValue().getAsDouble());
            }
        }
    }

    @Override
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("_comment",
                "Biome temperature offsets. actual_celsius = biome_baseline + tier_celsius. " +
                        "Use categories for broad biome groups, or biome_overrides for individual biomes.");

        json.addProperty("default_offset", defaultOffset);

        JsonObject cats = new JsonObject();
        cats.addProperty("cold", -30.0);
        cats.addProperty("temperate", 0.0);
        cats.addProperty("hot", 15.0);
        json.add("categories", cats);

        JsonObject assignments = new JsonObject();
        assignments.addProperty("minecraft:snowy_plains", "cold");
        assignments.addProperty("minecraft:snowy_taiga", "cold");
        assignments.addProperty("minecraft:frozen_river", "cold");
        assignments.addProperty("minecraft:ice_spikes", "cold");
        assignments.addProperty("minecraft:frozen_ocean", "cold");
        assignments.addProperty("minecraft:deep_frozen_ocean", "cold");
        assignments.addProperty("minecraft:frozen_peaks", "cold");
        assignments.addProperty("minecraft:grove", "cold");
        assignments.addProperty("minecraft:jagged_peaks", "cold");
        assignments.addProperty("minecraft:snowy_slopes", "cold");
        assignments.addProperty("minecraft:snowy_beach", "cold");

        assignments.addProperty("minecraft:plains", "temperate");
        assignments.addProperty("minecraft:forest", "temperate");
        assignments.addProperty("minecraft:birch_forest", "temperate");
        assignments.addProperty("minecraft:dark_forest", "temperate");
        assignments.addProperty("minecraft:swamp", "temperate");
        assignments.addProperty("minecraft:river", "temperate");
        assignments.addProperty("minecraft:beach", "temperate");
        assignments.addProperty("minecraft:ocean", "temperate");
        assignments.addProperty("minecraft:taiga", "temperate");
        assignments.addProperty("minecraft:old_growth_spruce_taiga", "temperate");
        assignments.addProperty("minecraft:old_growth_pine_taiga", "temperate");
        assignments.addProperty("minecraft:meadow", "temperate");
        assignments.addProperty("minecraft:flower_forest", "temperate");
        assignments.addProperty("minecraft:sunflower_plains", "temperate");
        assignments.addProperty("minecraft:mushroom_fields", "temperate");
        assignments.addProperty("minecraft:stony_shore", "temperate");
        assignments.addProperty("minecraft:windswept_hills", "temperate");
        assignments.addProperty("minecraft:windswept_forest", "temperate");
        assignments.addProperty("minecraft:windswept_gravelly_hills", "temperate");
        assignments.addProperty("minecraft:cherry_grove", "temperate");

        assignments.addProperty("minecraft:desert", "hot");
        assignments.addProperty("minecraft:badlands", "hot");
        assignments.addProperty("minecraft:eroded_badlands", "hot");
        assignments.addProperty("minecraft:wooded_badlands", "hot");
        assignments.addProperty("minecraft:jungle", "hot");
        assignments.addProperty("minecraft:bamboo_jungle", "hot");
        assignments.addProperty("minecraft:sparse_jungle", "hot");
        assignments.addProperty("minecraft:savanna", "hot");
        assignments.addProperty("minecraft:savanna_plateau", "hot");
        assignments.addProperty("minecraft:windswept_savanna", "hot");
        assignments.addProperty("minecraft:warm_ocean", "hot");
        assignments.addProperty("minecraft:lukewarm_ocean", "hot");
        assignments.addProperty("minecraft:mangrove_swamp", "hot");

        assignments.addProperty("minecraft:nether_wastes", "hot");
        assignments.addProperty("minecraft:soul_sand_valley", "hot");
        assignments.addProperty("minecraft:crimson_forest", "hot");
        assignments.addProperty("minecraft:warped_forest", "hot");
        assignments.addProperty("minecraft:basalt_deltas", "hot");
        assignments.addProperty("minecraft:the_end", "cold");
        assignments.addProperty("minecraft:end_highlands", "cold");
        assignments.addProperty("minecraft:end_midlands", "cold");
        assignments.addProperty("minecraft:end_barrens", "cold");
        assignments.addProperty("minecraft:small_end_islands", "cold");

        json.add("biome_category_assignments", assignments);

        JsonObject overrides = new JsonObject();
        overrides.addProperty("_comment", "Per-biome Celsius offsets. These override category offsets.");
        json.add("biome_overrides", overrides);

        return json;
    }

    public double getOffset(Holder<Biome> biomeHolder) {
        if (biomeHolder.unwrapKey().isPresent()) {
            ResourceLocation biomeId = biomeHolder.unwrapKey().get().location();

            Double override = biomeOverrides.get(biomeId);
            if (override != null)
                return override;

            String category = biomeCategories.get(biomeId);
            if (category != null) {
                Double catOffset = categoryOffsets.get(category);
                if (catOffset != null)
                    return catOffset;
            }

            return guessOffsetFromVanillaTemp(biomeHolder);
        }
        return defaultOffset;
    }

    private double guessOffsetFromVanillaTemp(Holder<Biome> biomeHolder) {
        float vanillaTemp = biomeHolder.value().getBaseTemperature();
        if (vanillaTemp < 0.2f) {
            Double coldOffset = categoryOffsets.get("cold");
            return coldOffset != null ? coldOffset : -30.0;
        } else if (vanillaTemp > 0.8f) {
            Double hotOffset = categoryOffsets.get("hot");
            return hotOffset != null ? hotOffset : 15.0;
        }
        return defaultOffset;
    }

    public Map<String, Double> getCategoryOffsets() {
        return categoryOffsets;
    }

    public Map<ResourceLocation, Double> getBiomeOverrides() {
        return biomeOverrides;
    }
}
