package com.liedowncraft.cartography.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Technical plan v2.0 section 15.1 lists the coordinate round-trip as a mandatory invariant:
 * MC block -> view pixel -> signed tile -> normalized publish tile -> MC block.
 *
 * <p>The failure mode this guards is features drifting off the basemap and negative coordinates
 * landing in the wrong tile.
 */
class CoordinateRoundTripTest {
    private static final TileGrid GRID = new TileGrid(256, 0, 8, 2, 0, 0);

    @Test
    void negativeBlockCoordinatesLandInTheTileThatContainsThem() {
        // 128 blocks per tile at max zoom: 256px tile / 2px per block.
        assertEquals(128, GRID.blocksPerTileAtMaxZoom());

        // Block -1 must fall in tile -1, not tile 0. Integer division would get this wrong.
        assertEquals(-1, GRID.signedTileX(8, -1));
        assertEquals(-1, GRID.signedTileX(8, -128));
        assertEquals(-2, GRID.signedTileX(8, -129));
        assertEquals(0, GRID.signedTileX(8, 0));
        assertEquals(0, GRID.signedTileX(8, 127));
        assertEquals(1, GRID.signedTileX(8, 128));
    }

    @Test
    void everyProbeRoundTripsThroughViewPixelSignedTileAndNormalizedTile() {
        TileGridNormalization normalization = new TileGridNormalization(-183, -96, 220, 144);

        int[][] probes = {
            {0, 0},
            {1, 1},
            {-1, -1},
            {127, 127},
            {128, 128},
            {-128, -128},
            {-129, -129},
            {513, -129},
            {-10000, -10000},
            {23456, -7891},
        };

        for (int[] probe : probes) {
            int blockX = probe[0];
            int blockZ = probe[1];

            // MC block -> max-zoom view pixel.
            double pixelX = GRID.pixelXAtMaxZoom(blockX);
            double pixelY = GRID.pixelYAtMaxZoom(blockZ);

            // view pixel -> signed tile, exactly as the plan's section 4.2 formula defines it.
            int signedTileX = (int) Math.floor(pixelX / GRID.tileSize());
            int signedTileY = (int) Math.floor(pixelY / GRID.tileSize());
            assertEquals(signedTileX, GRID.signedTileX(GRID.maxZoom(), blockX), "signed tile X for block " + blockX);
            assertEquals(signedTileY, GRID.signedTileY(GRID.maxZoom(), blockZ), "signed tile Y for block " + blockZ);

            // signed tile -> normalized publish tile -> signed tile.
            TileCoordinate signedTile = new TileCoordinate("minecraft:overworld", GRID.maxZoom(), signedTileX, signedTileY);
            TileCoordinate normalized = normalization.toNormalized(signedTile);
            assertTrue(normalized.x() >= 0 && normalized.y() >= 0, "published tiles must be non-negative: " + normalized);
            assertEquals(signedTile, normalization.toSigned(normalized), "normalized tile must map back to its signed tile");

            // signed tile -> MC block bounds must still contain the block we started from.
            TileBounds bounds = GRID.tileToBlockBounds(signedTile);
            assertTrue(
                    bounds.contains(blockX, blockZ),
                    "tile " + signedTile + " bounds " + bounds + " must contain block " + blockX + "," + blockZ);
        }
    }

    @Test
    void roundTripHoldsWhenTileOriginIsNotAtWorldOrigin() {
        TileGrid shifted = new TileGrid(256, 0, 8, 2, -512, 384);

        // A non-zero tile origin only moves the tile lattice; it must never move data coordinates.
        TileCoordinate tile = shifted.blockToTile("minecraft:overworld", 8, -512, 384);
        assertEquals(0, tile.x());
        assertEquals(0, tile.y());

        TileBounds bounds = shifted.tileToBlockBounds(tile);
        assertEquals(-512, bounds.minBlockX());
        assertEquals(384, bounds.minBlockZ());
        assertTrue(bounds.contains(-512, 384));
        assertTrue(bounds.contains(-385, 511));
        assertTrue(shifted.tileToBlockBounds(shifted.blockToTile("minecraft:overworld", 8, -513, 383)).contains(-513, 383));
    }

    @Test
    void everyZoomLevelContainsTheBlockItClaims() {
        for (int zoom = GRID.minZoom(); zoom <= GRID.maxZoom(); zoom++) {
            for (int blockX : new int[] {-10000, -129, -1, 0, 1, 127, 5000}) {
                TileCoordinate tile = GRID.blockToTile("minecraft:overworld", zoom, blockX, blockX);
                assertTrue(
                        GRID.tileToBlockBounds(tile).contains(blockX, blockX),
                        "zoom " + zoom + " tile " + tile + " must contain block " + blockX);
            }
        }
    }

    @Test
    void ancestorOfATileCoversTheSameBlocks() {
        // Guards the zoom pyramid: a parent tile must geographically contain its child.
        TileCoordinate leaf = GRID.blockToTile("minecraft:overworld", GRID.maxZoom(), -10000, -10000);
        TileBounds leafBounds = GRID.tileToBlockBounds(leaf);

        for (TileCoordinate ancestor : TileMath.ancestorChain(leaf, GRID.minZoom())) {
            TileBounds ancestorBounds = GRID.tileToBlockBounds(ancestor);
            assertTrue(
                    ancestorBounds.contains(leafBounds.minBlockX(), leafBounds.minBlockZ())
                            && ancestorBounds.contains(leafBounds.maxBlockXExclusive() - 1, leafBounds.maxBlockZExclusive() - 1),
                    "ancestor " + ancestor + " bounds " + ancestorBounds + " must cover leaf bounds " + leafBounds);
        }
    }
}
