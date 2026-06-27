package com.liedowncraft.cartography.config;

import java.nio.file.Path;
import java.util.List;

public record CartographySettings(
        WebSettings web,
        RendererProfile renderer,
        SchedulerSettings scheduler,
        MarkerSettings markers,
        Path tileRoot) {
    public static CartographySettings defaults(Path tileRoot) {
        return new CartographySettings(
                new WebSettings(true, "127.0.0.1", 8080, 1500),
                new RendererProfile(
                        256,
                        0,
                        4,
                        1,
                        4,
                        "bootstrap-v1",
                        "vanilla",
                        "default-pack",
                        List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                        "minecraft:overworld"),
                new SchedulerSettings(1, 512, 18.0D),
                new MarkerSettings("off", 2000),
                tileRoot);
    }

    public static CartographySettings forTests(Path tileRoot) {
        return new CartographySettings(
                new WebSettings(true, "127.0.0.1", 0, 1500),
                new RendererProfile(
                        256,
                        0,
                        4,
                        1,
                        4,
                        "bootstrap-v1",
                        "vanilla",
                        "default-pack",
                        List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                        "minecraft:overworld"),
                new SchedulerSettings(1, 64, 18.0D),
                new MarkerSettings("off", 2000),
                tileRoot);
    }

    public CartographySettings withMinTps(double minTps) {
        return new CartographySettings(web, renderer, scheduler.withMinTps(minTps), markers, tileRoot);
    }

    public record WebSettings(boolean enabled, String bindHost, int port, int pendingTileRetryMs) {
    }

    public record SchedulerSettings(int workerThreads, int queueCapacity, double minTps) {
        public SchedulerSettings withMinTps(double updatedMinTps) {
            return new SchedulerSettings(workerThreads, queueCapacity, updatedMinTps);
        }
    }

    public record MarkerSettings(String mode, int pollIntervalMs) {
        public boolean enabled() {
            return !"off".equals(mode);
        }
    }
}
