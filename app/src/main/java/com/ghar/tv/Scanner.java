package com.ghar.tv;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks many channels in parallel and reports each working one as soon as it passes. */
final class Scanner {

    interface Listener {
        /** Called from worker threads; result is Probe.OK, DEAD or UNSURE (after a second, slower try). */
        void onChecked(Channel c, int result);

        /** Called once, from a worker thread, when every channel has been checked. */
        void onFinished();
    }

    private final ExecutorService pool;
    private volatile boolean cancelled;

    /** @param threads how many channels to check at once */
    Scanner(int threads) {
        pool = Executors.newFixedThreadPool(Math.max(1, threads));
    }

    void start(List<Channel> channels, Listener listener) {
        if (channels.isEmpty()) {
            pool.execute(listener::onFinished);
            pool.shutdown();
            return;
        }
        AtomicInteger remaining = new AtomicInteger(channels.size());
        for (Channel c : channels) submit(c, false, remaining, listener);
    }

    /** A slow first answer gets one more, more patient try before the channel is judged. */
    private void submit(Channel c, boolean retry, AtomicInteger remaining, Listener listener) {
        try {
            pool.execute(() -> {
                if (cancelled) return;
                int result = Probe.check(c, retry ? Probe.SLOW_TIMEOUT_MS : Probe.TIMEOUT_MS);
                if (cancelled) return;
                if (result == Probe.UNSURE && !retry) {
                    submit(c, true, remaining, listener);
                    return;
                }
                listener.onChecked(c, result);
                if (remaining.decrementAndGet() == 0) {
                    listener.onFinished();
                    pool.shutdown();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // cancelled
        }
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
