package com.Tribulla.thermodynamica.config;

import com.google.gson.JsonObject;

public interface ConfigSection {
    void load(JsonObject json);

    JsonObject toJson();
}
