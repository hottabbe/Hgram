package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;

/**
 * Defers notification catch-up work until the initial holders are published. The queue is
 * local to one notification build, so callbacks registered before publication cannot mutate
 * a builder that is still being assembled.
 */
public final class HgramNotificationCatchup {

    private final ArrayList<Runnable> pending = new ArrayList<>();
    private boolean published;

    public void enqueue(Runnable start) {
        if (start == null) {
            return;
        }
        synchronized (this) {
            if (!published) {
                pending.add(start);
                return;
            }
        }
        startSafely(start);
    }

    public void publish() {
        final ArrayList<Runnable> toRun;
        synchronized (this) {
            if (published) {
                return;
            }
            published = true;
            toRun = new ArrayList<>(pending);
            pending.clear();
        }
        for (int i = 0; i < toRun.size(); i++) {
            startSafely(toRun.get(i));
        }
    }

    public void cancel() {
        synchronized (this) {
            pending.clear();
        }
    }

    private static void startSafely(Runnable start) {
        try {
            start.run();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
