package com.liedowncraft.cartography.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.core.TileCoordinate;

/**
 * Online writable raster store (technical plan v2.0, section 7.2).
 */
class FileSystemTileStoreTest {
    private static final byte[] TILE_BYTES = {1, 2, 3, 4};

    @Test
    void writtenTilesAreReadBackByteForByte() throws IOException {
        FileSystemTileStore store = new FileSystemTileStore(Files.createTempDirectory("tile-store"));
        TileCoordinate tile = new TileCoordinate("minecraft:overworld", 4, 2, 3);

        store.write("v1", tile, TILE_BYTES, "png");

        Optional<StoredTile> stored = store.read("v1", tile, "png");
        assertTrue(stored.isPresent());
        assertArrayEquals(TILE_BYTES, stored.orElseThrow().bytes());
    }

    @Test
    void negativeCoordinatesGetUnambiguousPathSegments() throws IOException {
        Path root = Files.createTempDirectory("tile-store-negative");
        FileSystemTileStore store = new FileSystemTileStore(root);
        TileCoordinate tile = new TileCoordinate("minecraft:overworld", 8, -3, -12);

        Path path = store.pathFor("v1", tile, "png");

        // The x/y prefixes keep a leading minus from being read as a path separator or traversal.
        assertTrue(path.toString().contains("x-3"), path.toString());
        assertTrue(path.toString().endsWith("y-12.png"), path.toString());

        store.write("v1", tile, TILE_BYTES, "png");
        assertTrue(store.exists("v1", tile, "png"));
    }

    @Test
    void tilesetVersionsAreIsolatedFromEachOther() throws IOException {
        FileSystemTileStore store = new FileSystemTileStore(Files.createTempDirectory("tile-store-versions"));
        TileCoordinate tile = new TileCoordinate("minecraft:overworld", 4, 0, 0);

        store.write("v1", tile, TILE_BYTES, "png");

        // A new tileset version must start empty rather than inherit the previous render.
        assertFalse(store.exists("v2", tile, "png"));
        assertTrue(store.read("v2", tile, "png").isEmpty());
    }

    @Test
    void overwritingATileLeavesNoTemporaryFileBehind() throws IOException {
        Path root = Files.createTempDirectory("tile-store-atomic");
        FileSystemTileStore store = new FileSystemTileStore(root);
        TileCoordinate tile = new TileCoordinate("minecraft:overworld", 4, 0, 0);

        store.write("v1", tile, TILE_BYTES, "png");
        store.write("v1", tile, new byte[] {9, 9}, "png");

        assertArrayEquals(new byte[] {9, 9}, store.read("v1", tile, "png").orElseThrow().bytes());
        try (var paths = Files.walk(root)) {
            assertTrue(
                    paths.noneMatch(path -> path.toString().endsWith(".tmp")),
                    "the atomic write must not leave a temp file behind");
        }
    }

    @Test
    void deletingATileMakesItMissingAgain() throws IOException {
        FileSystemTileStore store = new FileSystemTileStore(Files.createTempDirectory("tile-store-delete"));
        TileCoordinate tile = new TileCoordinate("minecraft:overworld", 4, 0, 0);
        store.write("v1", tile, TILE_BYTES, "png");

        store.delete("v1", tile, "png");

        assertFalse(store.exists("v1", tile, "png"));
        // Deleting an absent tile must stay a no-op rather than throw.
        store.delete("v1", tile, "png");
    }

    @Test
    void metadataIsPublishedAlongsideTheTiles() throws IOException {
        Path root = Files.createTempDirectory("tile-store-metadata");
        FileSystemTileStore store = new FileSystemTileStore(root);

        store.writeMetadata("v1", "{\"tilesetVersion\":\"v1\"}");

        Path metadata = root.resolve("tiles").resolve("v1").resolve("metadata.json");
        assertTrue(Files.exists(metadata));
        assertEquals("{\"tilesetVersion\":\"v1\"}", Files.readString(metadata));
    }

    @Test
    void dimensionIdentifiersAreMadeSafeForTheFilesystem() throws IOException {
        FileSystemTileStore store = new FileSystemTileStore(Files.createTempDirectory("tile-store-dimension"));
        TileCoordinate tile = new TileCoordinate("minecraft:overworld", 4, 0, 0);

        Path path = store.pathFor("v1", tile, "png");

        // A colon is not a legal path character on Windows.
        assertFalse(path.toString().contains("minecraft:overworld"));
        assertTrue(path.toString().contains("minecraft_overworld"));
    }
}
