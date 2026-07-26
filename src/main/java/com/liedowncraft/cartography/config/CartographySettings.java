package com.liedowncraft.cartography.config;

import java.nio.file.Path;
import java.util.List;

import com.liedowncraft.cartography.core.TileCoordinateMode;
import com.liedowncraft.cartography.core.TileGrid;

/**
 * Runtime settings groups (technical plan v2.0, appendix A.1).
 */
public record CartographySettings(
        WebSettings web,
        RendererProfile renderer,
        SchedulerSettings scheduler,
        MarkerSettings markers,
        Path tileRoot) {

    /**
     * Ships the plan's MVP "fast" profile: 2 pixels per block at max zoom, 4x4 metatiles, 2 blocks of
     * padding, height shading and fluid depth on.
     */
    public static CartographySettings defaults(Path tileRoot) {
        return new CartographySettings(
                new WebSettings(true, "127.0.0.1", 8080, 1500),
                fastProfile(8),
                new SchedulerSettings(1, 512, 18.5D, 19.2D, 5, 64),
                new MarkerSettings(ServerMode.SURVIVAL, "off", 2000, 60, 64),
                tileRoot);
    }

    /**
     * Test profile. Max zoom is lowered so a metatile covers few enough blocks to fake cheaply, and
     * the web port is ephemeral.
     */
    public static CartographySettings forTests(Path tileRoot) {
        return new CartographySettings(
                new WebSettings(true, "127.0.0.1", 0, 1500),
                fastProfile(4),
                new SchedulerSettings(1, 64, 18.5D, 19.2D, 0, 64),
                new MarkerSettings(ServerMode.SURVIVAL, "off", 2000, 60, 64),
                tileRoot);
    }

    private static RendererProfile fastProfile(int maxZoom) {
        return new RendererProfile(
                "fast",
                new TileGrid(256, 0, maxZoom, 2, 0, 0),
                TileCoordinateMode.ONLINE_SIGNED,
                4,
                2,
                "cartography-v2",
                "vanilla-map-colors",
                "default-pack",
                // Indexed PNG rather than the plan's WebP: measured smaller on palette tiles, and
                // stock Java has no WebP writer.
                "png",
                85,
                true,
                true,
                List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                "minecraft:overworld");
    }

    public CartographySettings withMinTps(double minTps) {
        return new CartographySettings(web, renderer, scheduler.withMinTps(minTps), markers, tileRoot);
    }

    public CartographySettings withRenderer(RendererProfile updatedRenderer) {
        return new CartographySettings(web, updatedRenderer, scheduler, markers, tileRoot);
    }

    public CartographySettings withMarkers(MarkerSettings updatedMarkers) {
        return new CartographySettings(web, renderer, scheduler, updatedMarkers, tileRoot);
    }

    public record WebSettings(boolean enabled, String bindHost, int port, int pendingTileRetryMs) {
    }

    /**
     * @param minTps workers stop pulling jobs below this TPS (plan A.1 pauseBelowTps)
     * @param resumeTps workers resume above this TPS; the gap prevents thrashing at the threshold
     * @param dirtyDebounceSeconds how long dirty chunks accumulate before becoming render jobs
     * @param ancestorBudgetPerPass low-zoom downsample tiles refreshed per pass, kept off the hot path
     */
    public record SchedulerSettings(
            int workerThreads,
            int queueCapacity,
            double minTps,
            double resumeTps,
            int dirtyDebounceSeconds,
            int ancestorBudgetPerPass) {

        public SchedulerSettings withMinTps(double updatedMinTps) {
            return new SchedulerSettings(
                    workerThreads,
                    queueCapacity,
                    updatedMinTps,
                    Math.max(updatedMinTps, resumeTps),
                    dirtyDebounceSeconds,
                    ancestorBudgetPerPass);
        }
    }

    /**
     * Player marker publication (plan sections 9.3 and A.1). Defaults are deliberately conservative:
     * markers are off unless an operator opts in.
     *
     * @param mode {@code off}, {@code exact}, or {@code blurred}
     * @param publicDelaySeconds how stale a published position must be
     * @param blurRadiusBlocks position quantization applied in blurred mode
     */
    public record MarkerSettings(
            ServerMode serverMode,
            String mode,
            int pollIntervalMs,
            int publicDelaySeconds,
            int blurRadiusBlocks) {

        public boolean enabled() {
            return !"off".equalsIgnoreCase(mode);
        }

        public boolean blurred() {
            return "blurred".equalsIgnoreCase(mode);
        }
    }

    /**
     * Server mode preset (plan section 9.3). Determines how conservative the default location
     * privacy policy is when an operator has not chosen one explicitly.
     */
    public enum ServerMode {
        BUILDING,
        PVE,
        SURVIVAL,
        PVP,
        WAR;

        public static ServerMode fromConfigValue(String value) {
            for (ServerMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return SURVIVAL;
        }
    }
}
