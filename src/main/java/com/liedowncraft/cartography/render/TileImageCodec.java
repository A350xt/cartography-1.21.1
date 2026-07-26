package com.liedowncraft.cartography.render;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Encodes rendered tiles (technical plan v2.0, section 5.5).
 *
 * <p>The plan nominates WebP at quality 85, which suits photographic tiles. Minecraft map tiles are
 * flat-shaded palette art drawn from about 250 distinct colours, and on that content a lossy DCT
 * codec is both larger and lossy. Stock Java also ships no WebP writer, so every WebP option would
 * mean bundling platform native libraries.
 *
 * <p>So tiles are written as 8-bit indexed-colour PNG whenever the image fits in 256 colours, which
 * is the normal case, and fall back to full ARGB PNG otherwise. Indexed PNG is smaller than both
 * ARGB PNG and lossy WebP here, lossless, and dependency-free.
 */
public final class TileImageCodec {
    private static final int MAX_PALETTE_SIZE = 256;

    public byte[] encode(BufferedImage image) throws IOException {
        BufferedImage encodable = toIndexedIfPossible(image);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(encodable, "png", output);
        return output.toByteArray();
    }

    /**
     * Converts to an indexed image when the tile uses few enough distinct colours.
     *
     * <p>The palette is derived from the pixels actually present rather than from a fixed table, so
     * blended overlay colours are preserved exactly and the conversion stays lossless.
     *
     * @return an indexed copy, or the original image when it needs more than 256 colours
     */
    private BufferedImage toIndexedIfPossible(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        Map<Integer, Integer> paletteIndexByColor = new HashMap<>();
        int[] palette = new int[MAX_PALETTE_SIZE];
        int paletteSize = 0;

        for (int pixel : pixels) {
            if (paletteIndexByColor.containsKey(pixel)) {
                continue;
            }
            if (paletteSize == MAX_PALETTE_SIZE) {
                return image;
            }
            palette[paletteSize] = pixel;
            paletteIndexByColor.put(pixel, paletteSize);
            paletteSize++;
        }

        byte[] reds = new byte[paletteSize];
        byte[] greens = new byte[paletteSize];
        byte[] blues = new byte[paletteSize];
        byte[] alphas = new byte[paletteSize];
        for (int index = 0; index < paletteSize; index++) {
            int color = palette[index];
            alphas[index] = (byte) (color >>> 24);
            reds[index] = (byte) (color >> 16);
            greens[index] = (byte) (color >> 8);
            blues[index] = (byte) color;
        }

        IndexColorModel colorModel = new IndexColorModel(8, paletteSize, reds, greens, blues, alphas);
        BufferedImage indexed = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, colorModel);

        // Write palette indices straight into the raster. Drawing through Graphics2D would re-match
        // each pixel to the nearest palette entry, which is both slower and lossy.
        byte[] raster = ((DataBufferByte) indexed.getRaster().getDataBuffer()).getData();
        for (int index = 0; index < pixels.length; index++) {
            raster[index] = paletteIndexByColor.get(pixels[index]).byteValue();
        }
        return indexed;
    }

    public static String sniffMimeType(byte[] bytes) {
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/png";
    }
}
