package com.liedowncraft.cartography.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.core.TileCoordinate;

/**
 * Low-zoom invalidation queue (technical plan v2.0, section 6.4). Order matters: a parent can only
 * be downsampled correctly once its own children are current, so the deepest zoom must pop first.
 */
class AncestorDirtySetTest {
    @Test
    void deepestZoomPopsFirstSoChildrenRefreshBeforeParents() {
        AncestorDirtySet set = new AncestorDirtySet();
        set.add(tile(0, 0, 0));
        set.add(tile(3, 4, 4));
        set.add(tile(1, 1, 1));

        assertEquals(3, set.popBest().orElseThrow().zoom());
        assertEquals(1, set.popBest().orElseThrow().zoom());
        assertEquals(0, set.popBest().orElseThrow().zoom());
        assertTrue(set.popBest().isEmpty());
    }

    @Test
    void duplicateEntriesAreCollapsed() {
        AncestorDirtySet set = new AncestorDirtySet();

        assertTrue(set.add(tile(2, 3, 3)));
        assertFalse(set.add(tile(2, 3, 3)), "the same ancestor must not queue twice");
        assertEquals(1, set.backlog());
    }

    @Test
    void anAncestorCanBeRequeuedAfterItIsRefreshed() {
        AncestorDirtySet set = new AncestorDirtySet();
        set.add(tile(2, 3, 3));
        set.popBest();

        // A later edit under the same parent must be able to mark it dirty again.
        assertTrue(set.add(tile(2, 3, 3)));
        assertEquals(1, set.backlog());
    }

    @Test
    void backlogReportsQueueDepthForTheMetricsEndpoint() {
        AncestorDirtySet set = new AncestorDirtySet();

        assertTrue(set.isEmpty());
        set.add(tile(1, 0, 0));
        set.add(tile(1, 1, 0));
        assertEquals(2, set.backlog());

        set.clear();
        assertTrue(set.isEmpty());
        assertEquals(0, set.backlog());
    }

    private static TileCoordinate tile(int zoom, int x, int y) {
        return new TileCoordinate("minecraft:overworld", zoom, x, y);
    }
}
