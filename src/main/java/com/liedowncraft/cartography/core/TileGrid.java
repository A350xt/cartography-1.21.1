package com.liedowncraft.cartography.core;

/**
 * Canonical Cartography tile grid (technical plan v2.0, section 4).
 *
 * <p>Data coordinates are always raw Minecraft block coordinates. The display origin lives here and
 * in the frontend view transform only, never in stored geometry, so changing spawn or republishing
 * the map cannot shift existing data.
 */
public record TileGrid(
        int tileSize,
        int minZoom,
        int maxZoom,
        int pixelsPerBlockAtMaxZoom,
        int tileOriginX,
        int tileOriginZ) {

    public TileGrid {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be positive");
        }
        if (pixelsPerBlockAtMaxZoom <= 0) {
            throw new IllegalArgumentException("pixelsPerBlockAtMaxZoom must be positive");
        }
        if (minZoom < 0 || maxZoom < minZoom) {
            throw new IllegalArgumentException("expected 0 <= minZoom <= maxZoom");
        }
        if (tileSize % pixelsPerBlockAtMaxZoom != 0) {
            throw new IllegalArgumentException("tileSize must be divisible by pixelsPerBlockAtMaxZoom");
        }
    }

    /** Blocks covered by one tile edge at max zoom. */
    public int blocksPerTileAtMaxZoom() {
        return tileSize / pixelsPerBlockAtMaxZoom;
    }

    /** Blocks covered by one tile edge at {@code zoom}; each zoom step out doubles the footprint. */
    public int blocksPerTile(int zoom) {
        return blocksPerTileAtMaxZoom() << Math.max(maxZoom - zoom, 0);
    }

    /**
     * Blocks covered by one rendered pixel at {@code zoom}, rounded up to at least one.
     *
     * <p>At high zoom a pixel can cover less than a whole block: with 2 pixels per block, a 256px
     * tile spans only 128 blocks. The true ratio is therefore fractional there, and this returns 1.
     * Use {@link #firstBlockOfPixel} to find the exact block a pixel samples; this value is for
     * sizing a sample footprint and for vanilla's shading divisor, both of which need a whole number
     * of at least one.
     */
    public int blocksPerPixel(int zoom) {
        return Math.max(1, blocksPerTile(zoom) / tileSize);
    }

    /** True blocks-per-pixel ratio at {@code zoom}; below 1 when a block spans several pixels. */
    public double exactBlocksPerPixel(int zoom) {
        return (double) blocksPerTile(zoom) / tileSize;
    }

    /**
     * First block covered by pixel {@code pixelIndex} of a tile row, relative to the row's first
     * block.
     *
     * <p>Computed from the pixel index rather than by multiplying a rounded ratio, so it stays exact
     * whether a pixel covers many blocks or several pixels share one.
     */
    public int firstBlockOfPixel(int zoom, int pixelIndex) {
        return Math.floorDiv(pixelIndex * blocksPerTile(zoom), tileSize);
    }

    /** Number of blocks pixel {@code pixelIndex} covers; at least one. */
    public int blockSpanOfPixel(int zoom, int pixelIndex) {
        int start = firstBlockOfPixel(zoom, pixelIndex);
        int end = firstBlockOfPixel(zoom, pixelIndex + 1);
        return Math.max(1, end - start);
    }

    /** Max-zoom pixel X for a data coordinate, per plan section 4.2. */
    public double pixelXAtMaxZoom(double dataX) {
        return (dataX - tileOriginX) * pixelsPerBlockAtMaxZoom;
    }

    /** Max-zoom pixel Y for a data coordinate. Minecraft Z maps to the tile Y axis. */
    public double pixelYAtMaxZoom(double dataZ) {
        return (dataZ - tileOriginZ) * pixelsPerBlockAtMaxZoom;
    }

    /**
     * Signed tile X containing a data coordinate. Equivalent to
     * {@code floor(pixelXAtMaxZoom(dataX) / tileSize)} at max zoom, but computed in block space so
     * negative coordinates stay exact.
     */
    public int signedTileX(int zoom, int dataX) {
        return Math.floorDiv(dataX - tileOriginX, blocksPerTile(zoom));
    }

    /** Signed tile Y containing a data coordinate. */
    public int signedTileY(int zoom, int dataZ) {
        return Math.floorDiv(dataZ - tileOriginZ, blocksPerTile(zoom));
    }

    public TileCoordinate blockToTile(String dimension, int zoom, int dataX, int dataZ) {
        return new TileCoordinate(dimension, zoom, signedTileX(zoom, dataX), signedTileY(zoom, dataZ));
    }

    /** World-space block bounds covered by a signed tile. */
    public TileBounds tileToBlockBounds(TileCoordinate tile) {
        int blocksPerTile = blocksPerTile(tile.zoom());
        int minBlockX = tileOriginX + tile.x() * blocksPerTile;
        int minBlockZ = tileOriginZ + tile.y() * blocksPerTile;
        return new TileBounds(minBlockX, minBlockZ, minBlockX + blocksPerTile, minBlockZ + blocksPerTile);
    }
}
