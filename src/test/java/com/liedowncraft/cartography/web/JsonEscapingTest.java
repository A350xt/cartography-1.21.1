package com.liedowncraft.cartography.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.bootstrap.CartographyRuntime;
import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.config.CartographySettings.MarkerSettings;
import com.liedowncraft.cartography.config.CartographySettings.ServerMode;

/**
 * Player names and dimension ids reach the JSON endpoints from user-controlled data, so they must be
 * escaped rather than concatenated. An unescaped quote would let a player name break out of its
 * string and inject arbitrary structure into the payload the map client parses.
 */
class JsonEscapingTest {
    @Test
    void quotesInUserContentCannotBreakOutOfTheirString() {
        String hostile = "Steve\",\"admin\":true,\"x\":\"";

        String quoted = Json.quote(hostile);

        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""));
        // Every interior quote must be escaped, so the value stays a single JSON string.
        assertFalse(quoted.substring(1, quoted.length() - 1).contains("\"\\"));
        assertTrue(quoted.contains("\\\""), quoted);
    }

    @Test
    void backslashesAndControlCharactersAreEscaped() {
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"));
        assertEquals("\"line\\nbreak\"", Json.quote("line\nbreak"));
        assertEquals("\"tab\\there\"", Json.quote("tab\there"));
        assertEquals("\"\\u0000\"", Json.quote("\u0000"));
        assertEquals("\"\\u001f\"", Json.quote("\u001f"));
    }

    @Test
    void aHostilePlayerNameStillProducesParseableMarkerJson() throws Exception {
        CartographySettings settings = CartographySettings
                .forTests(Files.createTempDirectory("cartography-json"))
                .withMarkers(new MarkerSettings(ServerMode.BUILDING, "exact", 2000, 0, 64));

        try (CartographyRuntime runtime = CartographyRuntime.startForTests(settings)) {
            runtime.upsertMarker(new PlayerMarker(
                    "uuid-1",
                    "Steve\",\"injected\":\"yes",
                    "minecraft:overworld",
                    1.0,
                    2.0,
                    0L));

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(runtime.baseUri().resolve("/markers?dimension=minecraft:overworld"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            // The injected key must appear only as escaped text inside the name, never as structure.
            assertFalse(response.body().contains("\"injected\":\"yes\""), response.body());
            assertTrue(response.body().contains("\\\""), response.body());
        }
    }

    @Test
    void nonFiniteMetricsDoNotProduceInvalidJson() {
        // NaN and Infinity are not valid JSON literals; emitting them would break every client parse.
        String json = new Json().object()
                .field("ratio", Double.NaN)
                .field("latency", Double.POSITIVE_INFINITY)
                .endObject()
                .toString();

        assertFalse(json.contains("NaN"), json);
        assertFalse(json.contains("Infinity"), json);
        assertEquals("{\"ratio\":0,\"latency\":0}", json);
    }
}
