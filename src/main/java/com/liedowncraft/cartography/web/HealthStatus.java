package com.liedowncraft.cartography.web;

/**
 * Health and observability snapshot (technical plan v2.0, section 15.2).
 */
public record HealthStatus(
        boolean alive,
        String tilesetVersion,
        int queueDepth,
        int ancestorDirtyBacklog,
        int pendingDirtyChunks,
        boolean schedulerPaused,
        double currentTps,
        long renderedJobs,
        long failedJobs,
        long droppedJobs,
        long refreshedAncestors,
        long tileCacheHits,
        long tileCacheMisses,
        double lastSnapshotMillis,
        double lastRenderMillis,
        double lastTileWriteMillis,
        /** Most recent render failure. Empty when nothing has failed. */
        String lastFailure) {

    /** Fraction of tile requests served from the store rather than queued (plan cache_hit_ratio). */
    public double cacheHitRatio() {
        long total = tileCacheHits + tileCacheMisses;
        return total == 0 ? 0.0 : (double) tileCacheHits / total;
    }
}
