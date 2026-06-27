package com.liedowncraft.cartography.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.bootstrap.CartographyRuntime;
import com.liedowncraft.cartography.config.CartographySettings;

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
}
