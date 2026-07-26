package com.liedowncraft.cartography.config;

import java.util.List;

import com.liedowncraft.cartography.core.TileCoordinateMode;
import com.liedowncraft.cartography.core.TileGrid;

/**
 * Render profile plus the tile grid it publishes into (technical plan v2.0, sections 4 and 5.5).
 *
 * <p>Everything that can change what a rendered pixel looks like belongs here, because the tileset
 * version hashes this record and the tile URL namespace carries that hash.
 */
public record RendererProfile(
        String profileId,
        TileGrid tileGrid,
        TileCoordinateMode tileCoordinateMode,
        int metatileSize,
        int paddingBlocks,
        String rendererCodeVersion,
        String materialTableVersion,
        String configuredPackSignature,
        String format,
        int quality,
        boolean heightShade,
        boolean fluidDepth,
        List<String> dimensions,
        String defaultDimension) {

    public RendererProfile {
        if (metatileSize <= 0) {
            throw new IllegalArgumentException("metatileSize must be positive");
        }
        if (paddingBlocks < 0) {
            throw new IllegalArgumentException("paddingBlocks must not be negative");
        }
        dimensions = List.copyOf(dimensions);
    }

    public int tileSize() {
        return tileGrid.tileSize();
    }

    public int minZoom() {
        return tileGrid.minZoom();
    }

    public int maxZoom() {
        return tileGrid.maxZoom();
    }

    public int pixelsPerBlockAtMaxZoom() {
        return tileGrid.pixelsPerBlockAtMaxZoom();
    }

    public int blocksPerTileAtMaxZoom() {
        return tileGrid.blocksPerTileAtMaxZoom();
    }

    /**
     * File extension for rendered tiles.
     *
     * <p>Always PNG. The plan nominates WebP, but stock Java ships no WebP writer, and on flat-shaded
     * palette tiles indexed PNG measures smaller than lossy WebP while staying lossless and free of
     * native dependencies. The extension must match what is actually encoded, or clients and CDNs
     * cache PNG bytes under a {@code .webp} URL.
     */
    public String fileExtension() {
        return "png";
    }
}
