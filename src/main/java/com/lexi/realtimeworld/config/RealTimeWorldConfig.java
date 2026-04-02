package com.midna.realtimeworld.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RealTimeWorldConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("realtimeworld.json");

    public boolean syncWeather = true;
    public boolean lockTime = true;
    public boolean syncLight = true;

    public double daytimeRainChance = 0.1;
    public double daytimeThunderChance = 0.05;

    private static RealTimeWorldConfig INSTANCE;

    public static RealTimeWorldConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    public static void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                INSTANCE = GSON.fromJson(Files.readString(CONFIG_PATH), RealTimeWorldConfig.class);
            } else {
                INSTANCE = new RealTimeWorldConfig();
                save();
            }
        } catch (IOException e) {
            e.printStackTrace();
            INSTANCE = new RealTimeWorldConfig();
        }
    }

    public static void save() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}