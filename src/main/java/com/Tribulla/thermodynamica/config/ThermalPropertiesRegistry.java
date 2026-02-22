package com.Tribulla.thermodynamica.config;

import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public class ThermalPropertiesRegistry {

    private final Map<ResourceLocation, ThermalProperties> blockProperties = new HashMap<>();
    private final Map<String, ThermalProperties> tagProperties = new HashMap<>();
    private final Map<ResourceLocation, ThermalProperties> runtimeOverrides = new HashMap<>();

    public void load(JsonObject json) {
        if (json.has("blocks")) {
            JsonObject blocks = json.getAsJsonObject("blocks");
            for (Map.Entry<String, JsonElement> entry : blocks.entrySet()) {
                if (entry.getKey().startsWith("_"))
                    continue;
                ResourceLocation rl = new ResourceLocation(entry.getKey());
                blockProperties.put(rl, parseProps(entry.getValue().getAsJsonObject()));
            }
        }
        if (json.has("tags")) {
            JsonObject tags = json.getAsJsonObject("tags");
            for (Map.Entry<String, JsonElement> entry : tags.entrySet()) {
                if (entry.getKey().startsWith("_"))
                    continue;
                tagProperties.put(entry.getKey(), parseProps(entry.getValue().getAsJsonObject()));
            }
        }
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

    @Nullable
    public ThermalProperties get(ResourceLocation block) {
        ThermalProperties props = runtimeOverrides.get(block);
        if (props != null)
            return props;
        props = blockProperties.get(block);
        if (props != null)
            return props;
        return null;
    }

    public void registerOverride(ResourceLocation block, ThermalProperties props) {
        runtimeOverrides.put(block, props);
    }

    public int size() {
        return blockProperties.size() + tagProperties.size() + runtimeOverrides.size();
    }

    public JsonObject toDefaultJson() {
        JsonObject root = new JsonObject();
        JsonObject blocks = new JsonObject();
        JsonObject tags = new JsonObject();
        root.add("blocks", blocks);
        root.add("tags", tags);
        return root;
    }
}
