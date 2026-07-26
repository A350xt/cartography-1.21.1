package com.liedowncraft.cartography.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.snapshot.SampledMapBuffer;
import com.liedowncraft.cartography.snapshot.SurfaceKind;

/**
 * Pins the renderer to vanilla's map shading (MapItem#update).
 *
 * <p>Slope: {@code (h - hNorth) * 4 / (blocksPerPixel + 4) + (((x+y)&1) - 0.5) * 0.4}, HIGH above
 * 0.6 and LOW below -0.6. Water: {@code depth * 0.1 + ((x+y)&1) * 0.2}, HIGH below 0.5 and LOW above
 * 0.9.
 */
class VanillaMapTileRendererTest {
    private final VanillaMapTileRenderer renderer = new VanillaMapTileRenderer();

    @Test
    void descendingTerrainProducesLowerBrightnessOnTheNextRow() {
        SampledMapBuffer buffer = SampledMapBuffer.builder(1, 2, 1)
                .set(0, 0, VanillaMapPalette.STONE, 12.0F, 0.0F, false)
                .set(0, 1, VanillaMapPalette.STONE, 0.0F, 0.0F, false)
                .build();

        BufferedImage image = renderer.renderImage(buffer);

        // Row 0 has no northern neighbour in an unpadded buffer, so its slope term is zero and it
        // falls in the NORMAL band. Row 1 drops 12 blocks, which is well past the LOW threshold.
        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.NORMAL), image.getRGB(0, 0));
        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.LOW), image.getRGB(0, 1));
    }

    @Test
    void ascendingTerrainProducesHigherBrightness() {
        SampledMapBuffer buffer = SampledMapBuffer.builder(1, 2, 1)
                .set(0, 0, VanillaMapPalette.STONE, 0.0F, 0.0F, false)
                .set(0, 1, VanillaMapPalette.STONE, 12.0F, 0.0F, false)
                .build();

        BufferedImage image = renderer.renderImage(buffer);

        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.HIGH), image.getRGB(0, 1));
    }

    @Test
    void paddingRowSeedsTheSlopeOfTheFirstPublishedRow() {
        // The padded row is real sampled terrain north of the tile. It is what lets the first
        // published row be shaded against its true neighbour instead of against nothing, which is
        // how seams between metatiles are avoided.
        SampledMapBuffer buffer = SampledMapBuffer.builder(3, 3, 1, 1)
                .setAt(0, 0, VanillaMapPalette.STONE, 12.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .setAt(1, 0, VanillaMapPalette.STONE, 12.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .setAt(2, 0, VanillaMapPalette.STONE, 12.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .setAt(0, 1, VanillaMapPalette.STONE, 0.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .setAt(1, 1, VanillaMapPalette.STONE, 0.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .setAt(2, 1, VanillaMapPalette.STONE, 0.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .setAt(0, 2, VanillaMapPalette.STONE, 0.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .setAt(1, 2, VanillaMapPalette.STONE, 0.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .setAt(2, 2, VanillaMapPalette.STONE, 0.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, 0)
                .build();

        BufferedImage image = renderer.renderImage(buffer);

        // Cropped down to the single published pixel, which sits below the 12-block drop.
        assertEquals(1, image.getWidth());
        assertEquals(1, image.getHeight());
        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.LOW), image.getRGB(0, 0));
    }

    @Test
    void deepWaterUsesVanillaStyleWaterBrightnessRules() {
        BufferedImage shallow = renderer.renderImage(SampledMapBuffer.builder(1, 1, 1)
                .set(0, 0, VanillaMapPalette.WATER, 0.0F, 0.0F, true)
                .build());
        BufferedImage deep = renderer.renderImage(SampledMapBuffer.builder(1, 1, 1)
                .set(0, 0, VanillaMapPalette.WATER, 0.0F, 12.0F, true)
                .build());

        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.WATER, VanillaMapPalette.Brightness.HIGH), shallow.getRGB(0, 0));
        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.WATER, VanillaMapPalette.Brightness.LOW), deep.getRGB(0, 0));
    }

    @Test
    void nonWaterFluidUsesSlopeShadingLikeVanilla() {
        // Vanilla keys depth shading on the dominant map colour being WATER, so lava is shaded by
        // slope. A fluid pixel with a lava colour must not be treated as deep water.
        SampledMapBuffer lava = SampledMapBuffer.builder(1, 1, 1)
                .setAt(0, 0, (byte) 4, 40.0F, 12.0F, SurfaceKind.FLUID_SURFACE, 0)
                .build();

        BufferedImage image = renderer.renderImage(lava);

        assertNotEquals(
                VanillaMapPalette.argb(4, VanillaMapPalette.Brightness.LOW),
                image.getRGB(0, 0),
                "lava must not be shaded by fluid depth");
    }

    @Test
    void transparentStructureOverlayBlendsOverTheSurfaceBelow() {
        // A glass roof over stone must show as tinted stone, not as bare stone and not as a solid
        // block of glass colour.
        int glassOverlay = 0x80FFFFFF;
        SampledMapBuffer buffer = SampledMapBuffer.builder(1, 1, 1)
                .setAt(0, 0, VanillaMapPalette.STONE, 64.0F, 0.0F, SurfaceKind.OPAQUE_SURFACE, glassOverlay)
                .build();

        int blended = renderer.renderImage(buffer).getRGB(0, 0);
        int bare = VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.NORMAL);

        assertNotEquals(bare, blended, "the covering must tint the surface below it");
        assertEquals(0xFF, blended >>> 24, "the composited pixel must stay fully opaque");
    }

    @Test
    void pendingTileIsFullyTransparentSoTheClientShowsAGapNotAColour() throws Exception {
        byte[] encoded = renderer.renderPendingTile(
                com.liedowncraft.cartography.config.CartographySettings
                        .forTests(java.nio.file.Path.of("build/test-pending"))
                        .renderer());

        BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(encoded));
        assertEquals(0, image.getRGB(0, 0) >>> 24, "pending tiles must be transparent");
    }
}
