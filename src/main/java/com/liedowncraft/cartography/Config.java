package com.liedowncraft.cartography;

import java.nio.file.Path;
import java.util.List;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.core.TileCoordinateMode;
import com.liedowncraft.cartography.core.TileGrid;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Config surface mirroring the technical plan's {@code cartography.yml} (appendix A.1).
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue WEB_ENABLED;
    private static final ModConfigSpec.ConfigValue<String> WEB_BIND_HOST;
    private static final ModConfigSpec.IntValue WEB_PORT;

    private static final ModConfigSpec.ConfigValue<String> RENDER_OUTPUT_ROOT;
    private static final ModConfigSpec.ConfigValue<String> RENDER_PROFILE_ID;
    private static final ModConfigSpec.IntValue RENDER_TILE_SIZE;
    private static final ModConfigSpec.IntValue RENDER_MIN_ZOOM;
    private static final ModConfigSpec.IntValue RENDER_MAX_ZOOM;
    private static final ModConfigSpec.IntValue RENDER_PIXELS_PER_BLOCK_MAX_ZOOM;
    private static final ModConfigSpec.IntValue RENDER_TILE_ORIGIN_X;
    private static final ModConfigSpec.IntValue RENDER_TILE_ORIGIN_Z;
    private static final ModConfigSpec.ConfigValue<String> RENDER_TILE_COORDINATE_MODE;
    private static final ModConfigSpec.IntValue RENDER_METATILE_SIZE;
    private static final ModConfigSpec.IntValue RENDER_PADDING_BLOCKS;
    private static final ModConfigSpec.ConfigValue<String> RENDER_FORMAT;
    private static final ModConfigSpec.IntValue RENDER_QUALITY;
    private static final ModConfigSpec.BooleanValue RENDER_HEIGHT_SHADE;
    private static final ModConfigSpec.BooleanValue RENDER_FLUID_DEPTH;
    private static final ModConfigSpec.ConfigValue<String> RENDERER_CODE_VERSION;
    private static final ModConfigSpec.ConfigValue<String> MATERIAL_TABLE_VERSION;
    private static final ModConfigSpec.ConfigValue<String> CONFIGURED_PACK_SIGNATURE;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS;
    private static final ModConfigSpec.ConfigValue<String> DEFAULT_DIMENSION;

    private static final ModConfigSpec.IntValue SCHEDULER_WORKER_THREADS;
    private static final ModConfigSpec.IntValue SCHEDULER_QUEUE_CAPACITY;
    private static final ModConfigSpec.DoubleValue SCHEDULER_PAUSE_BELOW_TPS;
    private static final ModConfigSpec.DoubleValue SCHEDULER_RESUME_ABOVE_TPS;
    private static final ModConfigSpec.IntValue SCHEDULER_DIRTY_DEBOUNCE_SECONDS;
    private static final ModConfigSpec.IntValue SCHEDULER_ANCESTOR_BUDGET;
    private static final ModConfigSpec.IntValue PENDING_TILE_RETRY_MS;

    private static final ModConfigSpec.ConfigValue<String> SERVER_MODE;
    private static final ModConfigSpec.ConfigValue<String> MARKER_MODE;
    private static final ModConfigSpec.IntValue MARKER_POLL_INTERVAL_MS;
    private static final ModConfigSpec.IntValue MARKER_PUBLIC_DELAY_SECONDS;
    private static final ModConfigSpec.IntValue MARKER_BLUR_RADIUS_BLOCKS;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("web");
        WEB_ENABLED = BUILDER.comment("Enable the embedded web map server.")
                .define("enabled", true);
        WEB_BIND_HOST = BUILDER.comment("Host interface for the embedded web server.")
                .define("bindHost", "127.0.0.1");
        WEB_PORT = BUILDER.comment("Port for the embedded web server. Use 0 for an ephemeral port in tests.")
                .defineInRange("port", 8080, 0, 65535);
        PENDING_TILE_RETRY_MS = BUILDER.comment("Frontend retry interval for tiles that are still rendering.")
                .defineInRange("pendingTileRetryMs", 1500, 100, 60000);
        BUILDER.pop();

        BUILDER.push("renderer");
        RENDER_OUTPUT_ROOT = BUILDER.comment("Filesystem root used for generated tiles.")
                .define("outputRoot", "cartography");
        RENDER_PROFILE_ID = BUILDER.comment("Render profile id. 'fast' is the MVP colour-table profile.")
                .define("profile", "fast");
        RENDER_TILE_SIZE = BUILDER.comment("Rendered tile size in pixels.")
                .defineInRange("tileSize", 256, 64, 1024);
        RENDER_MIN_ZOOM = BUILDER.comment("Minimum served zoom.")
                .defineInRange("minZoom", 0, 0, 10);
        RENDER_MAX_ZOOM = BUILDER.comment("Maximum served zoom.")
                .defineInRange("maxZoom", 8, 0, 18);
        RENDER_PIXELS_PER_BLOCK_MAX_ZOOM = BUILDER.comment("Pixels per Minecraft block at max zoom. Must divide tileSize.")
                .defineInRange("pixelsPerBlockAtMaxZoom", 2, 1, 32);
        RENDER_TILE_ORIGIN_X = BUILDER.comment("Tile grid origin X. Display-only; never changes stored coordinates.")
                .defineInRange("tileOriginX", 0, -30000000, 30000000);
        RENDER_TILE_ORIGIN_Z = BUILDER.comment("Tile grid origin Z. Display-only; never changes stored coordinates.")
                .defineInRange("tileOriginZ", 0, -30000000, 30000000);
        RENDER_TILE_COORDINATE_MODE = BUILDER.comment("Tile addressing: 'online-signed' or 'published-normalized'.")
                .define("tileCoordinateMode", "online-signed");
        RENDER_METATILE_SIZE = BUILDER.comment("Square width/height of a metatile job in tiles.")
                .defineInRange("metatileSize", 4, 1, 16);
        RENDER_PADDING_BLOCKS = BUILDER.comment("Blocks of padding sampled around each metatile to keep shading seamless.")
                .defineInRange("paddingBlocks", 2, 0, 32);
        RENDER_FORMAT = BUILDER.comment("Tile image format. Only 'png' is supported; tiles use an indexed palette.")
                .define("format", "png");
        RENDER_QUALITY = BUILDER.comment("Recorded in the tileset version. PNG is lossless, so this does not affect output.")
                .defineInRange("quality", 85, 1, 100);
        RENDER_HEIGHT_SHADE = BUILDER.comment("Apply vanilla-style slope shading.")
                .define("heightShade", true);
        RENDER_FLUID_DEPTH = BUILDER.comment("Shade water by depth.")
                .define("fluidDepth", true);
        RENDERER_CODE_VERSION = BUILDER.comment("Version tag for renderer behavior. Changing it invalidates all tiles.")
                .define("rendererCodeVersion", "cartography-v2");
        MATERIAL_TABLE_VERSION = BUILDER.comment("Version tag for colour/material lookup tables.")
                .define("materialTableVersion", "vanilla-map-colors");
        CONFIGURED_PACK_SIGNATURE = BUILDER.comment("Resource-pack signature folded into the tileset version.")
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
        SCHEDULER_PAUSE_BELOW_TPS = BUILDER.comment("Workers pause when server TPS drops below this value.")
                .defineInRange("pauseBelowTps", 18.5D, 0.0D, 20.0D);
        SCHEDULER_RESUME_ABOVE_TPS = BUILDER.comment("Workers resume once TPS recovers above this value.")
                .defineInRange("resumeAboveTps", 19.2D, 0.0D, 20.0D);
        SCHEDULER_DIRTY_DEBOUNCE_SECONDS = BUILDER.comment("Seconds a dirty chunk settles before it becomes a render job.")
                .defineInRange("dirtyDebounceSeconds", 5, 0, 300);
        SCHEDULER_ANCESTOR_BUDGET = BUILDER.comment("Low-zoom tiles downsampled per idle pass.")
                .defineInRange("ancestorBudgetPerPass", 64, 1, 4096);
        BUILDER.pop();

        BUILDER.push("markers");
        SERVER_MODE = BUILDER.comment("Server mode preset driving location privacy: building, pve, survival, pvp, war.")
                .define("serverMode", "survival");
        MARKER_MODE = BUILDER.comment("Marker mode: 'off', 'blurred', or 'exact'.")
                .define("mode", "off");
        MARKER_POLL_INTERVAL_MS = BUILDER.comment("Frontend polling interval for markers.")
                .defineInRange("pollIntervalMs", 2000, 250, 60000);
        MARKER_PUBLIC_DELAY_SECONDS = BUILDER.comment("Seconds a position is held back before the public map shows it.")
                .defineInRange("publicDelaySeconds", 60, 0, 3600);
        MARKER_BLUR_RADIUS_BLOCKS = BUILDER.comment("Cell size used to quantize positions in blurred mode.")
                .defineInRange("blurRadiusBlocks", 64, 1, 4096);
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
        TileGrid tileGrid = new TileGrid(
                RENDER_TILE_SIZE.get(),
                RENDER_MIN_ZOOM.get(),
                RENDER_MAX_ZOOM.get(),
                RENDER_PIXELS_PER_BLOCK_MAX_ZOOM.get(),
                RENDER_TILE_ORIGIN_X.get(),
                RENDER_TILE_ORIGIN_Z.get());

        return new RendererProfile(
                RENDER_PROFILE_ID.get(),
                tileGrid,
                TileCoordinateMode.fromManifestValue(RENDER_TILE_COORDINATE_MODE.get()),
                RENDER_METATILE_SIZE.get(),
                RENDER_PADDING_BLOCKS.get(),
                RENDERER_CODE_VERSION.get(),
                MATERIAL_TABLE_VERSION.get(),
                CONFIGURED_PACK_SIGNATURE.get(),
                RENDER_FORMAT.get(),
                RENDER_QUALITY.get(),
                RENDER_HEIGHT_SHADE.get(),
                RENDER_FLUID_DEPTH.get(),
                List.copyOf(DIMENSIONS.get()),
                DEFAULT_DIMENSION.get());
    }

    public static CartographySettings.SchedulerSettings scheduler() {
        double pauseBelow = SCHEDULER_PAUSE_BELOW_TPS.get();
        // Resume must sit at or above pause, otherwise the hysteresis band inverts and workers flap.
        double resumeAbove = Math.max(pauseBelow, SCHEDULER_RESUME_ABOVE_TPS.get());
        return new CartographySettings.SchedulerSettings(
                SCHEDULER_WORKER_THREADS.get(),
                SCHEDULER_QUEUE_CAPACITY.get(),
                pauseBelow,
                resumeAbove,
                SCHEDULER_DIRTY_DEBOUNCE_SECONDS.get(),
                SCHEDULER_ANCESTOR_BUDGET.get());
    }

    public static CartographySettings.MarkerSettings markers() {
        return new CartographySettings.MarkerSettings(
                CartographySettings.ServerMode.fromConfigValue(SERVER_MODE.get()),
                MARKER_MODE.get().trim().toLowerCase(java.util.Locale.ROOT),
                MARKER_POLL_INTERVAL_MS.get(),
                MARKER_PUBLIC_DELAY_SECONDS.get(),
                MARKER_BLUR_RADIUS_BLOCKS.get());
    }

    private static boolean isStringValue(Object value) {
        return value instanceof String;
    }
}
