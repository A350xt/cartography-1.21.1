package com.liedowncraft.cartography.core;

/**
 * Signed-to-normalized tile mapping for published tilesets (technical plan v2.0, section 4.3).
 *
 * <p>Published archives (CDN, object storage, PMTiles) may only use non-negative tile coordinates.
 * The offset recorded here is what makes the mapping reversible, so it must ship in the manifest.
 */
public record TileGridNormalization(
        int minSignedTileX,
        int minSignedTileY,
        int maxSignedTileX,
        int maxSignedTileY) {

    public TileGridNormalization {
        if (maxSignedTileX < minSignedTileX || maxSignedTileY < minSignedTileY) {
            throw new IllegalArgumentException("signed extent maximum must not be below its minimum");
        }
    }

    public int normalizedOffsetX() {
        return -minSignedTileX;
    }

    public int normalizedOffsetY() {
        return -minSignedTileY;
    }

    public int toNormalizedX(int signedTileX) {
        return signedTileX - minSignedTileX;
    }

    public int toNormalizedY(int signedTileY) {
        return signedTileY - minSignedTileY;
    }

    public int toSignedX(int normalizedTileX) {
        return normalizedTileX + minSignedTileX;
    }

    public int toSignedY(int normalizedTileY) {
        return normalizedTileY + minSignedTileY;
    }

    public TileCoordinate toNormalized(TileCoordinate signedTile) {
        return new TileCoordinate(
                signedTile.dimension(),
                signedTile.zoom(),
                toNormalizedX(signedTile.x()),
                toNormalizedY(signedTile.y()));
    }

    public TileCoordinate toSigned(TileCoordinate normalizedTile) {
        return new TileCoordinate(
                normalizedTile.dimension(),
                normalizedTile.zoom(),
                toSignedX(normalizedTile.x()),
                toSignedY(normalizedTile.y()));
    }
}
