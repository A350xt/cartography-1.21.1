package com.liedowncraft.cartography.web;

import java.util.List;

import com.liedowncraft.cartography.core.TileCoordinateMode;
import com.liedowncraft.cartography.core.TileGrid;
import com.liedowncraft.cartography.core.TileGridNormalization;

/**
 * Tile grid manifest served to the frontend (technical plan v2.0, sections 4.4 and A.2).
 *
 * <p>This is the contract that lets a client convert Minecraft coordinates into tile requests. It
 * must carry the tile origin, pixels per block and signed extent, because without them the client
 * cannot place a feature at a known block coordinate onto the right pixel.
 */
public record MapManifest(
        String crs,
        String world,
        String defaultDimension,
        List<String> dimensions,
        String dataCoordinate,
        TileCoordinateMode tileCoordinateMode,
        TileGrid tileGrid,
        TileGridNormalization normalization,
        String profile,
        String tilesetVersion,
        String rendererVersion,
        String materialTableHash,
        String resourcePackHash,
        String format,
        int quality,
        String tileUrlTemplate,
        String markerMode,
        int markerPollIntervalMs,
        int pendingTileRetryMs) {

    public static final String CRS = "cartography:mc-crs";
    public static final String DATA_COORDINATE = "minecraft-xz";

    public MapManifest {
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
}
