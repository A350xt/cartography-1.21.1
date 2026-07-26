package com.liedowncraft.cartography.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.liedowncraft.cartography.config.CartographySettings;

/**
 * Player location privacy filter (technical plan v2.0, sections 9.3 and A.1).
 *
 * <p>This is a hard filter, deliberately applied before anything else sees a marker: a position that
 * fails the policy is dropped from the candidate set entirely rather than downranked. The plan is
 * explicit that hiding by score is not a security control.
 *
 * <p>Defaults are conservative. Markers are off unless an operator opts in, and on PVP or war servers
 * the public map never publishes exact coordinates.
 */
public final class LocationPrivacyPolicy {
    private final CartographySettings.MarkerSettings settings;

    public LocationPrivacyPolicy(CartographySettings.MarkerSettings settings) {
        this.settings = settings;
    }

    /** Whether the public map may show player markers at all under the current server mode. */
    public boolean publicMarkersAllowed() {
        if (!settings.enabled()) {
            return false;
        }

        return switch (settings.serverMode()) {
            case BUILDING, PVE, SURVIVAL -> true;
            // The plan defaults these modes to no public positions; combat servers leak basecamps.
            case PVP, WAR -> false;
        };
    }

    /** Whether a published position must be quantized rather than exact. */
    public boolean requiresBlurring() {
        if (settings.blurred()) {
            return true;
        }

        return switch (settings.serverMode()) {
            case BUILDING, PVE -> false;
            case SURVIVAL, PVP, WAR -> true;
        };
    }

    /** How stale a published position must be, in milliseconds. */
    public long publicDelayMillis() {
        return switch (settings.serverMode()) {
            case BUILDING -> 0L;
            case PVE, SURVIVAL, PVP, WAR -> settings.publicDelaySeconds() * 1000L;
        };
    }

    /**
     * Applies the policy to a set of raw positions.
     *
     * @param nowMillis current time, used to enforce the publication delay
     * @return markers safe to publish on the public map
     */
    public List<PlayerMarker> filterForPublicMap(Collection<PlayerMarker> rawMarkers, long nowMillis) {
        if (!publicMarkersAllowed()) {
            return List.of();
        }

        long delayMillis = publicDelayMillis();
        boolean blur = requiresBlurring();
        int blurRadius = Math.max(1, settings.blurRadiusBlocks());

        List<PlayerMarker> published = new ArrayList<>();
        for (PlayerMarker marker : rawMarkers) {
            // Only publish a position once it is at least delayMillis old, so the map cannot be used
            // to track someone in real time.
            if (nowMillis - marker.updatedAt() < delayMillis) {
                continue;
            }

            published.add(blur ? blur(marker, blurRadius) : marker);
        }
        return published;
    }

    /** Snaps a position to the centre of a blurRadius-sized cell. */
    private PlayerMarker blur(PlayerMarker marker, int blurRadius) {
        double blurredX = Math.floor(marker.x() / blurRadius) * blurRadius + blurRadius / 2.0;
        double blurredZ = Math.floor(marker.z() / blurRadius) * blurRadius + blurRadius / 2.0;
        return new PlayerMarker(marker.uuid(), marker.name(), marker.dimension(), blurredX, blurredZ, marker.updatedAt());
    }
}
