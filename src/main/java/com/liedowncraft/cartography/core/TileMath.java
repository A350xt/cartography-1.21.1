package com.liedowncraft.cartography.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.liedowncraft.cartography.config.RendererProfile;

/**
 * Dirty-chunk to metatile planning (technical plan v2.0, section 6).
 *
 * <p>Coordinate conversions live on {@link TileGrid}; this class only handles the scheduling shapes
 * built on top of them.
 */
public final class TileMath {
    private static final int CHUNK_SIZE = 16;

    private TileMath() {
    }

    public static int blocksPerTile(int zoom, RendererProfile profile) {
        return profile.tileGrid().blocksPerTile(zoom);
    }

    public static TileCoordinate blockToTile(String dimension, int zoom, int blockX, int blockZ, RendererProfile profile) {
        return profile.tileGrid().blockToTile(dimension, zoom, blockX, blockZ);
    }

    public static TileBounds tileToBlockBounds(TileCoordinate tile, RendererProfile profile) {
        return profile.tileGrid().tileToBlockBounds(tile);
    }

    /** Max-zoom tiles overlapped by a dirty chunk. */
    public static Set<TileCoordinate> dirtyChunkToTiles(String dimension, int chunkX, int chunkZ, RendererProfile profile) {
        int minBlockX = chunkX * CHUNK_SIZE;
        int minBlockZ = chunkZ * CHUNK_SIZE;
        return bboxToTiles(
                dimension,
                new TileBounds(minBlockX, minBlockZ, minBlockX + CHUNK_SIZE, minBlockZ + CHUNK_SIZE),
                profile.maxZoom(),
                profile);
    }

    /**
     * Every tile at {@code zoom} overlapping a block-space bounding box. Unlike sampling only the
     * four corners, this stays correct when the box spans more than two tiles on an axis.
     */
    public static Set<TileCoordinate> bboxToTiles(String dimension, TileBounds bounds, int zoom, RendererProfile profile) {
        TileGrid grid = profile.tileGrid();
        int minTileX = grid.signedTileX(zoom, bounds.minBlockX());
        int maxTileX = grid.signedTileX(zoom, bounds.maxBlockXExclusive() - 1);
        int minTileY = grid.signedTileY(zoom, bounds.minBlockZ());
        int maxTileY = grid.signedTileY(zoom, bounds.maxBlockZExclusive() - 1);

        Set<TileCoordinate> tiles = new LinkedHashSet<>();
        for (int tileY = minTileY; tileY <= maxTileY; tileY++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                tiles.add(new TileCoordinate(dimension, zoom, tileX, tileY));
            }
        }
        return tiles;
    }

    public static MetatileJob groupIntoMetatile(TileCoordinate tile, RendererProfile profile) {
        return groupIntoMetatile("", tile, profile);
    }

    /** Snaps a tile onto the metatile lattice so concurrent dirty tiles collapse into one job. */
    public static MetatileJob groupIntoMetatile(String tilesetVersion, TileCoordinate tile, RendererProfile profile) {
        int metatileSize = profile.metatileSize();
        int startX = Math.floorDiv(tile.x(), metatileSize) * metatileSize;
        int startY = Math.floorDiv(tile.y(), metatileSize) * metatileSize;
        return new MetatileJob(tilesetVersion, tile.dimension(), tile.zoom(), startX, startY, metatileSize);
    }

    /** Parent chain from a tile up to {@code minZoom}, nearest parent first (plan section 6.4). */
    public static List<TileCoordinate> ancestorChain(TileCoordinate leaf, int minZoom) {
        List<TileCoordinate> ancestors = new ArrayList<>();
        int currentZoom = leaf.zoom();
        int currentX = leaf.x();
        int currentY = leaf.y();
        while (currentZoom > minZoom) {
            currentZoom -= 1;
            currentX = Math.floorDiv(currentX, 2);
            currentY = Math.floorDiv(currentY, 2);
            ancestors.add(new TileCoordinate(leaf.dimension(), currentZoom, currentX, currentY));
        }
        return ancestors;
    }

    /** Immediate parent, or empty at {@code minZoom}. */
    public static java.util.Optional<TileCoordinate> parentOf(TileCoordinate tile, int minZoom) {
        if (tile.zoom() <= minZoom) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new TileCoordinate(
                tile.dimension(),
                tile.zoom() - 1,
                Math.floorDiv(tile.x(), 2),
                Math.floorDiv(tile.y(), 2)));
    }

    /** The four children of a tile, in (x, y) quadrant order, used by the downsample pass. */
    public static List<TileCoordinate> childrenOf(TileCoordinate tile, int maxZoom) {
        if (tile.zoom() >= maxZoom) {
            return List.of();
        }

        int childZoom = tile.zoom() + 1;
        int baseX = tile.x() * 2;
        int baseY = tile.y() * 2;
        return List.of(
                new TileCoordinate(tile.dimension(), childZoom, baseX, baseY),
                new TileCoordinate(tile.dimension(), childZoom, baseX + 1, baseY),
                new TileCoordinate(tile.dimension(), childZoom, baseX, baseY + 1),
                new TileCoordinate(tile.dimension(), childZoom, baseX + 1, baseY + 1));
    }
}
