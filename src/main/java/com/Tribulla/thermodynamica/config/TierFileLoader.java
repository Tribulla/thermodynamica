package com.Tribulla.thermodynamica.config;

import com.Tribulla.thermodynamica.api.HeatTier;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class TierFileLoader {

    public static List<String> load(HeatTier tier, JsonObject json) {
        List<String> blocks = new ArrayList<>();

        if (json.has("blocks")) {
            JsonArray arr = json.getAsJsonArray("blocks");
            for (JsonElement el : arr) {
                blocks.add(el.getAsString());
            }
        }

        if (json.has("tags")) {
            JsonArray arr = json.getAsJsonArray("tags");
            for (JsonElement el : arr) {
                String tag = el.getAsString();
                if (!tag.startsWith("#"))
                    tag = "#" + tag;
                blocks.add(tag);
            }
        }

        return blocks;
    }

    public static JsonObject createTemplate(HeatTier tier) {
        JsonObject json = new JsonObject();
        json.addProperty("_comment",
                "Blocks assigned to heat tier " + tier.name() + ". " +
                        "Add block registry names (e.g. \"minecraft:lava\") or tags prefixed with #. " +
                        "Mods and data packs may add or override these files.");

        JsonArray blocks = new JsonArray();
        JsonArray tags = new JsonArray();

        switch (tier) {
            case POS5:
                // Extreme heat: lava
                blocks.add("minecraft:lava");
                blocks.add("minecraft:lava_cauldron");
                break;
            case POS4:
                // Very hot: active fire
                blocks.add("minecraft:fire");
                blocks.add("minecraft:soul_fire");
                tags.add("#minecraft:fire");
                break;
            case POS3:
                // Hot: heated blocks, magma
                blocks.add("minecraft:magma_block");
                blocks.add("minecraft:campfire");
                blocks.add("minecraft:soul_campfire");
                break;
            case POS2:
                // Warm: furnaces, smokers, torches
                blocks.add("minecraft:furnace");
                blocks.add("minecraft:blast_furnace");
                blocks.add("minecraft:smoker");
                blocks.add("minecraft:torch");
                blocks.add("minecraft:wall_torch");
                blocks.add("minecraft:soul_torch");
                blocks.add("minecraft:soul_wall_torch");
                blocks.add("minecraft:lantern");
                blocks.add("minecraft:soul_lantern");
                blocks.add("minecraft:jack_o_lantern");
                blocks.add("minecraft:shroomlight");
                blocks.add("minecraft:glowstone");
                blocks.add("minecraft:redstone_lamp");
                break;
            case POS1:
                // Ambient (room temperature) - no specific blocks needed
                break;
            case ZERO:
                // Freezing
                blocks.add("minecraft:ice");
                break;
            case NEG1:
                // Chilly
                blocks.add("minecraft:packed_ice");
                blocks.add("minecraft:snow_block");
                blocks.add("minecraft:snow");
                blocks.add("minecraft:powder_snow");
                break;
            case NEG2:
                // Cool
                blocks.add("minecraft:blue_ice");
                break;
            case NEG3:
            case NEG4:
            case NEG5:
                break;
        }

        json.add("blocks", blocks);
        json.add("tags", tags);

        return json;
    }
}
