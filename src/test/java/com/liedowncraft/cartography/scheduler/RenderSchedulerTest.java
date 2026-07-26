package com.liedowncraft.cartography.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.core.MetatileJob;
import com.liedowncraft.cartography.core.TileCoordinate;

/**
 * Scheduler behaviour under TPS pressure (technical plan v2.0, section A.1).
 */
class RenderSchedulerTest {
    private static CartographySettings.SchedulerSettings settings(int queueCapacity) {
        // Pause below 18.5, resume above 19.2, as the plan's reference config specifies.
        return new CartographySettings.SchedulerSettings(1, queueCapacity, 18.5D, 19.2D, 0, 8);
    }

    @Test
    void workersPauseBelowTheFloorAndResumeOnlyAboveTheCeiling() {
        try (RenderScheduler scheduler = new RenderScheduler(settings(16))) {
            scheduler.setCurrentTps(20.0);
            assertFalse(scheduler.isPaused());

            scheduler.setCurrentTps(18.0);
            assertTrue(scheduler.isPaused(), "should pause below the floor");

            // The gap between the thresholds is the point: recovering to just above the floor must
            // not resume, or workers flap on and off while TPS hovers at the limit.
            scheduler.setCurrentTps(18.6);
            assertTrue(scheduler.isPaused(), "should stay paused inside the hysteresis band");

            scheduler.setCurrentTps(19.3);
            assertFalse(scheduler.isPaused(), "should resume above the ceiling");
        }
    }

    @Test
    void aSchedulerStartsPausedWhenTheConfiguredFloorIsUnreachable() {
        // Assumed starting TPS is 20; a floor above that must be honoured from construction rather
        // than defaulting to running.
        CartographySettings.SchedulerSettings impossible =
                new CartographySettings.SchedulerSettings(1, 16, 21.0D, 21.0D, 0, 8);

        try (RenderScheduler scheduler = new RenderScheduler(impossible)) {
            assertTrue(scheduler.isPaused());
        }
    }

    @Test
    void duplicateJobsAreCollapsedWhileQueued() {
        try (RenderScheduler scheduler = new RenderScheduler(settings(16))) {
            scheduler.setCurrentTps(0.0);
            MetatileJob job = new MetatileJob("v1", "minecraft:overworld", 8, 0, 0, 4);

            assertTrue(scheduler.enqueue(job));
            assertTrue(scheduler.enqueue(job));

            assertEquals(1, scheduler.queueDepth(), "the same metatile must not queue twice");
        }
    }

    @Test
    void aSaturatedQueueRejectsNewWorkInsteadOfGrowing() {
        try (RenderScheduler scheduler = new RenderScheduler(settings(2))) {
            scheduler.setCurrentTps(0.0);

            assertTrue(scheduler.enqueue(new MetatileJob("v1", "minecraft:overworld", 8, 0, 0, 4)));
            assertTrue(scheduler.enqueue(new MetatileJob("v1", "minecraft:overworld", 8, 4, 0, 4)));
            assertFalse(
                    scheduler.enqueue(new MetatileJob("v1", "minecraft:overworld", 8, 8, 0, 4)),
                    "a full queue must reject rather than grow without bound");
            assertEquals(1, scheduler.droppedJobs());
        }
    }

    @Test
    void queuedJobsRunAndAreCounted() throws Exception {
        try (RenderScheduler scheduler = new RenderScheduler(settings(16))) {
            CountDownLatch rendered = new CountDownLatch(1);
            scheduler.start(job -> rendered.countDown(), ancestor -> { });
            scheduler.setCurrentTps(20.0);
            scheduler.enqueue(new MetatileJob("v1", "minecraft:overworld", 8, 0, 0, 4));

            assertTrue(rendered.await(5, TimeUnit.SECONDS), "the job should have been handled");
        }
    }

    @Test
    void aFailingJobDoesNotStopTheWorker() throws Exception {
        try (RenderScheduler scheduler = new RenderScheduler(settings(16))) {
            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch secondJob = new CountDownLatch(2);
            scheduler.start(
                    job -> {
                        secondJob.countDown();
                        // An unloaded chunk is the common cause; the worker must survive it.
                        if (attempts.incrementAndGet() == 1) {
                            throw new IllegalStateException("chunk not loaded");
                        }
                    },
                    ancestor -> { });
            scheduler.setCurrentTps(20.0);

            scheduler.enqueue(new MetatileJob("v1", "minecraft:overworld", 8, 0, 0, 4));
            scheduler.enqueue(new MetatileJob("v1", "minecraft:overworld", 8, 4, 0, 4));

            assertTrue(secondJob.await(5, TimeUnit.SECONDS), "the worker should have continued after a failure");
            assertEquals(1, scheduler.failedJobs());
        }
    }

    @Test
    void ancestorRefreshRunsOnlyWhenTheMetatileQueueIsIdle() throws Exception {
        try (RenderScheduler scheduler = new RenderScheduler(settings(16))) {
            CountDownLatch refreshed = new CountDownLatch(1);
            scheduler.start(job -> { }, ancestor -> refreshed.countDown());
            scheduler.setCurrentTps(20.0);
            scheduler.markAncestorDirty(new TileCoordinate("minecraft:overworld", 4, 0, 0));

            assertTrue(refreshed.await(5, TimeUnit.SECONDS), "an idle worker should drain the ancestor queue");
            assertTrue(scheduler.refreshedAncestors() >= 1);
        }
    }

    @Test
    void ancestorBacklogIsReportedForMetrics() {
        try (RenderScheduler scheduler = new RenderScheduler(settings(16))) {
            scheduler.markAncestorDirty(new TileCoordinate("minecraft:overworld", 4, 0, 0));
            scheduler.markAncestorDirty(new TileCoordinate("minecraft:overworld", 3, 0, 0));

            assertEquals(2, scheduler.ancestorBacklog());
        }
    }
}
