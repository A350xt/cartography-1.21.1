package com.liedowncraft.cartography.web;

/**
 * A published player position (technical plan v2.0, section 9.3).
 *
 * <p>Coordinates here have already passed the privacy policy, so they may be delayed or quantized
 * relative to the player's true position.
 */
public record PlayerMarker(String uuid, String name, String dimension, double x, double z, long updatedAt) {
}
