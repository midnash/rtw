package com.midna.realtimeworld.client;

import com.midna.realtimeworld.config.RealTimeWorldConfig;
import me.shedaniel.clothconfig2.api.*;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class RealTimeWorldConfigScreen {

        public static Screen create(Screen parent) {

                RealTimeWorldConfig config = RealTimeWorldConfig.get();

                ConfigBuilder builder = ConfigBuilder.create()
                                .setParentScreen(parent)
                                .setTitle(Text.literal("RealTimeWorld Config"));

                builder.setSavingRunnable(RealTimeWorldConfig::save);

                ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
                ConfigEntryBuilder entryBuilder = builder.entryBuilder();

                general.addEntry(entryBuilder.startBooleanToggle(
                                Text.literal("Lock Time"),
                                config.lockTime)
                                .setSaveConsumer(val -> config.lockTime = val)
                                .build());

                general.addEntry(entryBuilder.startBooleanToggle(
                                Text.literal("Sync Weather"),
                                config.syncWeather)
                                .setSaveConsumer(val -> config.syncWeather = val)
                                .build());

                general.addEntry(entryBuilder.startBooleanToggle(
                                Text.literal("Use Real Weather API"),
                                config.useRealWeather)
                                .setSaveConsumer(val -> config.useRealWeather = val)
                                .build());

                general.addEntry(entryBuilder.startDoubleField(
                                Text.literal("Weather Latitude"),
                                config.weatherLatitude)
                                .setMin(-90.0)
                                .setMax(90.0)
                                .setSaveConsumer(val -> config.weatherLatitude = val)
                                .build());

                general.addEntry(entryBuilder.startDoubleField(
                                Text.literal("Weather Longitude"),
                                config.weatherLongitude)
                                .setMin(-180.0)
                                .setMax(180.0)
                                .setSaveConsumer(val -> config.weatherLongitude = val)
                                .build());

                general.addEntry(entryBuilder.startIntField(
                                Text.literal("Weather Refresh Seconds"),
                                config.realWeatherRefreshSeconds)
                                .setMin(60)
                                .setMax(3600)
                                .setSaveConsumer(val -> config.realWeatherRefreshSeconds = val)
                                .build());

                general.addEntry(entryBuilder.startBooleanToggle(
                                Text.literal("Sync Light"),
                                config.syncLight)
                                .setSaveConsumer(val -> config.syncLight = val)
                                .build());

                general.addEntry(entryBuilder.startBooleanToggle(
                                Text.literal("Log To Minecraft Chat"),
                                config.logToMCChat)
                                .setSaveConsumer(val -> config.logToMCChat = val)
                                .build());

                general.addEntry(entryBuilder.startDoubleField(
                                Text.literal("Daytime Rain Chance (0-1)"),
                                config.daytimeRainChance)
                                .setMin(0.0)
                                .setMax(1.0)
                                .setSaveConsumer(val -> config.daytimeRainChance = val)
                                .build());

                general.addEntry(entryBuilder.startDoubleField(
                                Text.literal("Daytime Thunder Chance (0-1)"),
                                config.daytimeThunderChance)
                                .setMin(0.0)
                                .setMax(1.0)
                                .setSaveConsumer(val -> config.daytimeThunderChance = val)
                                .build());

                return builder.build();
        }
}