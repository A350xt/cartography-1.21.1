package com.liedowncraft.cartography.snapshot;

import java.io.IOException;

import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.core.MetatileJob;

/**
 * Captures world surface data for a metatile (technical plan v2.0, section 3.1).
 *
 * <p>Implementations must do short-lived collection only: no image encoding and no large IO, because
 * this runs on or synchronizes with the server thread.
 *
 * <p>The returned buffer includes the render padding, which the renderer crops after shading.
 */
@FunctionalInterface
public interface WorldSnapshotProvider {

    /**
     * @throws SnapshotUnavailableException when the region is not currently readable, e.g. chunks are
     *     not loaded. The scheduler treats this as retryable and leaves the tile pending.
     */
    SampledMapBuffer capture(MetatileJob job, RendererProfile profile) throws IOException;
}
