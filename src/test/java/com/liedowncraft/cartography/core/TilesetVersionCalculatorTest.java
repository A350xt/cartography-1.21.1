package com.liedowncraft.cartography.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.config.RendererProfile;

/**
 * Technical plan v2.0 section 15.1 requires proving that changing the resource pack, renderer or
 * profile yields a new tileset version, since that version is what namespaces the tile URLs.
 */
class TilesetVersionCalculatorTest {
    private static final RendererProfile BASE = CartographySettings.forTests(Path.of("build/test-version")).renderer();

    @Test
    void versionIsStableForIdenticalInputs() {
        assertEquals(
                TilesetVersionCalculator.calculate("world", "minecraft:overworld", BASE),
                TilesetVersionCalculator.calculate("world", "minecraft:overworld", BASE));
    }

    @Test
    void everyCacheRelevantInputProducesANewNamespace() {
        String base = TilesetVersionCalculator.calculate("world", "minecraft:overworld", BASE);

        assertNotEquals(base, TilesetVersionCalculator.calculate("other-world", "minecraft:overworld", BASE),
                "a different world must not share a tileset namespace");
        assertNotEquals(base, TilesetVersionCalculator.calculate("world", "minecraft:the_nether", BASE),
                "a different dimension must not share a tileset namespace");
        assertNotEquals(base, TilesetVersionCalculator.calculate("world", "minecraft:overworld", withPack("modded-pack")),
                "a new resource pack must invalidate cached tiles");
        assertNotEquals(base, TilesetVersionCalculator.calculate("world", "minecraft:overworld", withRendererVersion("v3")),
                "a renderer change must invalidate cached tiles");
        assertNotEquals(base, TilesetVersionCalculator.calculate("world", "minecraft:overworld", withProfileId("vanilla")),
                "a different profile must not share a tileset namespace");
        assertNotEquals(base, TilesetVersionCalculator.calculate("world", "minecraft:overworld", withFormat("webp")),
                "a format change must invalidate cached tiles");
        assertNotEquals(base, TilesetVersionCalculator.calculate("world", "minecraft:overworld", withQuality(60)),
                "a quality change must invalidate cached tiles");
        assertNotEquals(base, TilesetVersionCalculator.calculate("world", "minecraft:overworld", withTileOrigin(64, 64)),
                "a tile grid change repartitions the world and must invalidate cached tiles");
        assertNotEquals(base, TilesetVersionCalculator.calculate("world", "minecraft:overworld", withHeightShade(false)),
                "a shading change alters every pixel and must invalidate cached tiles");
    }

    @Test
    void versionIsSixteenHexCharacters() {
        String version = TilesetVersionCalculator.calculate("world", "minecraft:overworld", BASE);

        assertEquals(16, version.length());
        assertEquals(version.toLowerCase(), version);
    }

    private static RendererProfile withPack(String pack) {
        return rebuild(BASE.profileId(), BASE.tileGrid(), pack, BASE.rendererCodeVersion(), BASE.format(),
                BASE.quality(), BASE.heightShade());
    }

    private static RendererProfile withRendererVersion(String version) {
        return rebuild(BASE.profileId(), BASE.tileGrid(), BASE.configuredPackSignature(), version, BASE.format(),
                BASE.quality(), BASE.heightShade());
    }

    private static RendererProfile withProfileId(String profileId) {
        return rebuild(profileId, BASE.tileGrid(), BASE.configuredPackSignature(), BASE.rendererCodeVersion(),
                BASE.format(), BASE.quality(), BASE.heightShade());
    }

    private static RendererProfile withFormat(String format) {
        return rebuild(BASE.profileId(), BASE.tileGrid(), BASE.configuredPackSignature(), BASE.rendererCodeVersion(),
                format, BASE.quality(), BASE.heightShade());
    }

    private static RendererProfile withQuality(int quality) {
        return rebuild(BASE.profileId(), BASE.tileGrid(), BASE.configuredPackSignature(), BASE.rendererCodeVersion(),
                BASE.format(), quality, BASE.heightShade());
    }

    private static RendererProfile withTileOrigin(int originX, int originZ) {
        TileGrid grid = BASE.tileGrid();
        return rebuild(
                BASE.profileId(),
                new TileGrid(grid.tileSize(), grid.minZoom(), grid.maxZoom(), grid.pixelsPerBlockAtMaxZoom(), originX, originZ),
                BASE.configuredPackSignature(),
                BASE.rendererCodeVersion(),
                BASE.format(),
                BASE.quality(),
                BASE.heightShade());
    }

    private static RendererProfile withHeightShade(boolean heightShade) {
        return rebuild(BASE.profileId(), BASE.tileGrid(), BASE.configuredPackSignature(), BASE.rendererCodeVersion(),
                BASE.format(), BASE.quality(), heightShade);
    }

    private static RendererProfile rebuild(
            String profileId,
            TileGrid grid,
            String pack,
            String rendererVersion,
            String format,
            int quality,
            boolean heightShade) {
        return new RendererProfile(
                profileId,
                grid,
                BASE.tileCoordinateMode(),
                BASE.metatileSize(),
                BASE.paddingBlocks(),
                rendererVersion,
                BASE.materialTableVersion(),
                pack,
                format,
                quality,
                heightShade,
                BASE.fluidDepth(),
                List.copyOf(BASE.dimensions()),
                BASE.defaultDimension());
    }
}
