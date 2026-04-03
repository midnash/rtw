package com.midna.realtimeworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.midna.realtimeworld.config.RealTimeWorldConfig;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.logging.*;

public class RealTimeWorld implements ModInitializer {

    private static final Logger LOGGER = Logger.getLogger("RealTimeWorld");
    private static final String LOG_NAME = "realtimeworld.log";

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final long MC_DAY_TICKS = 24000L;
    private static final long REAL_SECONDS_PER_DAY = 86400L;
    private static final int WEATHER_MIN_REFRESH_SECONDS = 60;
    private static final int WEATHER_MAX_REFRESH_SECONDS = 3600;
    private static final Duration WEATHER_FALLBACK_LOG_INTERVAL = Duration.ofMinutes(5);

    private final Random random = new Random();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Instant nextWeatherFetchAt = Instant.EPOCH;
    private Instant nextFallbackLogAt = Instant.EPOCH;
    private WeatherState cachedWeather;
    private WeatherState lastAppliedWeather;
    private Integer lastFetchedWeatherCode;
    private boolean weatherFetchErrorLogged;

    @Override
    public void onInitialize() {
        setupFileLogger();
        RealTimeWorldConfig.load();
        LOGGER.info("RealTimeWorld initialized");
        RealTimeWorldConfig config = RealTimeWorldConfig.get();
        LOGGER.info("Weather mode: " + (config.useRealWeather ? "real API" : "random fallback"));
        LOGGER.info("Weather logging to chat: " + config.logToMCChat);

        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);
    }

    private void setupFileLogger() {
        try {
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir))
                Files.createDirectories(logsDir);

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
        if (world == null)
            return;

        RealTimeWorldConfig config = RealTimeWorldConfig.get();

        if (config.lockTime) {
            world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
        }

        syncTime(world);
        syncMoonPhase(world);

        if (config.syncWeather) {
            if (config.useRealWeather) {
                syncRealWeather(server, world, config);
            } else {
                syncRandomWeather(server, world, config);
            }
        }
        if (config.syncLight)
            syncSunriseSunset(world);
    }

    private void syncTime(ServerWorld world) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);

        long seconds = now.toLocalTime().toSecondOfDay();
        long nanos = now.getNano();
        double exactSeconds = seconds + (nanos / 1_000_000_000.0);
        long mcTicks = (long) ((exactSeconds / REAL_SECONDS_PER_DAY) * MC_DAY_TICKS);
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
                knownNewMoon.atStartOfDay(), today.atStartOfDay()).toDays();

        int desiredPhase = (int) ((daysSince % 29.53) / 29.53 * 8) % 8;

        long fullTime = world.getTimeOfDay();
        long currentDayCount = fullTime / MC_DAY_TICKS;
        long baseCycleStart = currentDayCount - (currentDayCount % 8);
        long newDayCount = baseCycleStart + desiredPhase;

        world.setTimeOfDay(newDayCount * MC_DAY_TICKS + (fullTime % MC_DAY_TICKS));
    }

    private void syncRandomWeather(MinecraftServer server, ServerWorld world, RealTimeWorldConfig config) {
        int hour = LocalTime.now(ZONE).getHour();
        boolean isNight = (hour >= 19 || hour < 5);

        if (isNight) {
            applyWeather(server, world, config, new WeatherState(false, false), "random-night-clear");
        } else {
            if (!world.isRaining() && random.nextDouble() < config.daytimeRainChance) {
                boolean thunder = random.nextDouble() < config.daytimeThunderChance;
                applyWeather(server, world, config, new WeatherState(true, thunder), "random-day-roll");
            }
        }
    }

    private void syncRealWeather(MinecraftServer server, ServerWorld world, RealTimeWorldConfig config) {
        Instant now = Instant.now();

        if (cachedWeather != null && now.isBefore(nextWeatherFetchAt)) {
            applyWeather(server, world, config, cachedWeather, "real-api-cached");
            return;
        }

        Optional<WeatherState> latest = fetchRealWeather(config);
        if (latest.isPresent()) {
            cachedWeather = latest.get();
            weatherFetchErrorLogged = false;
            nextFallbackLogAt = Instant.EPOCH;
            int refreshSeconds = Math.max(WEATHER_MIN_REFRESH_SECONDS,
                    Math.min(config.realWeatherRefreshSeconds, WEATHER_MAX_REFRESH_SECONDS));
            nextWeatherFetchAt = now.plus(refreshSeconds, ChronoUnit.SECONDS);
            applyWeather(server, world, config, cachedWeather, "real-api-live");
            return;
        }

        if (cachedWeather != null) {
            applyWeather(server, world, config, cachedWeather, "real-api-cached-fallback");
        } else {
            logFallbackThrottled(server, config, now, "Real weather unavailable, using random fallback");
            syncRandomWeather(server, world, config);
        }
    }

    private Optional<WeatherState> fetchRealWeather(RealTimeWorldConfig config) {
        try {
            String url = String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&current=weather_code",
                    config.weatherLatitude,
                    config.weatherLongitude);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Weather API returned status " + response.statusCode());
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject current = root.getAsJsonObject("current");
            if (current == null || !current.has("weather_code")) {
                throw new IOException("Missing current.weather_code in weather API response");
            }

            int weatherCode = current.get("weather_code").getAsInt();
            if (lastFetchedWeatherCode == null || lastFetchedWeatherCode != weatherCode) {
                LOGGER.info("Fetched real weather code: " + weatherCode);
                lastFetchedWeatherCode = weatherCode;
            }
            return Optional.of(mapWeatherCode(weatherCode));
        } catch (Exception e) {
            if (!weatherFetchErrorLogged) {
                LOGGER.warning("Real weather fetch failed: " + e.getMessage());
                weatherFetchErrorLogged = true;
            }
            return Optional.empty();
        }
    }

    private WeatherState mapWeatherCode(int weatherCode) {
        boolean thunder = (weatherCode == 95 || weatherCode == 96 || weatherCode == 99);
        boolean rain = thunder
                || weatherCode == 51 || weatherCode == 53 || weatherCode == 55
                || weatherCode == 56 || weatherCode == 57
                || weatherCode == 61 || weatherCode == 63 || weatherCode == 65
                || weatherCode == 66 || weatherCode == 67
                || weatherCode == 80 || weatherCode == 81 || weatherCode == 82;

        return new WeatherState(rain, thunder);
    }

    private void applyWeather(
            MinecraftServer server,
            ServerWorld world,
            RealTimeWorldConfig config,
            WeatherState weatherState,
            String source) {
        boolean stateChanged = lastAppliedWeather == null
                || lastAppliedWeather.raining != weatherState.raining
                || lastAppliedWeather.thundering != weatherState.thundering;

        if (!weatherState.raining && !weatherState.thundering) {
            if (world.isRaining() || world.isThundering()) {
                world.setWeather(12000, 0, false, false);
            }
            if (stateChanged) {
                logWeather(server, config, Level.INFO, "Weather -> clear (" + source + ")");
            }
            lastAppliedWeather = weatherState;
            return;
        }

        if (!world.isRaining() || world.isThundering() != weatherState.thundering) {
            world.setWeather(0, 12000, true, weatherState.thundering);
        }

        if (stateChanged) {
            String state = weatherState.thundering ? "thunder" : "rain";
            logWeather(server, config, Level.INFO, "Weather -> " + state + " (" + source + ")");
        }

        lastAppliedWeather = weatherState;
    }

    private void logWeather(MinecraftServer server, RealTimeWorldConfig config, Level level, String message) {
        LOGGER.log(level, message);
        if (config.logToMCChat) {
            server.getPlayerManager().broadcast(Text.literal("[RealTimeWorld] " + message), false);
        }
    }

    private void logFallbackThrottled(
            MinecraftServer server,
            RealTimeWorldConfig config,
            Instant now,
            String message) {
        if (now.isBefore(nextFallbackLogAt)) {
            return;
        }

        nextFallbackLogAt = now.plus(WEATHER_FALLBACK_LOG_INTERVAL);
        logWeather(server, config, Level.WARNING, message);
    }

    private record WeatherState(boolean raining, boolean thundering) {
    }

    private void syncSunriseSunset(ServerWorld world) {
        world.setTimeOfDay(world.getTimeOfDay());
    }
}