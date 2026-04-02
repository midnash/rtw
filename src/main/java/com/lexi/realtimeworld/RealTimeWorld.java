package com.midna.realtimeworld;

import com.midna.realtimeworld.config.RealTimeWorldConfig;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.Random;
import java.util.logging.*;

public class RealTimeWorld implements ModInitializer {

    private static final Logger LOGGER = Logger.getLogger("RealTimeWorld");
    private static final String LOG_NAME = "realtimeworld.log";

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final long MC_DAY_TICKS = 24000L;
    private static final long REAL_SECONDS_PER_DAY = 86400L;

    private final Random random = new Random();

    @Override
    public void onInitialize() {
        setupFileLogger();
        RealTimeWorldConfig.load();
        LOGGER.info("RealTimeWorld initialized");

        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);
    }

    private void setupFileLogger() {
        try {
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir)) Files.createDirectories(logsDir);

            Path logFile = logsDir.resolve(LOG_NAME);

            Handler fileHandler = new FileHandler(logFile.toString(), true);
            fileHandler.setFormatter(new SimpleFormatter());
            LOGGER.addHandler(fileHandler);
            LOGGER.setUseParentHandlers(false);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void onServerTick(MinecraftServer server) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) return;

        RealTimeWorldConfig config = RealTimeWorldConfig.get();

        if (config.lockTime) {
            world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
        }

        syncTime(world);
        syncMoonPhase(world);

        if (config.syncWeather) syncWeather(world, config);
        if (config.syncLight) syncSunriseSunset(world);
    }

    private void syncTime(ServerWorld world) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);

        long seconds = now.toLocalTime().toSecondOfDay();
        long nanos = now.getNano();
        double exactSeconds = seconds + (nanos / 1_000_000_000.0);
        long mcTicks = (long)((exactSeconds / REAL_SECONDS_PER_DAY) * MC_DAY_TICKS);
        mcTicks = (mcTicks + 18000) % MC_DAY_TICKS;

        long fullTime = world.getTimeOfDay();
        long currentDays = fullTime / MC_DAY_TICKS;
        long newFullTime = currentDays * MC_DAY_TICKS + mcTicks;

        world.setTimeOfDay(newFullTime);
    }

    private void syncMoonPhase(ServerWorld world) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate knownNewMoon = LocalDate.of(2000, 1, 6);

        long daysSince = Duration.between(
                knownNewMoon.atStartOfDay(), today.atStartOfDay()
        ).toDays();

        int desiredPhase = (int)((daysSince % 29.53) / 29.53 * 8) % 8;

        long fullTime = world.getTimeOfDay();
        long currentDayCount = fullTime / MC_DAY_TICKS;
        long baseCycleStart = currentDayCount - (currentDayCount % 8);
        long newDayCount = baseCycleStart + desiredPhase;

        world.setTimeOfDay(newDayCount * MC_DAY_TICKS + (fullTime % MC_DAY_TICKS));
    }

    private void syncWeather(ServerWorld world, RealTimeWorldConfig config) {
        int hour = LocalTime.now(ZONE).getHour();
        boolean isNight = (hour >= 19 || hour < 5);

        if (isNight) {
            if (world.isRaining() || world.isThundering()) {
                world.setWeather(12000, 0, false, false);
            }
        } else {
            if (!world.isRaining() && random.nextDouble() < config.daytimeRainChance) {
                boolean thunder = random.nextDouble() < config.daytimeThunderChance;
                world.setWeather(0, 12000, true, thunder);
            }
        }
    }

    private void syncSunriseSunset(ServerWorld world) {
        world.setTimeOfDay(world.getTimeOfDay());
    }
}