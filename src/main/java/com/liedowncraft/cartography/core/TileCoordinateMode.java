package com.liedowncraft.cartography.core;

/**
 * Tile addressing mode (technical plan v2.0, section 4.3).
 *
 * <p>The online writable store keeps signed tile coordinates so it never has to rewrite paths as the
 * explored area grows. Published archives normalize to non-negative coordinates and record the
 * offset in the manifest.
 */
public enum TileCoordinateMode {
    ONLINE_SIGNED("online-signed"),
    PUBLISHED_NORMALIZED("published-normalized");

    private final String manifestValue;

    TileCoordinateMode(String manifestValue) {
        this.manifestValue = manifestValue;
    }

    public String manifestValue() {
        return manifestValue;
    }

    public static TileCoordinateMode fromManifestValue(String value) {
        for (TileCoordinateMode mode : values()) {
            if (mode.manifestValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown tile coordinate mode " + value);
    }
}
