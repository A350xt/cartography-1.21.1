package com.liedowncraft.cartography.core;

import java.util.ArrayList;
import java.util.List;

public record MetatileJob(String tilesetVersion, String dimension, int zoom, int startX, int startY, int tileCount) {
    public List<TileCoordinate> tiles() {
        List<TileCoordinate> tiles = new ArrayList<>(tileCount * tileCount);
        for (int offsetY = 0; offsetY < tileCount; offsetY++) {
            for (int offsetX = 0; offsetX < tileCount; offsetX++) {
                tiles.add(new TileCoordinate(dimension, zoom, startX + offsetX, startY + offsetY));
            }
        }
        return tiles;
    }
}
