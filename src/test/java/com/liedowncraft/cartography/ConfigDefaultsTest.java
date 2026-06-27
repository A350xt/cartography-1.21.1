package com.liedowncraft.cartography;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.config.CartographySettings;

class ConfigDefaultsTest {
    @Test
    void markerModeDefaultsToOff() {
        assertEquals("off", CartographySettings.defaults(Path.of("build/test-defaults")).markers().mode());
    }
}
