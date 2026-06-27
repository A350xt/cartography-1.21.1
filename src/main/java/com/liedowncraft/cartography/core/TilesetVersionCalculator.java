package com.liedowncraft.cartography.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.liedowncraft.cartography.config.RendererProfile;

public final class TilesetVersionCalculator {
    private TilesetVersionCalculator() {
    }

    public static String calculate(RendererProfile profile) {
        String payload = String.join("|",
                profile.rendererCodeVersion(),
                profile.materialTableVersion(),
                profile.configuredPackSignature(),
                Integer.toString(profile.tileSize()),
                Integer.toString(profile.maxZoom()),
                Integer.toString(profile.metatileSize()));
        byte[] digest = sha256(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(16);
        for (int index = 0; index < 8; index++) {
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
