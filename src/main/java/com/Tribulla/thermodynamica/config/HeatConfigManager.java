package com.Tribulla.thermodynamica.config;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.ThermalProperties;
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

            loadOrCreate("settings.json", settings);
            loadOrCreate("biome_config.json", biomeConfig);

            loadThermalProperties();

            Thermodynamica.LOGGER.info("Loaded {} thermal property entries",
                    thermalPropertiesRegistry.size());

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
