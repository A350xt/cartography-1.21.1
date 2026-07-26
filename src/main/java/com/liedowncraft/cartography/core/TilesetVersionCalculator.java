package com.liedowncraft.cartography.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.liedowncraft.cartography.config.RendererProfile;

/**
 * Tileset version hash (technical plan v2.0, section 7.1).
 *
 * <p>The hash goes into the tile URL, not just internal metadata, so a browser or CDN holding a tile
 * from an older resource pack, renderer, profile or tile grid can never serve it under the new
 * namespace. Every input that can change a rendered pixel must feed this hash.
 */
public final class TilesetVersionCalculator {
    private TilesetVersionCalculator() {
    }

    /**
     * @param worldId stable world identifier; distinguishes saves that share a profile
     * @param dimensionId dimension the tileset covers
     */
    public static String calculate(String worldId, String dimensionId, RendererProfile profile) {
        TileGrid grid = profile.tileGrid();
        String payload = String.join("|",
                worldId,
                dimensionId,
                profile.profileId(),
                renderProfileHash(profile),
                profile.configuredPackSignature(),
                profile.materialTableVersion(),
                profile.rendererCodeVersion(),
                tileGridVersion(grid, profile),
                profile.format(),
                Integer.toString(profile.quality()));
        return shortHex(sha256(payload.getBytes(StandardCharsets.UTF_8)), 8);
    }

    /** Visual knobs that are not already covered by an explicit version string. */
    private static String renderProfileHash(RendererProfile profile) {
        String payload = String.join(",",
                Integer.toString(profile.metatileSize()),
                Integer.toString(profile.paddingBlocks()),
                Boolean.toString(profile.heightShade()),
                Boolean.toString(profile.fluidDepth()));
        return shortHex(sha256(payload.getBytes(StandardCharsets.UTF_8)), 4);
    }

    /** Any tile grid change repartitions the world, so it must produce a fresh namespace. */
    private static String tileGridVersion(TileGrid grid, RendererProfile profile) {
        return String.join(",",
                Integer.toString(grid.tileSize()),
                Integer.toString(grid.minZoom()),
                Integer.toString(grid.maxZoom()),
                Integer.toString(grid.pixelsPerBlockAtMaxZoom()),
                Integer.toString(grid.tileOriginX()),
                Integer.toString(grid.tileOriginZ()),
                profile.tileCoordinateMode().manifestValue());
    }

    private static String shortHex(byte[] digest, int byteCount) {
        StringBuilder builder = new StringBuilder(byteCount * 2);
        for (int index = 0; index < byteCount; index++) {
            builder.append(String.format("%02x", digest[index]));
        }
        return builder.toString();
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
