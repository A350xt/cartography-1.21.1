package com.liedowncraft.cartography.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.config.RendererProfile;

class TilesetVersionCalculatorTest {
    @Test
    void versionChangesWhenPackSignatureChanges() {
        RendererProfile base = new RendererProfile(
                256,
                0,
                4,
                1,
                4,
                "bootstrap-v1",
                "vanilla",
                "default-pack",
                List.of("minecraft:overworld"),
                "minecraft:overworld");
        RendererProfile changedPack = new RendererProfile(
                256,
                0,
                4,
                1,
                4,
                "bootstrap-v1",
                "vanilla",
                "modded-pack",
                List.of("minecraft:overworld"),
                "minecraft:overworld");

        String baseVersion = TilesetVersionCalculator.calculate(base);
        String changedPackVersion = TilesetVersionCalculator.calculate(changedPack);

        assertNotEquals(baseVersion, changedPackVersion);
        assertEquals(baseVersion, TilesetVersionCalculator.calculate(base));
    }
}
