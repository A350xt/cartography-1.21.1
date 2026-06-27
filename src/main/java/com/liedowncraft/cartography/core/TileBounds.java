package com.liedowncraft.cartography.core;

public record TileBounds(int minBlockX, int minBlockZ, int maxBlockXExclusive, int maxBlockZExclusive) {
    public boolean contains(int blockX, int blockZ) {
        return blockX >= minBlockX
                && blockX < maxBlockXExclusive
                && blockZ >= minBlockZ
                && blockZ < maxBlockZExclusive;
    }
}
