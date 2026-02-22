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
            case POS4:
            case POS3:
            case POS2:
            case POS1:
            case ZERO:
            case NEG1:
            case NEG2:
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
