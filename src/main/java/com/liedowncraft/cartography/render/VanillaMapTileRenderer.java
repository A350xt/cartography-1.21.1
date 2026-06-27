package com.liedowncraft.cartography.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.snapshot.SampledMapBuffer;

public final class VanillaMapTileRenderer {
    private final TileImageCodec codec = new TileImageCodec();

    public byte[] renderPendingTile(RendererProfile profile) throws IOException {
        BufferedImage image = new BufferedImage(profile.tileSize(), profile.tileSize(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
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

    public BufferedImage renderImage(SampledMapBuffer snapshot) {
        BufferedImage image = new BufferedImage(snapshot.width(), snapshot.height(), BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < snapshot.width(); x++) {
            double previousAverageHeight = 0.0;
            for (int y = 0; y < snapshot.height(); y++) {
                int colorId = Byte.toUnsignedInt(snapshot.mapColorIdAt(x, y));
                VanillaMapPalette.Brightness brightness;
                if (snapshot.waterPixelAt(x, y)) {
                    double waterDepthSignal = snapshot.fluidDepthAt(x, y) * 0.1 + (double)((x + y) & 1) * 0.2;
                    if (waterDepthSignal < 0.5) {
                        brightness = VanillaMapPalette.Brightness.HIGH;
                    } else if (waterDepthSignal > 0.9) {
                        brightness = VanillaMapPalette.Brightness.LOW;
                    } else {
                        brightness = VanillaMapPalette.Brightness.NORMAL;
                    }
                } else {
                    double slopeSignal = (snapshot.averageHeightAt(x, y) - previousAverageHeight) * 4.0 / (snapshot.blocksPerPixel() + 4.0)
                            + ((double)((x + y) & 1) - 0.5) * 0.4;
                    if (slopeSignal > 0.6) {
                        brightness = VanillaMapPalette.Brightness.HIGH;
                    } else if (slopeSignal < -0.6) {
                        brightness = VanillaMapPalette.Brightness.LOW;
                    } else {
                        brightness = VanillaMapPalette.Brightness.NORMAL;
                    }
                }

                previousAverageHeight = snapshot.averageHeightAt(x, y);
                image.setRGB(x, y, VanillaMapPalette.argb(colorId, brightness));
            }
        }
        return image;
    }
}
