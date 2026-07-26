package com.liedowncraft.cartography.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/**
 * Low-zoom tiles are built by downsampling children (technical plan v2.0, section 6.4).
 *
 * <p>Quadrant order must match {@code TileMath.childrenOf}, or the pyramid renders mirrored as you
 * zoom out.
 */
class TileDownsamplerTest {
    private static final int TILE_SIZE = 16;

    @Test
    void eachChildLandsInItsOwnQuadrant() {
        // childrenOf order is (x,y): top-left, top-right, bottom-left, bottom-right.
        BufferedImage[] children = {
            solid(0xFFFF0000),
            solid(0xFF00FF00),
            solid(0xFF0000FF),
            solid(0xFFFFFF00),
        };

        BufferedImage parent = TileDownsampler.downsample(children, TILE_SIZE);

        int quarter = TILE_SIZE / 4;
        int threeQuarters = TILE_SIZE * 3 / 4;
        assertEquals(0xFFFF0000, parent.getRGB(quarter, quarter), "top-left");
        assertEquals(0xFF00FF00, parent.getRGB(threeQuarters, quarter), "top-right");
        assertEquals(0xFF0000FF, parent.getRGB(quarter, threeQuarters), "bottom-left");
        assertEquals(0xFFFFFF00, parent.getRGB(threeQuarters, threeQuarters), "bottom-right");
    }

    @Test
    void aMissingChildLeavesItsQuadrantTransparent() {
        // A not-yet-rendered region must read as absent, not as black, so the client shows a gap.
        BufferedImage[] children = {solid(0xFFFF0000), null, null, null};

        BufferedImage parent = TileDownsampler.downsample(children, TILE_SIZE);

        assertEquals(0xFF, parent.getRGB(TILE_SIZE / 4, TILE_SIZE / 4) >>> 24, "rendered quadrant stays opaque");
        assertEquals(0, parent.getRGB(TILE_SIZE * 3 / 4, TILE_SIZE / 4) >>> 24, "missing quadrant must be transparent");
    }

    @Test
    void allChildrenMissingProducesAFullyTransparentTile() {
        BufferedImage parent = TileDownsampler.downsample(new BufferedImage[4], TILE_SIZE);

        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                assertEquals(0, parent.getRGB(x, y) >>> 24);
            }
        }
    }

    @Test
    void outputIsAlwaysASingleTile() {
        BufferedImage parent = TileDownsampler.downsample(
                new BufferedImage[] {solid(0xFF112233), solid(0xFF112233), solid(0xFF112233), solid(0xFF112233)},
                TILE_SIZE);

        assertEquals(TILE_SIZE, parent.getWidth());
        assertEquals(TILE_SIZE, parent.getHeight());
    }

    @Test
    void aWrongQuadrantCountIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TileDownsampler.downsample(new BufferedImage[3], TILE_SIZE));
    }

    private static BufferedImage solid(int argb) {
        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }
}
