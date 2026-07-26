package com.liedowncraft.cartography.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.core.TileCoordinate;
import com.liedowncraft.cartography.web.TilePath;

/**
 * A miss on a low-zoom tile must not walk the whole pyramid beneath it.
 *
 * <p>At max zoom eight, a zoom-0 tile has over 65,000 descendants. Expanding all of them on the
 * HTTP thread would let one unauthenticated request perform tens of thousands of filesystem stats,
 * which is a denial-of-service vector on an endpoint that has no authentication.
 */
class MissExpansionBoundTest {
    @Test
    void aLowZoomMissIsBoundedAndReturnsPromptly() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-miss-bound");
        // Keep the workers paused so the queue reflects exactly what the request enqueued.
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(21.0);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            runtime.setCurrentTps(1.0);

            TilePath request = new TilePath(
                    runtime.worldId(),
                    "minecraft:overworld",
                    settings.renderer().profileId(),
                    runtime.tilesetVersion(),
                    new TileCoordinate("minecraft:overworld", 0, 0, 0),
                    settings.renderer().fileExtension());

            long startNanos = System.nanoTime();
            runtime.tileResponse(request);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

            // The bound is on work performed, so the wall-clock check is deliberately loose; it only
            // has to fail if the expansion became unbounded again.
            assertTrue(
                    elapsed.toSeconds() < 5,
                    "expanding a zoom-0 miss took " + elapsed.toMillis() + "ms, which suggests unbounded descent");
            assertTrue(
                    runtime.queueDepth() <= 256,
                    "a single miss enqueued " + runtime.queueDepth() + " jobs; expansion should be capped");
        }
    }

    @Test
    void aMaxZoomMissEnqueuesExactlyOneMetatile() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-miss-single");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(21.0);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            runtime.setCurrentTps(1.0);

            runtime.tileResponse(new TilePath(
                    runtime.worldId(),
                    "minecraft:overworld",
                    settings.renderer().profileId(),
                    runtime.tilesetVersion(),
                    new TileCoordinate("minecraft:overworld", settings.renderer().maxZoom(), 0, 0),
                    settings.renderer().fileExtension()));

            assertEquals(1, runtime.queueDepth());
        }
    }
}
