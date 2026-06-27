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
import java.util.concurrent.atomic.AtomicReference;

import com.liedowncraft.cartography.config.CartographySettings;
import com.liedowncraft.cartography.core.MetatileJob;

public final class RenderScheduler implements Closeable {
    private final BlockingQueue<MetatileJob> queue;
    private final Set<MetatileJob> queuedJobs = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Double> currentTps = new AtomicReference<>(20.0D);
    private final CartographySettings.SchedulerSettings settings;
    private volatile boolean paused;

    public RenderScheduler(CartographySettings.SchedulerSettings settings) {
        this.settings = settings;
        this.queue = new LinkedBlockingQueue<>(settings.queueCapacity());
        this.workers = Executors.newFixedThreadPool(settings.workerThreads(), new SchedulerThreadFactory());
    }

    public void start(RenderJobHandler handler) {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        for (int workerIndex = 0; workerIndex < settings.workerThreads(); workerIndex++) {
            workers.submit(() -> runLoop(handler));
        }
    }

    public boolean enqueue(MetatileJob job) {
        if (!queuedJobs.add(job)) {
            return true;
        }

        if (!queue.offer(job)) {
            queuedJobs.remove(job);
            return false;
        }
        return true;
    }

    public int queueDepth() {
        return queue.size();
    }

    public boolean isPaused() {
        return paused;
    }

    public void setCurrentTps(double tps) {
        currentTps.set(tps);
    }

    private void runLoop(RenderJobHandler handler) {
        while (running.get()) {
            try {
                if (currentTps.get() < settings.minTps()) {
                    paused = true;
                    TimeUnit.MILLISECONDS.sleep(100);
                    continue;
                }

                paused = false;
                MetatileJob job = queue.poll(250, TimeUnit.MILLISECONDS);
                if (job == null) {
                    continue;
                }

                try {
                    handler.handle(job);
                } finally {
                    queuedJobs.remove(job);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Leave the scheduler running; failed jobs can be retried by future misses or dirty events.
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
