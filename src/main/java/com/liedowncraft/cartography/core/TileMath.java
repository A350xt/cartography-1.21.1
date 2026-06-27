package com.liedowncraft.cartography.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.liedowncraft.cartography.config.RendererProfile;

public final class TileMath {
    private static final int CHUNK_SIZE = 16;

    private TileMath() {
    }

    public static int blocksPerTile(int zoom, RendererProfile profile) {
        int zoomDelta = profile.maxZoom() - zoom;
        int blocksAtMaxZoom = profile.blocksPerTileAtMaxZoom();
        return blocksAtMaxZoom << Math.max(zoomDelta, 0);
    }

    public static TileCoordinate blockToTile(String dimension, int zoom, int blockX, int blockZ, RendererProfile profile) {
        int blocksPerTile = blocksPerTile(zoom, profile);
        return new TileCoordinate(
                dimension,
                zoom,
                Math.floorDiv(blockX, blocksPerTile),
                Math.floorDiv(blockZ, blocksPerTile));
    }

    public static TileBounds tileToBlockBounds(TileCoordinate tile, RendererProfile profile) {
        int blocksPerTile = blocksPerTile(tile.zoom(), profile);
        int minBlockX = tile.x() * blocksPerTile;
        int minBlockZ = tile.y() * blocksPerTile;
        return new TileBounds(minBlockX, minBlockZ, minBlockX + blocksPerTile, minBlockZ + blocksPerTile);
    }

    public static Set<TileCoordinate> dirtyChunkToTiles(String dimension, int chunkX, int chunkZ, RendererProfile profile) {
        int minBlockX = chunkX * CHUNK_SIZE;
        int minBlockZ = chunkZ * CHUNK_SIZE;
        int maxBlockX = minBlockX + CHUNK_SIZE - 1;
        int maxBlockZ = minBlockZ + CHUNK_SIZE - 1;

        TileCoordinate topLeft = blockToTile(dimension, profile.maxZoom(), minBlockX, minBlockZ, profile);
        TileCoordinate topRight = blockToTile(dimension, profile.maxZoom(), maxBlockX, minBlockZ, profile);
        TileCoordinate bottomLeft = blockToTile(dimension, profile.maxZoom(), minBlockX, maxBlockZ, profile);
        TileCoordinate bottomRight = blockToTile(dimension, profile.maxZoom(), maxBlockX, maxBlockZ, profile);

        Set<TileCoordinate> coordinates = new LinkedHashSet<>();
        coordinates.add(topLeft);
        coordinates.add(topRight);
        coordinates.add(bottomLeft);
        coordinates.add(bottomRight);
        return coordinates;
    }

    public static MetatileJob groupIntoMetatile(TileCoordinate tile, RendererProfile profile) {
        return groupIntoMetatile("", tile, profile);
    }

    public static MetatileJob groupIntoMetatile(String tilesetVersion, TileCoordinate tile, RendererProfile profile) {
        int metatileSize = profile.metatileSize();
        int startX = Math.floorDiv(tile.x(), metatileSize) * metatileSize;
        int startY = Math.floorDiv(tile.y(), metatileSize) * metatileSize;
        return new MetatileJob(tilesetVersion, tile.dimension(), tile.zoom(), startX, startY, metatileSize);
    }

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
}
