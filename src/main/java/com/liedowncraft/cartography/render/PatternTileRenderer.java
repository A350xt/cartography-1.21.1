package com.liedowncraft.cartography.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.core.TileCoordinate;

public final class PatternTileRenderer {
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

    public byte[] renderTile(TileCoordinate coordinate, RendererProfile profile) throws IOException {
        BufferedImage image = new BufferedImage(profile.tileSize(), profile.tileSize(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(backgroundColor(coordinate));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(255, 255, 255, 180));
            graphics.setStroke(new BasicStroke(2.0F));
            graphics.drawRect(1, 1, image.getWidth() - 3, image.getHeight() - 3);
            graphics.drawLine(0, 0, image.getWidth(), image.getHeight());
            graphics.drawLine(image.getWidth(), 0, 0, image.getHeight());
        } finally {
            graphics.dispose();
        }
        return codec.encode(image);
    }

    private Color backgroundColor(TileCoordinate coordinate) {
        int hash = Objects.hash(coordinate.dimension(), coordinate.zoom(), coordinate.x(), coordinate.y());
        int red = 48 + Math.floorMod(hash, 160);
        int green = 48 + Math.floorMod(hash >> 8, 160);
        int blue = 48 + Math.floorMod(hash >> 16, 160);
        return new Color(red, green, blue);
    }
}
