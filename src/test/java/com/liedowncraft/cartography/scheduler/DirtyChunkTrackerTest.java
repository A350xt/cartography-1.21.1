package com.liedowncraft.cartography.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Debounce and merge for dirty chunks (technical plan v2.0, section 6.1). A single bulk edit can
 * dirty one chunk thousands of times per second; without collapsing and settling, that becomes one
 * render job per block change.
 */
class DirtyChunkTrackerTest {
    @Test
    void repeatedMarksOnTheSameChunkCollapseToOneEntry() {
        DirtyChunkTracker tracker = new DirtyChunkTracker(0);

        for (int i = 0; i < 1000; i++) {
            tracker.markDirty("minecraft:overworld", 4, 9, 0L);
        }

        assertEquals(1, tracker.pendingCount());
        assertEquals(1, tracker.drainSettled(0L).size());
    }

    @Test
    void chunksAreHeldUntilTheDebounceWindowElapses() {
        DirtyChunkTracker tracker = new DirtyChunkTracker(5_000);
        tracker.markDirty("minecraft:overworld", 0, 0, 1_000L);

        assertTrue(tracker.drainSettled(3_000L).isEmpty(), "a chunk still settling must not drain");
        assertEquals(1, tracker.drainSettled(6_000L).size());
    }

    @Test
    void reMarkingRestartsTheWindowSoASustainedEditDrainsOnlyOnceItSettles() {
        DirtyChunkTracker tracker = new DirtyChunkTracker(5_000);
        tracker.markDirty("minecraft:overworld", 0, 0, 1_000L);
        // A long WorldEdit operation keeps touching the same chunk.
        tracker.markDirty("minecraft:overworld", 0, 0, 4_000L);

        assertTrue(tracker.drainSettled(6_000L).isEmpty(), "the window should have restarted");
        assertEquals(1, tracker.drainSettled(9_500L).size());
    }

    @Test
    void drainRemovesEntriesSoTheyAreNotRenderedTwice() {
        DirtyChunkTracker tracker = new DirtyChunkTracker(0);
        tracker.markDirty("minecraft:overworld", 1, 1, 0L);

        assertEquals(1, tracker.drainSettled(0L).size());
        assertTrue(tracker.drainSettled(0L).isEmpty());
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void chunksInDifferentDimensionsAreTrackedSeparately() {
        DirtyChunkTracker tracker = new DirtyChunkTracker(0);
        tracker.markDirty("minecraft:overworld", 0, 0, 0L);
        tracker.markDirty("minecraft:the_nether", 0, 0, 0L);

        List<DirtyChunkTracker.DirtyChunk> settled = tracker.drainSettled(0L);

        assertEquals(2, settled.size());
    }
}
