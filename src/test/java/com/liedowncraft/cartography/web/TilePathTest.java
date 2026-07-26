package com.liedowncraft.cartography.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.core.TileCoordinate;

/**
 * Tile URL parsing. This is the only place untrusted input becomes a filesystem path, so malformed
 * and hostile input must be rejected rather than normalized into something plausible.
 */
class TilePathTest {
    @Test
    void buildAndParseAreInverses() {
        TileCoordinate tile = new TileCoordinate("minecraft:overworld", 8, -3, 12);
        String path = TilePath.build("survival", "minecraft:overworld", "fast", "abc123", tile, "png");

        TilePath parsed = TilePath.parse(path).orElseThrow();

        assertEquals("survival", parsed.world());
        assertEquals("minecraft:overworld", parsed.dimension());
        assertEquals("fast", parsed.profile());
        assertEquals("abc123", parsed.tilesetVersion());
        assertEquals(tile, parsed.tile());
        assertEquals("png", parsed.extension());
    }

    @Test
    void negativeCoordinatesUseUnambiguousPrefixes() {
        String path = TilePath.build(
                "survival", "minecraft:overworld", "fast", "abc123",
                new TileCoordinate("minecraft:overworld", 8, -3, -12), "png");

        // Without the x/y prefixes a leading minus is easy to confuse with a path segment.
        assertTrue(path.endsWith("/8/x-3/y-12.png"), path);
        assertEquals(-3, TilePath.parse(path).orElseThrow().tile().x());
        assertEquals(-12, TilePath.parse(path).orElseThrow().tile().y());
    }

    @Test
    void traversalAttemptsAreRejected() {
        String[] hostile = {
            "/tiles/raster/../../../etc/passwd/fast/abc/8/x0/y0.png",
            "/tiles/raster/survival/../../fast/abc/8/x0/y0.png",
            "/tiles/raster/survival/minecraft:overworld/..%2F..%2Fetc/abc/8/x0/y0.png",
            "/tiles/raster/survival/minecraft:overworld/fast/../8/x0/y0.png",
            "/tiles/raster/survival/minecraft:overworld/fast/abc/8/x0/y0.%2e%2e%2fpng",
        };

        for (String path : hostile) {
            assertTrue(TilePath.parse(path).isEmpty(), "should have rejected: " + path);
        }
    }

    @Test
    void malformedPathsAreRejectedRatherThanGuessed() {
        String[] malformed = {
            "/tiles/raster/survival/minecraft:overworld/fast/abc/8/x0",
            "/tiles/raster/survival/minecraft:overworld/fast/abc/8/0/0.png",
            "/tiles/raster/survival/minecraft:overworld/fast/abc/8/x0/0.png",
            "/tiles/raster/survival/minecraft:overworld/fast/abc/notazoom/x0/y0.png",
            "/tiles/raster/survival/minecraft:overworld/fast/abc/8/xzero/y0.png",
            "/tiles/raster/survival/minecraft:overworld/fast/abc/8/x0/y0",
            "/tiles/raster/",
            "/manifest.json",
            "/tiles/raster/survival/minecraft:overworld/fast/abc/8/x0/y0.",
        };

        for (String path : malformed) {
            assertTrue(TilePath.parse(path).isEmpty(), "should have rejected: " + path);
        }
    }

    @Test
    void emptySegmentsAreRejected() {
        assertTrue(TilePath.parse("/tiles/raster//minecraft:overworld/fast/abc/8/x0/y0.png").isEmpty());
    }

    @Test
    void templateMatchesTheBuiltPathShape() {
        // The manifest advertises this template, so the client and server must agree on it.
        String template = TilePath.template();

        assertTrue(template.startsWith("/tiles/raster/"), template);
        for (String placeholder : new String[] {"{world}", "{dimension}", "{profile}", "{tilesetVersion}", "{z}", "x{x}", "y{y}"}) {
            assertTrue(template.contains(placeholder), "template is missing " + placeholder + ": " + template);
        }
    }

    @Test
    void zoomZeroTilesParse() {
        Optional<TilePath> parsed = TilePath.parse("/tiles/raster/survival/minecraft:overworld/fast/abc/0/x0/y0.png");

        assertTrue(parsed.isPresent());
        assertEquals(0, parsed.orElseThrow().tile().zoom());
    }
}
