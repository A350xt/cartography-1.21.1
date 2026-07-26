package com.liedowncraft.cartography.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pixel-to-block mapping across the zoom range.
 *
 * <p>The default profile renders at 2 pixels per block, so a 256px tile spans only 128 blocks and a
 * pixel covers *less* than one block at max zoom. Deriving block extents by multiplying a rounded
 * blocks-per-pixel ratio silently collapses to zero there, which would make every max-zoom metatile
 * sample a single column. These tests pin the exact lattice mapping instead.
 */
class TileGridPixelMappingTest {
    private static final TileGrid GRID = new TileGrid(256, 0, 8, 2, 0, 0);

    @Test
    void aPixelCoversLessThanABlockAtMaxZoom() {
        assertEquals(128, GRID.blocksPerTile(8));
        assertEquals(0.5, GRID.exactBlocksPerPixel(8));
        // Clamped to one, because a sample footprint and vanilla's shading divisor both need a whole
        // number of at least one.
        assertEquals(1, GRID.blocksPerPixel(8));
    }

    @Test
    void adjacentPixelsShareABlockWhenResolutionIsSubBlock() {
        // Two pixels per block at max zoom: pixels 0 and 1 both sample block 0.
        assertEquals(0, GRID.firstBlockOfPixel(8, 0));
        assertEquals(0, GRID.firstBlockOfPixel(8, 1));
        assertEquals(1, GRID.firstBlockOfPixel(8, 2));
        assertEquals(1, GRID.firstBlockOfPixel(8, 3));

        // Each still reports a span of one, so no sample loop degenerates to zero iterations.
        for (int pixel = 0; pixel < 8; pixel++) {
            assertEquals(1, GRID.blockSpanOfPixel(8, pixel), "pixel " + pixel);
        }
    }

    @Test
    void aPixelCoversManyBlocksAtLowZoom() {
        assertEquals(8, GRID.blocksPerPixel(4));
        assertEquals(0, GRID.firstBlockOfPixel(4, 0));
        assertEquals(8, GRID.firstBlockOfPixel(4, 1));
        assertEquals(16, GRID.firstBlockOfPixel(4, 2));
        assertEquals(8, GRID.blockSpanOfPixel(4, 0));
    }

    @Test
    void afullTileWidthOfPixelsCoversExactlyOneTileOfBlocks() {
        // The lattice must close: summing every pixel's start position across a tile has to land on
        // the tile's block width, at every zoom.
        for (int zoom = GRID.minZoom(); zoom <= GRID.maxZoom(); zoom++) {
            assertEquals(
                    GRID.blocksPerTile(zoom),
                    GRID.firstBlockOfPixel(zoom, GRID.tileSize()),
                    "zoom " + zoom + " must map a full tile of pixels onto a full tile of blocks");
        }
    }

    @Test
    void pixelStartsAreMonotonicAndNeverSkipBlocks() {
        for (int zoom = GRID.minZoom(); zoom <= GRID.maxZoom(); zoom++) {
            int previous = GRID.firstBlockOfPixel(zoom, 0);
            for (int pixel = 1; pixel <= GRID.tileSize(); pixel++) {
                int current = GRID.firstBlockOfPixel(zoom, pixel);
                assertTrue(current >= previous, "zoom " + zoom + " pixel " + pixel + " went backwards");
                // A gap would leave blocks unrendered between adjacent pixels.
                assertTrue(
                        current - previous <= GRID.blocksPerPixel(zoom),
                        "zoom " + zoom + " pixel " + pixel + " skipped blocks");
                previous = current;
            }
        }
    }

    @Test
    void aTileGridMustNotBeConstructedWithIndivisiblePixelScale() {
        // 256 / 3 is not an integer, so blocks per tile would not be whole and the lattice could not
        // close. Rejecting it at construction keeps every downstream calculation exact.
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new TileGrid(256, 0, 8, 3, 0, 0));
    }
}
