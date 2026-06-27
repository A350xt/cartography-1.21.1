package com.liedowncraft.cartography.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.config.RendererProfile;

class TileMathTest {
    private static final RendererProfile PROFILE = new RendererProfile(
            256,
            0,
            4,
            1,
            4,
            "bootstrap-v1",
            "vanilla",
            "default-pack",
            List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
            "minecraft:overworld");

    @Test
    void blockCoordinatesResolveToContainingTileBounds() {
        TileCoordinate tile = TileMath.blockToTile("minecraft:overworld", 4, 513, -129, PROFILE);

        TileBounds bounds = TileMath.tileToBlockBounds(tile, PROFILE);

        assertTrue(bounds.contains(513, -129));
        assertEquals(256, TileMath.blocksPerTile(4, PROFILE));
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
    void ancestorInvalidationCoversAllParentZooms() {
        TileCoordinate leaf = new TileCoordinate("minecraft:overworld", 4, 12, 9);

        List<TileCoordinate> ancestors = TileMath.ancestorChain(leaf, PROFILE.minZoom());

        assertEquals(4, ancestors.size());
        assertEquals(new TileCoordinate("minecraft:overworld", 3, 6, 4), ancestors.get(0));
        assertEquals(new TileCoordinate("minecraft:overworld", 0, 0, 0), ancestors.get(3));
    }
}
