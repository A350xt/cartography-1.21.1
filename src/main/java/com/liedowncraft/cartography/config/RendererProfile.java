package com.liedowncraft.cartography.config;

import java.util.List;

public record RendererProfile(
        int tileSize,
        int minZoom,
        int maxZoom,
        int pixelsPerBlockAtMaxZoom,
        int metatileSize,
        String rendererCodeVersion,
        String materialTableVersion,
        String configuredPackSignature,
        List<String> dimensions,
        String defaultDimension) {

    public RendererProfile {
        dimensions = List.copyOf(dimensions);
    }

    public int blocksPerTileAtMaxZoom() {
        return tileSize / pixelsPerBlockAtMaxZoom;
    }
}
