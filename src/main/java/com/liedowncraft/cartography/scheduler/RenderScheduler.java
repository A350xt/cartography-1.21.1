package com.liedowncraft.cartography.scheduler;

import java.io.Closeable;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.core.MetatileJob;
import com.liedowncraft.cartography.core.TileCoordinate;

/**
 * Bounded background render scheduler (technical plan v2.0, sections 6 and A.1).
 *
 * <p>Rendering must never outrank the server: workers pause below {@code pauseBelowTps} and only
 * resume above {@code resumeAboveTps}. The gap between the two is deliberate — a single threshold
 * would make workers flap on and off while TPS hovers at the limit.
 *
 * <p>Max-zoom metatile jobs take priority; ancestor downsampling runs only when the metatile queue
 * is idle, since a stale low-zoom tile is far less visible than a missing max-zoom one.
 */
public final class RenderScheduler implements Closeable {
    private final BlockingQueue<MetatileJob> queue;
    private final Set<MetatileJob> queuedJobs = ConcurrentHashMap.newKeySet();
    private final AncestorDirtySet ancestorDirtySet = new AncestorDirtySet();
    private final ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean();
    private final CartographySettings.SchedulerSettings settings;

    private final AtomicLong renderedJobs = new AtomicLong();
    private final AtomicLong failedJobs = new AtomicLong();
    private final AtomicLong droppedJobs = new AtomicLong();
    private final AtomicLong refreshedAncestors = new AtomicLong();

    private volatile double currentTps = 20.0D;
    private volatile boolean paused;

    public RenderScheduler(CartographySettings.SchedulerSettings settings) {
        this.settings = settings;
        this.queue = new LinkedBlockingQueue<>(settings.queueCapacity());
        this.workers = Executors.newFixedThreadPool(settings.workerThreads(), new SchedulerThreadFactory());
        // Evaluate the pause rule against the assumed starting TPS rather than defaulting to running.
        // A scheduler configured to pause below an unreachable threshold must start paused.
        setCurrentTps(currentTps);
    }

    public void start(RenderJobHandler handler, AncestorRefreshHandler ancestorHandler) {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        for (int workerIndex = 0; workerIndex < settings.workerThreads(); workerIndex++) {
            workers.submit(() -> runLoop(handler, ancestorHandler));
        }
    }

    /** @return false when the queue is saturated; the caller keeps serving a pending tile. */
    public boolean enqueue(MetatileJob job) {
        if (!queuedJobs.add(job)) {
            return true;
        }

        if (!queue.offer(job)) {
            queuedJobs.remove(job);
            droppedJobs.incrementAndGet();
            return false;
        }
        return true;
    }

    public void markAncestorDirty(TileCoordinate ancestor) {
        ancestorDirtySet.add(ancestor);
    }

    public int queueDepth() {
        return queue.size();
    }

    public int ancestorBacklog() {
        return ancestorDirtySet.backlog();
    }

    public boolean isPaused() {
        return paused;
    }

    public long renderedJobs() {
        return renderedJobs.get();
    }

    public long failedJobs() {
        return failedJobs.get();
    }

    public long droppedJobs() {
        return droppedJobs.get();
    }

    public long refreshedAncestors() {
        return refreshedAncestors.get();
    }

    public double currentTps() {
        return currentTps;
    }

    public void setCurrentTps(double tps) {
        this.currentTps = tps;
        // Hysteresis: pause below the floor, resume only above the higher ceiling, hold otherwise.
        if (tps < settings.minTps()) {
            paused = true;
        } else if (tps >= settings.resumeTps()) {
            paused = false;
        }
    }

    private void runLoop(RenderJobHandler handler, AncestorRefreshHandler ancestorHandler) {
        while (running.get()) {
            try {
                if (paused) {
                    TimeUnit.MILLISECONDS.sleep(100);
                    continue;
                }

                MetatileJob job = queue.poll(250, TimeUnit.MILLISECONDS);
                if (job != null) {
                    try {
                        handler.handle(job);
                        renderedJobs.incrementAndGet();
                    } catch (Exception exception) {
                        failedJobs.incrementAndGet();
                        // Leave the scheduler running. An unloaded chunk is the common cause, and the
                        // next tile request or dirty event re-queues the job.
                    } finally {
                        queuedJobs.remove(job);
                    }
                    continue;
                }

                // The metatile queue is idle, so spend the lull on low-zoom catch-up.
                refreshAncestorBudget(ancestorHandler);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Never let a scheduling-level fault kill a worker thread.
            }
        }
    }

    private void refreshAncestorBudget(AncestorRefreshHandler ancestorHandler) {
        for (int refreshed = 0; refreshed < settings.ancestorBudgetPerPass(); refreshed++) {
            if (paused || !queue.isEmpty()) {
                return;
            }

            TileCoordinate ancestor = ancestorDirtySet.popBest().orElse(null);
            if (ancestor == null) {
                return;
            }

            try {
                ancestorHandler.refresh(ancestor);
                refreshedAncestors.incrementAndGet();
            } catch (Exception exception) {
                failedJobs.incrementAndGet();
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        workers.shutdownNow();
    }

    @FunctionalInterface
    public interface RenderJobHandler {
        void handle(MetatileJob job) throws Exception;
    }

    @FunctionalInterface
    public interface AncestorRefreshHandler {
        void refresh(TileCoordinate ancestor) throws Exception;
    }

    private static final class SchedulerThreadFactory implements ThreadFactory {
        private int nextId = 1;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cartography-render-" + nextId++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
