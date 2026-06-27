package com.liedowncraft.cartography.web;

public record HealthStatus(boolean alive, int queueDepth, boolean schedulerPaused, String tilesetVersion) {
}
