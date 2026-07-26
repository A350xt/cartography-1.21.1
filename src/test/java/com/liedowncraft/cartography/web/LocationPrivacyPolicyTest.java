package com.liedowncraft.cartography.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.config.CartographySettings.MarkerSettings;
import com.liedowncraft.cartography.config.CartographySettings.ServerMode;

/**
 * The technical plan is explicit that permission is a hard filter applied before anything else, not
 * a ranking penalty. These tests pin that: a position that fails the policy must be absent from the
 * result, not merely deprioritized.
 */
class LocationPrivacyPolicyTest {
    private static final long NOW = 1_000_000L;

    @Test
    void markersAreOffByDefault() {
        MarkerSettings settings = new MarkerSettings(ServerMode.SURVIVAL, "off", 2000, 60, 64);

        LocationPrivacyPolicy policy = new LocationPrivacyPolicy(settings);

        assertFalse(policy.publicMarkersAllowed());
        assertTrue(policy.filterForPublicMap(List.of(marker(100, 200, NOW)), NOW).isEmpty());
    }

    @Test
    void combatServersNeverPublishPublicPositions() {
        // Leaking a basecamp on a PVP or war server is the failure this guards.
        for (ServerMode mode : new ServerMode[] {ServerMode.PVP, ServerMode.WAR}) {
            LocationPrivacyPolicy policy = new LocationPrivacyPolicy(
                    new MarkerSettings(mode, "exact", 2000, 0, 64));

            assertFalse(policy.publicMarkersAllowed(), mode + " must not publish public positions");
            assertTrue(policy.filterForPublicMap(List.of(marker(100, 200, NOW)), NOW).isEmpty());
        }
    }

    @Test
    void buildingServersMayShowExactLivePositions() {
        LocationPrivacyPolicy policy = new LocationPrivacyPolicy(
                new MarkerSettings(ServerMode.BUILDING, "exact", 2000, 60, 64));

        List<PlayerMarker> published = policy.filterForPublicMap(List.of(marker(100, 200, NOW)), NOW);

        assertEquals(1, published.size());
        assertEquals(100.0, published.get(0).x());
        assertEquals(200.0, published.get(0).z());
    }

    @Test
    void survivalServersQuantizePositions() {
        LocationPrivacyPolicy policy = new LocationPrivacyPolicy(
                new MarkerSettings(ServerMode.SURVIVAL, "exact", 2000, 0, 64));

        List<PlayerMarker> published = policy.filterForPublicMap(List.of(marker(100, 200, NOW)), NOW);

        assertEquals(1, published.size());
        assertNotEquals(100.0, published.get(0).x(), "survival must not expose an exact position");
        // Snapped to the centre of the 64-block cell containing the true position.
        assertEquals(96.0, published.get(0).x());
        assertEquals(224.0, published.get(0).z());
    }

    @Test
    void positionsAreWithheldUntilThePublicationDelayHasElapsed() {
        LocationPrivacyPolicy policy = new LocationPrivacyPolicy(
                new MarkerSettings(ServerMode.PVE, "exact", 2000, 60, 64));

        // A position from just now is still fresh enough to track someone in real time.
        assertTrue(policy.filterForPublicMap(List.of(marker(10, 20, NOW)), NOW).isEmpty());
        assertEquals(1, policy.filterForPublicMap(List.of(marker(10, 20, NOW - 61_000L)), NOW).size());
    }

    @Test
    void blurredModeQuantizesEvenOnPermissiveServerModes() {
        LocationPrivacyPolicy policy = new LocationPrivacyPolicy(
                new MarkerSettings(ServerMode.BUILDING, "blurred", 2000, 0, 16));

        List<PlayerMarker> published = policy.filterForPublicMap(List.of(marker(37, -5, NOW)), NOW);

        assertEquals(1, published.size());
        assertEquals(40.0, published.get(0).x());
        // Negative coordinates must floor toward the lower cell rather than truncate toward zero.
        assertEquals(-8.0, published.get(0).z());
    }

    private static PlayerMarker marker(double x, double z, long updatedAt) {
        return new PlayerMarker("uuid", "Steve", "minecraft:overworld", x, z, updatedAt);
    }
}
