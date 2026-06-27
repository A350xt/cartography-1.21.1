package com.liedowncraft.cartography;

import java.nio.file.Path;
import java.util.List;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.config.RendererProfile;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue WEB_ENABLED;
    private static final ModConfigSpec.ConfigValue<String> WEB_BIND_HOST;
    private static final ModConfigSpec.IntValue WEB_PORT;

    private static final ModConfigSpec.ConfigValue<String> RENDER_OUTPUT_ROOT;
    private static final ModConfigSpec.IntValue RENDER_TILE_SIZE;
    private static final ModConfigSpec.IntValue RENDER_MIN_ZOOM;
    private static final ModConfigSpec.IntValue RENDER_MAX_ZOOM;
    private static final ModConfigSpec.IntValue RENDER_PIXELS_PER_BLOCK_MAX_ZOOM;
    private static final ModConfigSpec.IntValue RENDER_METATILE_SIZE;
    private static final ModConfigSpec.ConfigValue<String> RENDERER_CODE_VERSION;
    private static final ModConfigSpec.ConfigValue<String> MATERIAL_TABLE_VERSION;
    private static final ModConfigSpec.ConfigValue<String> CONFIGURED_PACK_SIGNATURE;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS;
    private static final ModConfigSpec.ConfigValue<String> DEFAULT_DIMENSION;

    private static final ModConfigSpec.IntValue SCHEDULER_WORKER_THREADS;
    private static final ModConfigSpec.IntValue SCHEDULER_QUEUE_CAPACITY;
    private static final ModConfigSpec.DoubleValue SCHEDULER_MIN_TPS;
    private static final ModConfigSpec.IntValue PENDING_TILE_RETRY_MS;

    private static final ModConfigSpec.ConfigValue<String> MARKER_MODE;
    private static final ModConfigSpec.IntValue MARKER_POLL_INTERVAL_MS;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("web");
        WEB_ENABLED = BUILDER.comment("Enable the embedded web map server.")
                .define("enabled", true);
        WEB_BIND_HOST = BUILDER.comment("Host interface for the embedded web server.")
                .define("bindHost", "127.0.0.1");
        WEB_PORT = BUILDER.comment("Port for the embedded web server. Use 0 for an ephemeral port in tests.")
                .defineInRange("port", 8080, 0, 65535);
        BUILDER.pop();

        BUILDER.push("renderer");
        RENDER_OUTPUT_ROOT = BUILDER.comment("Filesystem root used for generated tiles.")
                .define("outputRoot", "run/cartography/tiles");
        RENDER_TILE_SIZE = BUILDER.comment("Rendered tile size in pixels.")
                .defineInRange("tileSize", 256, 64, 1024);
        RENDER_MIN_ZOOM = BUILDER.comment("Minimum served zoom.")
                .defineInRange("minZoom", 0, 0, 10);
        RENDER_MAX_ZOOM = BUILDER.comment("Maximum served zoom.")
                .defineInRange("maxZoom", 4, 0, 18);
        RENDER_PIXELS_PER_BLOCK_MAX_ZOOM = BUILDER.comment("Pixels per Minecraft block at max zoom.")
                .defineInRange("pixelsPerBlockAtMaxZoom", 1, 1, 32);
        RENDER_METATILE_SIZE = BUILDER.comment("Square width/height of a metatile job in tiles.")
                .defineInRange("metatileSize", 4, 1, 16);
        RENDERER_CODE_VERSION = BUILDER.comment("Version tag for renderer behavior.")
                .define("rendererCodeVersion", "bootstrap-v1");
        MATERIAL_TABLE_VERSION = BUILDER.comment("Version tag for color/material lookup tables.")
                .define("materialTableVersion", "vanilla");
        CONFIGURED_PACK_SIGNATURE = BUILDER.comment("Configured resource-pack signature used for tileset versioning.")
                .define("configuredPackSignature", "default-pack");
        DIMENSIONS = BUILDER.comment("Dimensions exposed to the frontend.")
                .defineListAllowEmpty("dimensions",
                        List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                        () -> "",
                        Config::isStringValue);
        DEFAULT_DIMENSION = BUILDER.comment("Default dimension selected by the frontend.")
                .define("defaultDimension", "minecraft:overworld");
        BUILDER.pop();

        BUILDER.push("scheduler");
        SCHEDULER_WORKER_THREADS = BUILDER.comment("Number of background render workers.")
                .defineInRange("workerThreads", 1, 1, 8);
        SCHEDULER_QUEUE_CAPACITY = BUILDER.comment("Maximum number of queued render jobs.")
                .defineInRange("queueCapacity", 512, 1, 8192);
        SCHEDULER_MIN_TPS = BUILDER.comment("Workers pause when the sampled TPS drops below this value.")
                .defineInRange("minTps", 18.0D, 0.0D, 20.0D);
        PENDING_TILE_RETRY_MS = BUILDER.comment("Frontend retry interval for pending tiles.")
                .defineInRange("pendingTileRetryMs", 1500, 100, 60000);
        BUILDER.pop();

        BUILDER.push("markers");
        MARKER_MODE = BUILDER.comment("Marker mode. Use 'off' to disable marker publication.")
                .define("mode", "off");
        MARKER_POLL_INTERVAL_MS = BUILDER.comment("Frontend polling interval for markers.")
                .defineInRange("pollIntervalMs", 2000, 250, 60000);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Config() {
    }

    public static CartographySettings settings() {
        return new CartographySettings(
                web(),
                renderer(),
                scheduler(),
                markers(),
                Path.of(RENDER_OUTPUT_ROOT.get()));
    }

    public static CartographySettings.WebSettings web() {
        return new CartographySettings.WebSettings(
                WEB_ENABLED.get(),
                WEB_BIND_HOST.get(),
                WEB_PORT.get(),
                PENDING_TILE_RETRY_MS.get());
    }

    public static RendererProfile renderer() {
        return new RendererProfile(
                RENDER_TILE_SIZE.get(),
                RENDER_MIN_ZOOM.get(),
                RENDER_MAX_ZOOM.get(),
                RENDER_PIXELS_PER_BLOCK_MAX_ZOOM.get(),
                RENDER_METATILE_SIZE.get(),
                RENDERER_CODE_VERSION.get(),
                MATERIAL_TABLE_VERSION.get(),
                CONFIGURED_PACK_SIGNATURE.get(),
                List.copyOf(DIMENSIONS.get()),
                DEFAULT_DIMENSION.get());
    }

    public static CartographySettings.SchedulerSettings scheduler() {
        return new CartographySettings.SchedulerSettings(
                SCHEDULER_WORKER_THREADS.get(),
                SCHEDULER_QUEUE_CAPACITY.get(),
                SCHEDULER_MIN_TPS.get());
    }

    public static CartographySettings.MarkerSettings markers() {
        return new CartographySettings.MarkerSettings(
                MARKER_MODE.get().trim().toLowerCase(),
                MARKER_POLL_INTERVAL_MS.get());
    }

    private static boolean isStringValue(Object value) {
        return value instanceof String;
    }
}
