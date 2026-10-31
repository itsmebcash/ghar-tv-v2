package com.ghar.tv;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks many channels in parallel and reports each working one as soon as it passes. */
final class Scanner {

    interface Listener {
        /** Called from worker threads. */
        void onChecked(Channel c, boolean working);

        /** Called once, from a worker thread, when every channel has been checked. */
        void onFinished();
    }

    private static final int THREADS = 24;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private volatile boolean cancelled;

    void start(List<Channel> channels, Listener listener) {
        if (channels.isEmpty()) {
            pool.execute(listener::onFinished);
            pool.shutdown();
            return;
        }
        AtomicInteger remaining = new AtomicInteger(channels.size());
        for (Channel c : channels) {
            pool.execute(() -> {
                if (cancelled) return;
                boolean ok = Probe.check(c);
                if (cancelled) return;
                listener.onChecked(c, ok);
                if (remaining.decrementAndGet() == 0) listener.onFinished();
            });
        }
        pool.shutdown();
    }

    void cancel() {
        cancelled = true;
        pool.shutdownNow();
        try {
            pool.awaitTermination(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
