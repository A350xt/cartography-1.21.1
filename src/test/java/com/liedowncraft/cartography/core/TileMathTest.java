package com.liedowncraft.cartography.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.config.RendererProfile;

class TileMathTest {
    private static final RendererProfile PROFILE = CartographySettings.forTests(Path.of("build/test-tilemath")).renderer();

    @Test
    void blockCoordinatesResolveToContainingTileBounds() {
        TileCoordinate tile = TileMath.blockToTile("minecraft:overworld", PROFILE.maxZoom(), 513, -129, PROFILE);

        TileBounds bounds = TileMath.tileToBlockBounds(tile, PROFILE);

        assertTrue(bounds.contains(513, -129));
        assertEquals(128, TileMath.blocksPerTile(PROFILE.maxZoom(), PROFILE));
    }

    @Test
    void dirtyChunkMapsIntoExpectedMetatile() {
        Set<TileCoordinate> affectedTiles = TileMath.dirtyChunkToTiles("minecraft:overworld", 0, 0, PROFILE);

        assertEquals(1, affectedTiles.size());

        MetatileJob metatile = TileMath.groupIntoMetatile(affectedTiles.iterator().next(), PROFILE);

        assertEquals(4, metatile.tileCount());
        assertEquals(0, metatile.startX());
        assertEquals(0, metatile.startY());
    }

    @Test
    void negativeChunksGroupIntoTheMetatileBelowTheOrigin() {
        // floorDiv, not integer division: chunk -1 must land in the metatile starting at -4, not 0.
        Set<TileCoordinate> affectedTiles = TileMath.dirtyChunkToTiles("minecraft:overworld", -1, -1, PROFILE);
        TileCoordinate tile = affectedTiles.iterator().next();

        assertEquals(-1, tile.x());
        assertEquals(-1, tile.y());

        MetatileJob metatile = TileMath.groupIntoMetatile(tile, PROFILE);
        assertEquals(-4, metatile.startX());
        assertEquals(-4, metatile.startY());
    }

    @Test
    void boundingBoxSpanningManyTilesReturnsEveryOverlappedTile() {
        // A box wider than two tiles must not be approximated by its four corners.
        TileBounds bounds = new TileBounds(0, 0, 512, 384);

        Set<TileCoordinate> tiles = TileMath.bboxToTiles("minecraft:overworld", bounds, PROFILE.maxZoom(), PROFILE);

        // 512 blocks / 128 per tile = 4 columns; 384 / 128 = 3 rows.
        assertEquals(12, tiles.size());
    }

    @Test
    void ancestorInvalidationCoversAllParentZooms() {
        TileCoordinate leaf = new TileCoordinate("minecraft:overworld", 4, 12, 9);

        List<TileCoordinate> ancestors = TileMath.ancestorChain(leaf, 0);

        assertEquals(4, ancestors.size());
        assertEquals(new TileCoordinate("minecraft:overworld", 3, 6, 4), ancestors.get(0));
        assertEquals(new TileCoordinate("minecraft:overworld", 0, 0, 0), ancestors.get(3));
    }

    @Test
    void childrenOfATileAreItsFourQuadrants() {
        TileCoordinate parent = new TileCoordinate("minecraft:overworld", 2, 3, -2);

        List<TileCoordinate> children = TileMath.childrenOf(parent, 4);

        assertEquals(
                List.of(
                        new TileCoordinate("minecraft:overworld", 3, 6, -4),
                        new TileCoordinate("minecraft:overworld", 3, 7, -4),
                        new TileCoordinate("minecraft:overworld", 3, 6, -3),
                        new TileCoordinate("minecraft:overworld", 3, 7, -3)),
                children);
        assertTrue(TileMath.childrenOf(new TileCoordinate("minecraft:overworld", 4, 0, 0), 4).isEmpty());
    }

    @Test
    void parentAndChildAreInverses() {
        TileCoordinate child = new TileCoordinate("minecraft:overworld", 3, -5, 7);

        TileCoordinate parent = TileMath.parentOf(child, 0).orElseThrow();

        assertTrue(TileMath.childrenOf(parent, 4).contains(child));
        assertTrue(TileMath.parentOf(new TileCoordinate("minecraft:overworld", 0, 0, 0), 0).isEmpty());
    }

    @Test
    void metatilePaddingExpandsBoundsOnEverySide() {
        TileGrid grid = PROFILE.tileGrid();
        MetatileJob job = new MetatileJob("v", "minecraft:overworld", PROFILE.maxZoom(), 0, 0, 4);

        TileBounds bounds = job.blockBounds(grid);
        TileBounds padded = job.paddedBlockBounds(grid, 2);

        assertEquals(bounds.minBlockX() - 2, padded.minBlockX());
        assertEquals(bounds.minBlockZ() - 2, padded.minBlockZ());
        assertEquals(bounds.maxBlockXExclusive() + 2, padded.maxBlockXExclusive());
        assertEquals(bounds.maxBlockZExclusive() + 2, padded.maxBlockZExclusive());
    }
}
