package com.liedowncraft.cartography.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
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
import com.liedowncraft.cartography.core.MetatileJob;
import com.liedowncraft.cartography.core.TileCoordinate;
import com.liedowncraft.cartography.render.VanillaMapPalette;
import com.liedowncraft.cartography.snapshot.SampledMapBuffer;
import com.liedowncraft.cartography.snapshot.SnapshotUnavailableException;
import com.liedowncraft.cartography.snapshot.WorldSnapshotProvider;
import com.liedowncraft.cartography.storage.FileSystemTileStore;

class CartographyHttpContractTest {
    @Test
    void manifestExposesContractFields() throws Exception {
        CartographySettings settings = CartographySettings.forTests(Files.createTempDirectory("cartography-http"));
        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(runtime.baseUri().resolve("/manifest.json")).GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String compactJson = response.body().replaceAll("\\s+", "");

            assertEquals(200, response.statusCode());
            assertTrue(compactJson.contains("\"tileSize\""));
            assertTrue(compactJson.contains("\"tilesetVersion\""));
            assertTrue(compactJson.contains("\"markerMode\":\"off\""));
        }
    }

    @Test
    void tileMissReturnsPendingHeadersAndQueuesRender() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-pending");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(21.0);
        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            HttpClient client = HttpClient.newHttpClient();
            String tilePath = "/tiles/" + runtime.tilesetVersion() + "/minecraft:overworld/4/0/0.webp";
            HttpRequest request = HttpRequest.newBuilder(runtime.baseUri().resolve(tilePath)).GET().build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals("pending", response.headers().firstValue("X-Cartography-Tile-State").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow()) >= 1);
            assertEquals(1, runtime.queueDepth());
            assertTrue(response.body().length > 0);
        }
    }

    @Test
    void tileEventuallyRendersSnapshotDataAfterPendingResponse() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-live-render");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);
        WorldSnapshotProvider snapshotProvider = (MetatileJob job, com.liedowncraft.cartography.config.RendererProfile profile) -> {
            SampledMapBuffer.Builder buffer = SampledMapBuffer.builder(profile.tileSize() * job.tileCount(), profile.tileSize() * job.tileCount(), 1);
            for (int y = 0; y < buffer.height(); y++) {
                for (int x = 0; x < buffer.width(); x++) {
                    buffer.set(x, y, VanillaMapPalette.GRASS, 20.0F, 0.0F, false);
                }
            }
            return buffer.build();
        };

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, snapshotProvider)) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String tilePath = "/tiles/" + runtime.tilesetVersion() + "/minecraft:overworld/4/0/0.webp";
            HttpRequest request = HttpRequest.newBuilder(runtime.baseUri().resolve(tilePath)).GET().build();

            HttpResponse<byte[]> pendingResponse = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertEquals("pending", pendingResponse.headers().firstValue("X-Cartography-Tile-State").orElseThrow());

            HttpResponse<byte[]> readyResponse = awaitReadyTile(client, request);

            assertEquals(200, readyResponse.statusCode());
            assertFalse(readyResponse.headers().firstValue("X-Cartography-Tile-State").isPresent());
            assertFalse(java.util.Arrays.equals(pendingResponse.body(), readyResponse.body()));
        }
    }

    @Test
    void metatileBoundaryPreservesVanillaShadingAcrossTileSlices() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-metatile-shading");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);
        WorldSnapshotProvider snapshotProvider = (MetatileJob job, com.liedowncraft.cartography.config.RendererProfile profile) -> {
            int size = profile.tileSize() * job.tileCount();
            SampledMapBuffer.Builder buffer = SampledMapBuffer.builder(size, size, 1);
            for (int y = 0; y < size; y++) {
                float averageHeight = y < profile.tileSize() ? 12.0F : 0.0F;
                for (int x = 0; x < size; x++) {
                    buffer.set(x, y, VanillaMapPalette.STONE, averageHeight, 0.0F, false);
                }
            }
            return buffer.build();
        };

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, snapshotProvider)) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String tilePath = "/tiles/" + runtime.tilesetVersion() + "/minecraft:overworld/4/0/1.webp";
            HttpRequest request = HttpRequest.newBuilder(runtime.baseUri().resolve(tilePath)).GET().build();

            HttpResponse<byte[]> readyResponse = awaitReadyTile(client, request);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(readyResponse.body()));

            assertEquals(200, readyResponse.statusCode());
            assertEquals(VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.LOW), image.getRGB(0, 0));
        }
    }

    @Test
    void unavailableSnapshotKeepsTilePendingAndDoesNotWriteOutput() throws Exception {
        Path tempDir = Files.createTempDirectory("cartography-unloaded-chunk");
        CartographySettings settings = CartographySettings.forTests(tempDir).withMinTps(0.0);
        WorldSnapshotProvider snapshotProvider = (job, profile) -> {
            throw new SnapshotUnavailableException("chunk not loaded");
        };

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings, snapshotProvider)) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            TileCoordinate tile = new TileCoordinate("minecraft:overworld", 4, 0, 0);
            String tilePath = "/tiles/" + runtime.tilesetVersion() + "/minecraft:overworld/4/0/0.webp";
            HttpRequest request = HttpRequest.newBuilder(runtime.baseUri().resolve(tilePath)).GET().build();

            HttpResponse<byte[]> firstResponse = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Thread.sleep(200L);
            HttpResponse<byte[]> secondResponse = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertEquals("pending", firstResponse.headers().firstValue("X-Cartography-Tile-State").orElseThrow());
            assertEquals("pending", secondResponse.headers().firstValue("X-Cartography-Tile-State").orElseThrow());
            assertFalse(Files.exists(new FileSystemTileStore(tempDir).pathFor(runtime.tilesetVersion(), tile)));
        }
    }

    private HttpResponse<byte[]> awaitReadyTile(HttpClient client, HttpRequest request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        HttpResponse<byte[]> lastResponse = null;
        while (System.nanoTime() < deadline) {
            lastResponse = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (lastResponse.headers().firstValue("X-Cartography-Tile-State").isEmpty()) {
                return lastResponse;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for a rendered tile. Last response was " + (lastResponse == null ? "null" : lastResponse.statusCode()));
    }
}
