package com.liedowncraft.cartography.scheduler;

import java.util.Comparator;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.HashSet;

import com.liedowncraft.cartography.core.TileCoordinate;

/**
 * Low-zoom invalidation DAG (technical plan v2.0, section 6.4).
 *
 * <p>When a max-zoom tile is rewritten, every parent up the pyramid becomes stale. Refreshing them
 * synchronously would multiply the cost of every edit, so they queue here and a budgeted background
 * pass downsamples them instead.
 *
 * <p>Highest zoom pops first. A parent can only be downsampled correctly once its own children are
 * current, so working bottom-up means each refresh reads already-refreshed children.
 */
public final class AncestorDirtySet {
    private final PriorityQueue<TileCoordinate> queue =
            new PriorityQueue<>(Comparator.comparingInt(TileCoordinate::zoom).reversed());
    private final Set<TileCoordinate> queued = new HashSet<>();

    public synchronized boolean add(TileCoordinate tile) {
        if (!queued.add(tile)) {
            return false;
        }
        queue.add(tile);
        return true;
    }

    /** Pops the deepest queued ancestor, so children are refreshed before their parents. */
    public synchronized Optional<TileCoordinate> popBest() {
        TileCoordinate tile = queue.poll();
        if (tile == null) {
            return Optional.empty();
        }
        queued.remove(tile);
        return Optional.of(tile);
    }

    public synchronized int backlog() {
        return queue.size();
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized void clear() {
        queue.clear();
        queued.clear();
    }
}
