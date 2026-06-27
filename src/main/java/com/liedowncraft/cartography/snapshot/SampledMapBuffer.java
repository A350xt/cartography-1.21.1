package com.liedowncraft.cartography.snapshot;

import java.util.Objects;

public final class SampledMapBuffer {
    private final int width;
    private final int height;
    private final int blocksPerPixel;
    private final byte[] mapColorIds;
    private final float[] averageHeights;
    private final float[] fluidDepths;
    private final boolean[] waterPixels;

    private SampledMapBuffer(int width, int height, int blocksPerPixel, byte[] mapColorIds, float[] averageHeights, float[] fluidDepths, boolean[] waterPixels) {
        if (width <= 0 || height <= 0 || blocksPerPixel <= 0) {
            throw new IllegalArgumentException("width, height, and blocksPerPixel must be positive");
        }

        this.width = width;
        this.height = height;
        this.blocksPerPixel = blocksPerPixel;
        this.mapColorIds = mapColorIds;
        this.averageHeights = averageHeights;
        this.fluidDepths = fluidDepths;
        this.waterPixels = waterPixels;
    }

    public static Builder builder(int width, int height, int blocksPerPixel) {
        return new Builder(width, height, blocksPerPixel);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int blocksPerPixel() {
        return blocksPerPixel;
    }

    public byte mapColorIdAt(int x, int y) {
        return mapColorIds[index(x, y)];
    }

    public float averageHeightAt(int x, int y) {
        return averageHeights[index(x, y)];
    }

    public float fluidDepthAt(int x, int y) {
        return fluidDepths[index(x, y)];
    }

    public boolean waterPixelAt(int x, int y) {
        return waterPixels[index(x, y)];
    }

    public SampledMapBuffer slice(int startX, int startY, int sliceWidth, int sliceHeight) {
        Objects.checkFromIndexSize(startX, sliceWidth, width);
        Objects.checkFromIndexSize(startY, sliceHeight, height);

        Builder slice = builder(sliceWidth, sliceHeight, blocksPerPixel);
        for (int y = 0; y < sliceHeight; y++) {
            for (int x = 0; x < sliceWidth; x++) {
                slice.set(
                        x,
                        y,
                        mapColorIdAt(startX + x, startY + y),
                        averageHeightAt(startX + x, startY + y),
                        fluidDepthAt(startX + x, startY + y),
                        waterPixelAt(startX + x, startY + y));
            }
        }
        return slice.build();
    }

    private int index(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        return x + y * width;
    }

    public static final class Builder {
        private final int width;
        private final int height;
        private final int blocksPerPixel;
        private final byte[] mapColorIds;
        private final float[] averageHeights;
        private final float[] fluidDepths;
        private final boolean[] waterPixels;

        private Builder(int width, int height, int blocksPerPixel) {
            if (width <= 0 || height <= 0 || blocksPerPixel <= 0) {
                throw new IllegalArgumentException("width, height, and blocksPerPixel must be positive");
            }

            this.width = width;
            this.height = height;
            this.blocksPerPixel = blocksPerPixel;
            int size = width * height;
            this.mapColorIds = new byte[size];
            this.averageHeights = new float[size];
            this.fluidDepths = new float[size];
            this.waterPixels = new boolean[size];
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public Builder set(int x, int y, byte mapColorId, float averageHeight, float fluidDepth, boolean waterPixel) {
            int index = index(x, y);
            mapColorIds[index] = mapColorId;
            averageHeights[index] = averageHeight;
            fluidDepths[index] = fluidDepth;
            waterPixels[index] = waterPixel;
            return this;
        }

        public SampledMapBuffer build() {
            return new SampledMapBuffer(
                    width,
                    height,
                    blocksPerPixel,
                    mapColorIds.clone(),
                    averageHeights.clone(),
                    fluidDepths.clone(),
                    waterPixels.clone());
        }

        private int index(int x, int y) {
            Objects.checkIndex(x, width);
            Objects.checkIndex(y, height);
            return x + y * width;
        }
    }
}
