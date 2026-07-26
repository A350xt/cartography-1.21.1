package com.liedowncraft.cartography.snapshot;

import java.util.Objects;

/**
 * Immutable grid of sampled surface columns for one metatile (technical plan v2.0, section 5.2).
 *
 * <p>The buffer covers the metatile plus its render padding. {@link #padding()} records how many
 * pixels of margin sit on each side so the renderer can shade using neighbours that lie outside the
 * published area, then crop them away before tiles are cut.
 *
 * <p>Structure-of-arrays rather than an object per pixel: a padded 4x4 metatile at 256px is over a
 * million samples, and boxing each one would dominate render cost.
 */
public final class SampledMapBuffer {
    private final int width;
    private final int height;
    private final int padding;
    private final int blocksPerPixel;
    private final byte[] mapColorIds;
    private final float[] averageHeights;
    private final float[] fluidDepths;
    private final byte[] surfaceKinds;
    /** ARGB of the structure covering this column, or 0 when nothing covers it. */
    private final int[] overlayColors;

    private SampledMapBuffer(
            int width,
            int height,
            int padding,
            int blocksPerPixel,
            byte[] mapColorIds,
            float[] averageHeights,
            float[] fluidDepths,
            byte[] surfaceKinds,
            int[] overlayColors) {
        this.width = width;
        this.height = height;
        this.padding = padding;
        this.blocksPerPixel = blocksPerPixel;
        this.mapColorIds = mapColorIds;
        this.averageHeights = averageHeights;
        this.fluidDepths = fluidDepths;
        this.surfaceKinds = surfaceKinds;
        this.overlayColors = overlayColors;
    }

    public static Builder builder(int width, int height, int blocksPerPixel) {
        return new Builder(width, height, 0, blocksPerPixel);
    }

    public static Builder builder(int width, int height, int padding, int blocksPerPixel) {
        return new Builder(width, height, padding, blocksPerPixel);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Padding pixels present on each side of the published area. */
    public int padding() {
        return padding;
    }

    /** Width of the published area once padding is cropped away. */
    public int publishedWidth() {
        return width - padding * 2;
    }

    public int publishedHeight() {
        return height - padding * 2;
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

    public SurfaceKind surfaceKindAt(int x, int y) {
        return SurfaceKind.values()[Byte.toUnsignedInt(surfaceKinds[index(x, y)])];
    }

    public int overlayColorAt(int x, int y) {
        return overlayColors[index(x, y)];
    }

    public boolean waterPixelAt(int x, int y) {
        return surfaceKindAt(x, y) == SurfaceKind.FLUID_SURFACE;
    }

    public SampledMapBuffer slice(int startX, int startY, int sliceWidth, int sliceHeight) {
        Objects.checkFromIndexSize(startX, sliceWidth, width);
        Objects.checkFromIndexSize(startY, sliceHeight, height);

        Builder slice = builder(sliceWidth, sliceHeight, 0, blocksPerPixel);
        for (int y = 0; y < sliceHeight; y++) {
            for (int x = 0; x < sliceWidth; x++) {
                int source = index(startX + x, startY + y);
                slice.setAt(
                        x,
                        y,
                        mapColorIds[source],
                        averageHeights[source],
                        fluidDepths[source],
                        SurfaceKind.values()[Byte.toUnsignedInt(surfaceKinds[source])],
                        overlayColors[source]);
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
        private final int padding;
        private final int blocksPerPixel;
        private final byte[] mapColorIds;
        private final float[] averageHeights;
        private final float[] fluidDepths;
        private final byte[] surfaceKinds;
        private final int[] overlayColors;

        private Builder(int width, int height, int padding, int blocksPerPixel) {
            if (width <= 0 || height <= 0 || blocksPerPixel <= 0) {
                throw new IllegalArgumentException("width, height, and blocksPerPixel must be positive");
            }
            if (padding < 0 || padding * 2 >= Math.min(width, height)) {
                throw new IllegalArgumentException("padding must be non-negative and leave a published area");
            }

            this.width = width;
            this.height = height;
            this.padding = padding;
            this.blocksPerPixel = blocksPerPixel;
            int size = width * height;
            this.mapColorIds = new byte[size];
            this.averageHeights = new float[size];
            this.fluidDepths = new float[size];
            this.surfaceKinds = new byte[size];
            this.overlayColors = new int[size];
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int padding() {
            return padding;
        }

        /** Legacy shape retained for tests that only care about color, height and water. */
        public Builder set(int x, int y, byte mapColorId, float averageHeight, float fluidDepth, boolean waterPixel) {
            return setAt(
                    x,
                    y,
                    mapColorId,
                    averageHeight,
                    fluidDepth,
                    waterPixel ? SurfaceKind.FLUID_SURFACE : SurfaceKind.OPAQUE_SURFACE,
                    0);
        }

        public Builder setAt(
                int x,
                int y,
                byte mapColorId,
                float averageHeight,
                float fluidDepth,
                SurfaceKind surfaceKind,
                int overlayColor) {
            int index = index(x, y);
            mapColorIds[index] = mapColorId;
            averageHeights[index] = averageHeight;
            fluidDepths[index] = fluidDepth;
            surfaceKinds[index] = (byte) surfaceKind.ordinal();
            overlayColors[index] = overlayColor;
            return this;
        }

        public SampledMapBuffer build() {
            return new SampledMapBuffer(
                    width,
                    height,
                    padding,
                    blocksPerPixel,
                    mapColorIds.clone(),
                    averageHeights.clone(),
                    fluidDepths.clone(),
                    surfaceKinds.clone(),
                    overlayColors.clone());
        }

        private int index(int x, int y) {
            Objects.checkIndex(x, width);
            Objects.checkIndex(y, height);
            return x + y * width;
        }
    }
}
