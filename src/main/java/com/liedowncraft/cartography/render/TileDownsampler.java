package com.liedowncraft.cartography.render;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Builds a low-zoom tile from its four children (technical plan v2.0, section 6.4).
 *
 * <p>Low-zoom tiles are produced by downsampling, never by re-sampling the world: a zoom-0 tile can
 * span tens of thousands of blocks, which would require loading far more chunks than a render job
 * can justify.
 */
public final class TileDownsampler {
    private TileDownsampler() {
    }

    /**
     * Composites four child quadrants into one parent tile.
     *
     * @param children in (x,y) quadrant order: top-left, top-right, bottom-left, bottom-right.
     *     A null entry leaves that quadrant transparent, which is how not-yet-rendered regions read.
     */
    public static BufferedImage downsample(BufferedImage[] children, int tileSize) {
        if (children.length != 4) {
            throw new IllegalArgumentException("expected 4 child quadrants, got " + children.length);
        }

        BufferedImage parent = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = parent.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int half = tileSize / 2;
            for (int quadrant = 0; quadrant < 4; quadrant++) {
                BufferedImage child = children[quadrant];
                if (child == null) {
                    continue;
                }

                int destinationX = (quadrant % 2) * half;
                int destinationY = (quadrant / 2) * half;
                graphics.drawImage(child, destinationX, destinationY, destinationX + half, destinationY + half,
                        0, 0, child.getWidth(), child.getHeight(), null);
            }
        } finally {
            graphics.dispose();
        }
        return parent;
    }
}
