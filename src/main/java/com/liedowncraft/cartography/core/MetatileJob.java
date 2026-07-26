package com.liedowncraft.cartography.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A unit of render scheduling (technical plan v2.0, section 6.3).
 *
 * <p>Individual 256px tiles are deliberately not the scheduling unit. Rendering a metatile block of
 * tiles in one pass keeps shadows, fluid compositing and edge sampling consistent, so seams do not
 * appear between tiles that were rendered at different times.
 */
public record MetatileJob(String tilesetVersion, String dimension, int zoom, int startX, int startY, int tileCount) {

    public MetatileJob {
        if (tileCount <= 0) {
            throw new IllegalArgumentException("tileCount must be positive");
        }
    }

    public List<TileCoordinate> tiles() {
        List<TileCoordinate> tiles = new ArrayList<>(tileCount * tileCount);
        for (int offsetY = 0; offsetY < tileCount; offsetY++) {
            for (int offsetX = 0; offsetX < tileCount; offsetX++) {
                tiles.add(new TileCoordinate(dimension, zoom, startX + offsetX, startY + offsetY));
            }
        }
        return tiles;
    }

    /** Block bounds of the metatile before padding is applied. */
    public TileBounds blockBounds(TileGrid grid) {
        int blocksPerTile = grid.blocksPerTile(zoom);
        int minBlockX = grid.tileOriginX() + startX * blocksPerTile;
        int minBlockZ = grid.tileOriginZ() + startY * blocksPerTile;
        return new TileBounds(
                minBlockX,
                minBlockZ,
                minBlockX + blocksPerTile * tileCount,
                minBlockZ + blocksPerTile * tileCount);
    }

    /**
     * Block bounds expanded by {@code paddingBlocks} on every side (plan section 6.3). Sampling the
     * padding lets slope shading at the metatile edge see its true neighbour instead of falling back
     * to a default, which is what would otherwise leave a visible seam.
     */
    public TileBounds paddedBlockBounds(TileGrid grid, int paddingBlocks) {
        TileBounds bounds = blockBounds(grid);
        return new TileBounds(
                bounds.minBlockX() - paddingBlocks,
                bounds.minBlockZ() - paddingBlocks,
                bounds.maxBlockXExclusive() + paddingBlocks,
                bounds.maxBlockZExclusive() + paddingBlocks);
    }
}
