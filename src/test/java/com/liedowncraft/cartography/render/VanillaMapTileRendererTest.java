package com.liedowncraft.cartography.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.snapshot.SampledMapBuffer;

class VanillaMapTileRendererTest {
    @Test
    void descendingTerrainProducesLowerBrightnessOnTheNextRow() {
        SampledMapBuffer buffer = SampledMapBuffer.builder(1, 2, 1)
                .set(0, 0, VanillaMapPalette.STONE, 12.0F, 0.0F, false)
                .set(0, 1, VanillaMapPalette.STONE, 0.0F, 0.0F, false)
                .build();

        VanillaMapTileRenderer renderer = new VanillaMapTileRenderer();

        BufferedImage image = renderer.renderImage(buffer);

        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.HIGH), image.getRGB(0, 0));
        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.STONE, VanillaMapPalette.Brightness.LOW), image.getRGB(0, 1));
    }

    @Test
    void deepWaterUsesVanillaStyleWaterBrightnessRules() {
        SampledMapBuffer shallowWater = SampledMapBuffer.builder(1, 1, 1)
                .set(0, 0, VanillaMapPalette.WATER, 0.0F, 0.0F, true)
                .build();
        SampledMapBuffer deepWater = SampledMapBuffer.builder(1, 1, 1)
                .set(0, 0, VanillaMapPalette.WATER, 0.0F, 12.0F, true)
                .build();

        VanillaMapTileRenderer renderer = new VanillaMapTileRenderer();

        BufferedImage shallowImage = renderer.renderImage(shallowWater);
        BufferedImage deepImage = renderer.renderImage(deepWater);

        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.WATER, VanillaMapPalette.Brightness.HIGH), shallowImage.getRGB(0, 0));
        assertEquals(VanillaMapPalette.argb(VanillaMapPalette.WATER, VanillaMapPalette.Brightness.LOW), deepImage.getRGB(0, 0));
    }
}
