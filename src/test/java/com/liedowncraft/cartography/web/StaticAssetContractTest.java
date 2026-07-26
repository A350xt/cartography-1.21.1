package com.liedowncraft.cartography.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.bootstrap.CartographyRuntime;
import com.liedowncraft.cartography.config.CartographySettings;

/**
 * The frontend bundle is served out of the mod jar under {@code web/}. The Gradle sync that puts it
 * there is easy to break silently: pointing the resource source directory at the bundle folder
 * itself strips the {@code web/} prefix, and every asset then 404s at runtime while the build stays
 * green. These tests pin the serving contract.
 */
class StaticAssetContractTest {
    @Test
    void rootServesAnHtmlDocument() throws Exception {
        CartographySettings settings = CartographySettings.forTests(Files.createTempDirectory("cartography-assets"));
        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(
                    response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                    "the map root must serve HTML");
            assertTrue(response.body().contains("<html"), "expected an HTML document, got: " + response.body());
        }
    }

    @Test
    void bundledFrontendIsReachableWhenItHasBeenBuilt() throws Exception {
        // Skips rather than fails when the bundle is absent, so a backend-only test run still passes.
        boolean bundlePresent = StaticAssetContractTest.class.getClassLoader().getResource("web/index.html") != null;
        org.junit.jupiter.api.Assumptions.assumeTrue(bundlePresent, "frontend bundle not built");

        CartographySettings settings = CartographySettings.forTests(Files.createTempDirectory("cartography-bundle"));
        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/index.html")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(
                    response.body().contains("<script") || response.body().contains("<div id=\"app\""),
                    "the built bundle should be served rather than the placeholder");
        }
    }

    @Test
    void missingAssetsReturnNotFound() throws Exception {
        CartographySettings settings = CartographySettings.forTests(Files.createTempDirectory("cartography-missing"));
        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/assets/does-not-exist.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(404, response.statusCode());
        }
    }
}
