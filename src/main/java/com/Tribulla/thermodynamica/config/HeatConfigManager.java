package com.Tribulla.thermodynamica.config;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.HeatTier;
import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.Tribulla.thermodynamica.api.TierResolution;
import com.Tribulla.thermodynamica.api.impl.TierRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class HeatConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final TierDefinitions tierDefinitions = new TierDefinitions();
    private final SimulationSettings settings = new SimulationSettings();
    private final BiomeConfig biomeConfig = new BiomeConfig();
    private final ThermalPropertiesRegistry thermalPropertiesRegistry = new ThermalPropertiesRegistry();

    private Path configRoot;

    public HeatConfigManager() {
    }

    public void loadAll() {
        configRoot = resolveConfigRoot();
        try {
            Files.createDirectories(configRoot);
            Files.createDirectories(configRoot.resolve("heat"));
            Files.createDirectories(configRoot.resolve("thermal"));

            loadOrCreate("tier_definitions.json", tierDefinitions);
            loadOrCreate("settings.json", settings);
            loadOrCreate("biome_config.json", biomeConfig);

            for (HeatTier tier : HeatTier.values()) {
                loadTierFile(tier);
            }

            loadThermalProperties();

            Thermodynamica.LOGGER.info("Loaded {} tier definitions, {} thermal property entries",
                    HeatTier.values().length, thermalPropertiesRegistry.size());

        } catch (IOException e) {
            Thermodynamica.LOGGER.error("Failed to load Thermodynamica config", e);
        }
    }

    private Path resolveConfigRoot() {
        Path gameDir = Path.of(System.getProperty("user.dir", "."));
        return gameDir.resolve("config").resolve("thermodynamica");
    }

    private <T extends ConfigSection> void loadOrCreate(String filename, T section) throws IOException {
        Path file = configRoot.resolve(filename);
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                section.load(json);
                Thermodynamica.LOGGER.debug("Loaded config: {}", filename);
            }
        } else {
            JsonObject defaults = section.toJson();
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(defaults, writer);
            }
            Thermodynamica.LOGGER.info("Created default config: {}", filename);
        }
    }

    private void loadTierFile(HeatTier tier) throws IOException {
        String filename = tier.getId() + ".json";
        Path file = configRoot.resolve("heat").resolve(filename);
        JsonObject json;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                json = GSON.fromJson(reader, JsonObject.class);
            }
            Thermodynamica.LOGGER.debug("Loaded tier file: heat/{}", filename);
        } else {
            json = TierFileLoader.createTemplate(tier);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            Thermodynamica.LOGGER.info("Created tier template: heat/{}", filename);
        }

        java.util.List<String> blocks = TierFileLoader.load(tier, json);
        com.Tribulla.thermodynamica.api.HeatAPI api = com.Tribulla.thermodynamica.api.HeatAPI.get();
        for (String blockId : blocks) {
            if (blockId.startsWith("#"))
                continue;
            try {
                api.registerBlockTier(new ResourceLocation(blockId), tier);
                Thermodynamica.LOGGER.debug("Registered {} → {}", blockId, tier);
            } catch (Exception e) {
                Thermodynamica.LOGGER.warn("Failed to register block tier {}: {}", blockId, e.getMessage());
            }
        }
    }

    private void loadThermalProperties() throws IOException {
        Path thermalDir = configRoot.resolve("thermal");
        Path defaultFile = thermalDir.resolve("block_properties.json");
        if (Files.exists(defaultFile)) {
            try (Reader reader = Files.newBufferedReader(defaultFile, StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                thermalPropertiesRegistry.load(json);
            }
        } else {
            JsonObject defaults = thermalPropertiesRegistry.toDefaultJson();
            try (Writer writer = Files.newBufferedWriter(defaultFile, StandardCharsets.UTF_8)) {
                GSON.toJson(defaults, writer);
            }
            Thermodynamica.LOGGER.info("Created default thermal properties: thermal/block_properties.json");
        }
    }

    public TierDefinitions getTierDefinitions() {
        return tierDefinitions;
    }

    public SimulationSettings getSettings() {
        return settings;
    }

    public BiomeConfig getBiomeConfig() {
        return biomeConfig;
    }

    public ThermalPropertiesRegistry getThermalPropertiesRegistry() {
        return thermalPropertiesRegistry;
    }

    public Path getConfigRoot() {
        return configRoot;
    }
}
