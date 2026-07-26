package com.liedowncraft.cartography.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.snapshot.SampledMapBuffer;

/**
 * Rasterizes a sampled metatile using vanilla map shading (technical plan v2.0, sections 5.3 and 5.5).
 *
 * <p>Shading follows the vanilla map item: north-south slope selects one of four brightness steps,
 * and water uses depth instead of slope. Matching vanilla matters because players read this map
 * against the map item they already know.
 */
public final class VanillaMapTileRenderer {
    private final TileImageCodec codec;

    public VanillaMapTileRenderer() {
        this(new TileImageCodec());
    }

    public VanillaMapTileRenderer(TileImageCodec codec) {
        this.codec = codec;
    }

    /** Placeholder served while a tile is queued, so the client shows a neutral cell, not a broken image. */
    public byte[] renderPendingTile(RendererProfile profile) throws IOException {
        BufferedImage image = new BufferedImage(profile.tileSize(), profile.tileSize(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0, 0, 0, 0));
            graphics.setComposite(java.awt.AlphaComposite.Src);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        return codec.encode(image);
    }

    public byte[] renderTile(SampledMapBuffer snapshot) throws IOException {
        return codec.encode(renderImage(snapshot));
    }

    public byte[] encodeImage(BufferedImage image) throws IOException {
        return codec.encode(image);
    }

    /**
     * Renders the full padded buffer, then crops the padding away. Shading a pixel needs its northern
     * neighbour, so the padding is what keeps the first row of every metatile from being shaded
     * against nothing and leaving a seam.
     */
    public BufferedImage renderImage(SampledMapBuffer snapshot) {
        BufferedImage padded = renderPadded(snapshot);
        int padding = snapshot.padding();
        if (padding == 0) {
            return padded;
        }
        return padded.getSubimage(padding, padding, snapshot.publishedWidth(), snapshot.publishedHeight());
    }

    private BufferedImage renderPadded(SampledMapBuffer snapshot) {
        BufferedImage image = new BufferedImage(snapshot.width(), snapshot.height(), BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < snapshot.width(); x++) {
            // Vanilla resets the gradient once per column and walks north to south (+Z), shading each
            // pixel against the row above it. Row 0 of the padded buffer is real sampled terrain from
            // outside the published area, which is exactly the extra row vanilla samples at l1 = -1
            // purely to seed this value.
            double previousHeight = snapshot.averageHeightAt(x, 0);
            for (int y = 0; y < snapshot.height(); y++) {
                double currentHeight = snapshot.averageHeightAt(x, y);
                int colorId = Byte.toUnsignedInt(snapshot.mapColorIdAt(x, y));

                // Vanilla gates depth shading on the dominant map colour being WATER, not on the
                // block being a fluid. Lava therefore uses slope shading, and matching that keeps
                // lava lakes reading the same here as on a map item.
                VanillaMapPalette.Brightness brightness =
                        colorId == Byte.toUnsignedInt(VanillaMapPalette.WATER)
                                ? waterBrightness(snapshot.fluidDepthAt(x, y), x, y)
                                : slopeBrightness(currentHeight - previousHeight, snapshot.blocksPerPixel(), x, y);

                int argb = VanillaMapPalette.argb(colorId, brightness);
                int overlay = snapshot.overlayColorAt(x, y);
                if (overlay != 0) {
                    // A transparent structure above this column tints what shows through it.
                    argb = blendOver(overlay, argb);
                }

                image.setRGB(x, y, argb);
                previousHeight = currentHeight;
            }
        }
        return image;
    }

    /**
     * Vanilla water shading: brightness comes from depth, with a checkerboard dither so large flat
     * bodies of water do not band into hard steps.
     */
    private VanillaMapPalette.Brightness waterBrightness(double fluidDepth, int x, int y) {
        double depthSignal = fluidDepth * 0.1 + (double) ((x + y) & 1) * 0.2;
        if (depthSignal < 0.5) {
            return VanillaMapPalette.Brightness.HIGH;
        }
        if (depthSignal > 0.9) {
            return VanillaMapPalette.Brightness.LOW;
        }
        return VanillaMapPalette.Brightness.NORMAL;
    }

    /**
     * Vanilla terrain shading: the height delta against the northern neighbour, dithered on a
     * checkerboard, mapped onto four brightness steps.
     *
     * <p>The divisor scales with {@code blocksPerPixel} exactly as vanilla's {@code (k2 + 4)} does,
     * so a zoomed-out tile that averages many blocks per pixel does not read as pure cliff face.
     */
    private VanillaMapPalette.Brightness slopeBrightness(double heightDelta, int blocksPerPixel, int x, int y) {
        double slopeSignal = heightDelta * 4.0 / (blocksPerPixel + 4.0) + ((double) ((x + y) & 1) - 0.5) * 0.4;
        if (slopeSignal > 0.6) {
            return VanillaMapPalette.Brightness.HIGH;
        }
        if (slopeSignal < -0.6) {
            return VanillaMapPalette.Brightness.LOW;
        }
        return VanillaMapPalette.Brightness.NORMAL;
    }

    /** Standard source-over alpha composite of a translucent structure onto the surface below. */
    private int blendOver(int overlayArgb, int baseArgb) {
        int overlayAlpha = overlayArgb >>> 24;
        if (overlayAlpha == 0) {
            return baseArgb;
        }
        if (overlayAlpha == 0xFF) {
            return overlayArgb;
        }

        double alpha = overlayAlpha / 255.0;
        int red = (int) Math.round(((overlayArgb >> 16) & 0xFF) * alpha + ((baseArgb >> 16) & 0xFF) * (1 - alpha));
        int green = (int) Math.round(((overlayArgb >> 8) & 0xFF) * alpha + ((baseArgb >> 8) & 0xFF) * (1 - alpha));
        int blue = (int) Math.round((overlayArgb & 0xFF) * alpha + (baseArgb & 0xFF) * (1 - alpha));
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
}
