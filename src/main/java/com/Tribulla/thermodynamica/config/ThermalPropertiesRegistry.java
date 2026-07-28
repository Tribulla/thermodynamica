package com.Tribulla.thermodynamica.config;

import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public class ThermalPropertiesRegistry {

    private final Map<ResourceLocation, ThermalProperties> blockProperties = new HashMap<>();
    private final Map<ResourceLocation, ThermalProperties> tagProperties = new HashMap<>();

    private final Map<ResourceLocation, ThermalProperties> datapackBlocks = new HashMap<>();
    private final Map<ResourceLocation, ThermalProperties> datapackTags = new HashMap<>();

    private final Map<ResourceLocation, ThermalProperties> runtimeOverrides = new HashMap<>();

    public void load(JsonObject json) {
        blockProperties.clear();
        tagProperties.clear();
        loadBlocksInto(json, blockProperties);
        loadTagsInto(json, tagProperties);
    }

    public void clearDatapackEntries() {
        datapackBlocks.clear();
        datapackTags.clear();
    }

    public void loadDatapack(JsonObject json) {
        loadBlocksInto(json, datapackBlocks);
        loadTagsInto(json, datapackTags);
    }

    private static void loadBlocksInto(JsonObject json, Map<ResourceLocation, ThermalProperties> target) {
        if (!json.has("blocks"))
            return;
        JsonObject blocks = json.getAsJsonObject("blocks");
        for (Map.Entry<String, JsonElement> entry : blocks.entrySet()) {
            if (entry.getKey().startsWith("_"))
                continue;
            ResourceLocation rl = ResourceLocation.tryParse(entry.getKey());
            if (rl != null) {
                target.put(rl, parseProps(entry.getValue().getAsJsonObject()));
            }
        }
    }

    private static void loadTagsInto(JsonObject json, Map<ResourceLocation, ThermalProperties> target) {
        if (!json.has("tags"))
            return;
        JsonObject tags = json.getAsJsonObject("tags");
        for (Map.Entry<String, JsonElement> entry : tags.entrySet()) {
            if (entry.getKey().startsWith("_"))
                continue;
            String key = entry.getKey();
            if (key.startsWith("#"))
                key = key.substring(1);
            ResourceLocation rl = ResourceLocation.tryParse(key);
            if (rl != null) {
                target.put(rl, parseProps(entry.getValue().getAsJsonObject()));
            }
        }
    }

    public static ThermalProperties parseProps(JsonObject obj) {
        double conductivity = obj.has("conductivity") ? obj.get("conductivity").getAsDouble()
                : ThermalProperties.DEFAULT_CONDUCTIVITY;
        double heatCapacity = obj.has("heat_capacity") ? obj.get("heat_capacity").getAsDouble()
                : ThermalProperties.DEFAULT_HEAT_CAPACITY;
        double dissipationRate = obj.has("dissipation_rate") ? obj.get("dissipation_rate").getAsDouble()
                : ThermalProperties.DEFAULT_DISSIPATION_RATE;
        java.util.OptionalDouble temperature = obj.has("temperature")
                ? java.util.OptionalDouble.of(obj.get("temperature").getAsDouble())
                : java.util.OptionalDouble.empty();
        return new ThermalProperties(conductivity, heatCapacity, dissipationRate, temperature);
    }

    /**
     * Lookup order: API override → datapack block → config block → datapack tag → config tag.
     */
    @Nullable
    public ThermalProperties get(ResourceLocation block) {
        ThermalProperties props = runtimeOverrides.get(block);
        if (props != null)
            return props;

        props = datapackBlocks.get(block);
        if (props != null)
            return props;

        props = blockProperties.get(block);
        if (props != null)
            return props;

        props = resolveTagProperties(block);
        return props;
    }

    @Nullable
    private ThermalProperties resolveTagProperties(ResourceLocation block) {
        if (datapackTags.isEmpty() && tagProperties.isEmpty())
            return null;

        Block b = ForgeRegistries.BLOCKS.getValue(block);
        if (b == null)
            return null;

        var holder = b.builtInRegistryHolder();

        for (Map.Entry<ResourceLocation, ThermalProperties> entry : datapackTags.entrySet()) {
            if (holder.is(TagKey.create(Registries.BLOCK, entry.getKey())))
                return entry.getValue();
        }
        for (Map.Entry<ResourceLocation, ThermalProperties> entry : tagProperties.entrySet()) {
            if (holder.is(TagKey.create(Registries.BLOCK, entry.getKey())))
                return entry.getValue();
        }
        return null;
    }

    public void registerOverride(ResourceLocation block, ThermalProperties props) {
        runtimeOverrides.put(block, props);
    }

    public void registerDatapackBlock(ResourceLocation block, ThermalProperties props) {
        datapackBlocks.put(block, props);
    }

    public int size() {
        return blockProperties.size() + tagProperties.size()
                + datapackBlocks.size() + datapackTags.size()
                + runtimeOverrides.size();
    }

    public int getDatapackSize() {
        return datapackBlocks.size() + datapackTags.size();
    }

    public JsonObject toDefaultJson() {
        JsonObject root = new JsonObject();
        JsonObject blocks = new JsonObject();
        JsonObject tags = new JsonObject();
        root.add("blocks", blocks);
        root.add("tags", tags);
        root.addProperty("_comment",
                "Optional overrides. Built-in vanilla/create properties ship in the mod jar under "
                        + "data/thermodynamica/thermal_properties/. Entries here override those defaults.");
        return root;
    }
}
