package com.liedowncraft.cartography.web;

import java.util.List;

public record MapManifest(
        int tileSize,
        int minZoom,
        int maxZoom,
        int pixelsPerBlockAtMaxZoom,
        List<String> dimensions,
        String defaultDimension,
        String tilesetVersion,
        String tileUrlTemplate,
        String markerMode,
        int pendingTileRetryMs) {
}
