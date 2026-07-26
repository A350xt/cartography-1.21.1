package com.liedowncraft.cartography.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.bootstrap.CartographyRuntime;
import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.core.MetatileJob;
import com.liedowncraft.cartography.core.TileCoordinate;
import com.liedowncraft.cartography.render.VanillaMapPalette;
import com.liedowncraft.cartography.snapshot.SampledMapBuffer;
import com.liedowncraft.cartography.snapshot.SnapshotUnavailableException;
import com.liedowncraft.cartography.snapshot.SurfaceKind;
import com.liedowncraft.cartography.snapshot.WorldSnapshotProvider;
import com.liedowncraft.cartography.storage.FileSystemTileStore;

class CartographyHttpContractTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void manifestExposesTheFullTileGridContract() throws Exception {
        CartographySettings settings = CartographySettings.forTests(Files.createTempDirectory("cartography-http"));
        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/manifest.json")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String json = response.body().replaceAll("\\s+", "");

            assertEquals(200, response.statusCode());
            // Without these the client cannot map a Minecraft coordinate onto a tile pixel.
            assertTrue(json.contains("\"crs\":\"cartography:mc-crs\""), json);
            assertTrue(json.contains("\"dataCoordinate\":\"minecraft-xz\""), json);
            assertTrue(json.contains("\"tileOriginX\":0"), json);
            assertTrue(json.contains("\"tileOriginZ\":0"), json);
            assertTrue(json.contains("\"pixelsPerBlockAtMaxZoom\":2"), json);
            assertTrue(json.contains("\"blocksPerTileAtMaxZoom\":128"), json);
            assertTrue(json.contains("\"tileCoordinateMode\":\"online-signed\""), json);
            assertTrue(json.contains("\"normalizedOffsetX\""), json);
            assertTrue(json.contains("\"tilesetVersion\""), json);
            assertTrue(json.contains("\"markerMode\":\"off\""), json);
            // Plan 7.4: the manifest must revalidate so a new tileset version is picked up promptly.
            assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.headers().firstValue("ETag").isPresent());
        }
    }

    @Test
    void manifestRevalidatesWithAnEtag() throws Exception {
        CartographySettings settings = CartographySettings.forTests(Files.createTempDirectory("cartography-etag"));
        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> first = client.send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/manifest.json")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String etag = first.headers().firstValue("ETag").orElseThrow();

            HttpResponse<String> second = client.send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/manifest.json"))
                            .header("If-None-Match", etag)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(304, second.statusCode());
        }
    }

    @Test
    void tileMissReturnsPendingHeadersAndQueuesRender() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-pending");
        // Pause the workers so the job stays queued and observable.
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(21.0);
        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            runtime.setCurrentTps(5.0);
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    tileRequest(runtime, 4, 0, 0), HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals("pending", response.headers().firstValue("X-Cartography-Tile-State").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) >= 1);
            assertEquals(1, runtime.queueDepth());
            assertTrue(response.body().length > 0);
        }
    }

    @Test
    void readyTileIsServedImmutablySoBrowsersAndCdnsCanCacheForever() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-immutable");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, flatSnapshot(VanillaMapPalette.GRASS))) {
            HttpClient client = newClient();
            HttpResponse<byte[]> ready = awaitReadyTile(client, tileRequest(runtime, 4, 0, 0));

            assertEquals(200, ready.statusCode());
            assertEquals(
                    "public, max-age=31536000, immutable",
                    ready.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    @Test
    void tileRequestUnderAStaleTilesetVersionIsRejected() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-stale-version");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, flatSnapshot(VanillaMapPalette.GRASS))) {
            String stalePath = TilePath.build(
                    runtime.worldId(),
                    DIMENSION,
                    settings.renderer().profileId(),
                    "0000000000000000",
                    new TileCoordinate(DIMENSION, 4, 0, 0),
                    settings.renderer().fileExtension());

            HttpResponse<byte[]> response = newClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve(stalePath)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            // Serving current pixels under an old namespace is the cache-poisoning failure mode.
            assertEquals(404, response.statusCode());
        }
    }

    @Test
    void negativeTileCoordinatesRoundTripThroughTheUrl() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-negative-tile");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, flatSnapshot(VanillaMapPalette.STONE))) {
            HttpResponse<byte[]> ready = awaitReadyTile(newClient(), tileRequest(runtime, 4, -3, -12));

            assertEquals(200, ready.statusCode());
            assertTrue(
                    new FileSystemTileStore(tempDir).exists(
                            runtime.tilesetVersion(),
                            new TileCoordinate(DIMENSION, 4, -3, -12),
                            settings.renderer().fileExtension()),
                    "a negative tile must be stored and served like any other");
        }
    }

    @Test
    void tileEventuallyRendersSnapshotDataAfterPendingResponse() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-live-render");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, flatSnapshot(VanillaMapPalette.GRASS))) {
            HttpClient client = newClient();
            HttpRequest request = tileRequest(runtime, 4, 0, 0);

            HttpResponse<byte[]> pending = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("pending", pending.headers().firstValue("X-Cartography-Tile-State").orElseThrow());

            HttpResponse<byte[]> ready = awaitReadyTile(client, request);

            assertEquals(200, ready.statusCode());
            assertFalse(ready.headers().firstValue("X-Cartography-Tile-State").isPresent());
            assertFalse(java.util.Arrays.equals(pending.body(), ready.body()));
        }
    }

    @Test
    void metatileBoundaryPreservesVanillaShadingAcrossTileSlices() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-metatile-shading");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);

        // A cliff exactly on the boundary between tile row 0 and row 1. With padding the second tile
        // still sees the higher ground above it, so the shading must read as a downward slope.
        WorldSnapshotProvider provider = (MetatileJob job, RendererProfile profile) -> {
            int padding = profile.paddingBlocks();
            int size = profile.tileSize() * job.tileCount() + padding * 2;
            SampledMapBuffer.Builder buffer = SampledMapBuffer.builder(size, size, padding, 1);
            for (int y = 0; y < size; y++) {
                float height = y < profile.tileSize() + padding ? 12.0F : 0.0F;
                for (int x = 0; x < size; x++) {
                    buffer.setAt(x, y, VanillaMapPalette.STONE, height, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0);
                }
            }
            return buffer.build();
        };

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, provider)) {
            HttpResponse<byte[]> ready = awaitReadyTile(newClient(), tileRequest(runtime, 4, 0, 1));
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(ready.body()));

            assertEquals(200, ready.statusCode());
            assertEquals(
                    VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.LOW),
                    image.getRGB(0, 0),
                    "the first row of a tile must be shaded against its true northern neighbour");
        }
    }

    @Test
    void lowZoomTileIsBuiltByDownsamplingItsChildren() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-downsample");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, flatSnapshot(VanillaMapPalette.GRASS))) {
            HttpClient client = newClient();
            // A zoom-3 tile spans four zoom-4 tiles; it can only exist once they are downsampled.
            HttpResponse<byte[]> ready = awaitReadyTile(client, tileRequest(runtime, 3, 0, 0), Duration.ofSeconds(20));

            assertEquals(200, ready.statusCode());
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(ready.body()));
            assertEquals(settings.renderer().tileSize(), image.getWidth());
            assertNotEquals(0, image.getRGB(0, 0) & 0x00FFFFFF, "downsampled tile must carry child pixels");
        }
    }

    @Test
    void unavailableSnapshotKeepsTilePendingAndDoesNotWriteOutput() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-unloaded-chunk");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);
        WorldSnapshotProvider provider = (job, profile) -> {
            throw new SnapshotUnavailableException("chunk not loaded");
        };

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, provider)) {
            HttpClient client = newClient();
            HttpRequest request = tileRequest(runtime, 4, 0, 0);

            HttpResponse<byte[]> first = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Thread.sleep(250L);
            HttpResponse<byte[]> second = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertEquals("pending", first.headers().firstValue("X-Cartography-Tile-State").orElseThrow());
            assertEquals("pending", second.headers().firstValue("X-Cartography-Tile-State").orElseThrow());
            assertFalse(
                    new FileSystemTileStore(tempDir).exists(
                            runtime.tilesetVersion(),
                            new TileCoordinate(DIMENSION, 4, 0, 0),
                            settings.renderer().fileExtension()),
                    "an unavailable snapshot must not write a partial tile");
        }
    }

    @Test
    void healthEndpointReportsTheObservabilityMetricsTheePlanRequires() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-health");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, flatSnapshot(VanillaMapPalette.GRASS))) {
            HttpResponse<String> response = newClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String json = response.body().replaceAll("\\s+", "");

            assertEquals(200, response.statusCode());
            for (String metric : new String[] {
                "snapshotTimeMs",
                "renderJobQueueDepth",
                "metatileRenderMs",
                "tileWriteLatencyMs",
                "ancestorDirtyBacklog",
                "cacheHitRatio",
            }) {
                assertTrue(json.contains("\"" + metric + "\""), "health payload must expose " + metric + ": " + json);
            }
        }
    }

    @Test
    void markersStayEmptyWhileMarkerModeIsOff() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-markers-off");
        CartographySettings settings = CartographySettings.forTests(tempDir);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            runtime.upsertMarker(new PlayerMarker("uuid-1", "Steve", DIMENSION, 100, 200, 0L));

            HttpResponse<String> response = newClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/markers?dimension=" + DIMENSION)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals("{\"players\":[]}", response.body().replaceAll("\\s+", ""));
        }
    }

    @Test
    void staticAssetRequestCannotEscapeTheBundledFrontend() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-traversal");
        CartographySettings settings = CartographySettings.forTests(tempDir);

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            HttpResponse<String> response = newClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/..%2f..%2fbuild.gradle")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertTrue(response.statusCode() >= 400, "path traversal must be refused, got " + response.statusCode());
        }
    }

    private static WorldSnapshotProvider flatSnapshot(byte mapColorId) {
        return (MetatileJob job, RendererProfile profile) -> {
            int padding = profile.paddingBlocks();
            int size = profile.tileSize() * job.tileCount() + padding * 2;
            SampledMapBuffer.Builder buffer = SampledMapBuffer.builder(size, size, padding, 1);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    buffer.setAt(x, y, mapColorId, 20.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0);
                }
            }
            return buffer.build();
        };
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static HttpRequest tileRequest(CartographyRuntime runtime, int zoom, int x, int y) {
        String path = TilePath.build(
                runtime.worldId(),
                DIMENSION,
                runtime.settings().renderer().profileId(),
                runtime.tilesetVersion(),
                new TileCoordinate(DIMENSION, zoom, x, y),
                runtime.settings().renderer().fileExtension());
        return HttpRequest.newBuilder(runtime.baseUri().resolve(path)).GET().build();
    }

    private HttpResponse<byte[]> awaitReadyTile(HttpClient client, HttpRequest request) throws Exception {
        return awaitReadyTile(client, request, Duration.ofSeconds(10));
    }

    private HttpResponse<byte[]> awaitReadyTile(HttpClient client, HttpRequest request, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpResponse<byte[]> last = null;
        while (System.nanoTime() < deadline) {
            last = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (last.headers().firstValue("X-Cartography-Tile-State").isEmpty() && last.statusCode() == 200) {
                return last;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for a rendered tile. Last status was "
                + (last == null ? "null" : last.statusCode()));
    }
}
