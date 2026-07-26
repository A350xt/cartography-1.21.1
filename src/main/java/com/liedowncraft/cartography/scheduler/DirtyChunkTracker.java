package com.liedowncraft.cartography.scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dirty chunk debounce and merge (technical plan v2.0, section 6.1).
 *
 * <p>A single WorldEdit operation or a burst of piston moves can dirty the same chunk thousands of
 * times per second. Collapsing repeats into one entry, and holding them for a debounce window, keeps
 * one edit from producing one render job per block change.
 *
 * <p>Time is injected rather than read from the clock so the debounce is testable.
 */
public final class DirtyChunkTracker {
    private final Map<DirtyChunk, Long> pending = new HashMap<>();
    private final long debounceMillis;

    public DirtyChunkTracker(long debounceMillis) {
        if (debounceMillis < 0) {
            throw new IllegalArgumentException("debounceMillis must not be negative");
        }
        this.debounceMillis = debounceMillis;
    }

    /**
     * Records a dirty chunk. Re-marking a chunk that is still within its debounce window restarts
     * the window, so a sustained edit drains only once it settles rather than mid-operation.
     */
    public synchronized void markDirty(String dimension, int chunkX, int chunkZ, long nowMillis) {
        pending.put(new DirtyChunk(dimension, chunkX, chunkZ), nowMillis);
    }

    /** Removes and returns the chunks whose debounce window has elapsed. */
    public synchronized List<DirtyChunk> drainSettled(long nowMillis) {
        List<DirtyChunk> settled = new ArrayList<>();
        pending.entrySet().removeIf(entry -> {
            if (nowMillis - entry.getValue() >= debounceMillis) {
                settled.add(entry.getKey());
                return true;
            }
            return false;
        });
        return settled;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized void clear() {
        pending.clear();
    }

    public record DirtyChunk(String dimension, int chunkX, int chunkZ) {
    }
}
