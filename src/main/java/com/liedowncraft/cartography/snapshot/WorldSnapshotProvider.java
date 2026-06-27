package com.liedowncraft.cartography.snapshot;

import java.io.IOException;

import com.liedowncraft.cartography.config.RendererProfile;
import com.liedowncraft.cartography.core.MetatileJob;

@FunctionalInterface
public interface WorldSnapshotProvider {
    SampledMapBuffer capture(MetatileJob job, RendererProfile profile) throws IOException;
}
