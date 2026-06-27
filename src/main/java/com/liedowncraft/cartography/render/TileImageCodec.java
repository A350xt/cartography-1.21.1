package com.liedowncraft.cartography.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

public final class TileImageCodec {
    public byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (ImageIO.write(image, "webp", output)) {
            return output.toByteArray();
        }

        output.reset();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
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
