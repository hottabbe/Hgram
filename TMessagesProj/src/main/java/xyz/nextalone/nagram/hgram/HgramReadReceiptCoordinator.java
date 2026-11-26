package xyz.nextalone.nagram.hgram;

import java.util.List;

/**
 * Thread-safe owner of the durable pending-read queue.
 *
 * <p>State is mutated synchronously so the in-memory queue always reflects the latest read, but
 * persistence is posted to the caller's background queue: the UI thread never waits for a disk
 * commit. The caller must use a FIFO queue that also runs the local unread reset, so the persisted
 * entry is written before the local zero is applied.</p>
 */
public final class HgramReadReceiptCoordinator {

    public interface Scheduler {
        boolean post(Runnable runnable);

        boolean postDelayed(Runnable runnable, long delayMillis);
    }

    public interface Persistence {
        /** @return true when the value is durable; false triggers a delayed retry. */
        boolean write(String value);
    }

    public interface ErrorLogger {
        void log(String message);
    }

    private static final int MAX_PERSIST_ATTEMPTS = 3;
    private static final long PERSIST_RETRY_DELAY_MS = 1000L;

    private final Object lock = new Object();
    private final Scheduler scheduler;
    private final Persistence persistence;
    private final ErrorLogger logger;
    private String value;

    public HgramReadReceiptCoordinator(String initial, Scheduler scheduler,
                                       Persistence persistence, ErrorLogger logger) {
        this.value = initial == null ? "" : initial;
        this.scheduler = scheduler;
        this.persistence = persistence;
        this.logger = logger;
    }

    public void remember(long dialogId, long replyId, long monoForumPeerId, int maxId, int maxDate) {
        final boolean changed;
        synchronized (lock) {
            final String next = HgramReadReceiptQueue.upsert(value,
                    new HgramReadReceiptQueue.Entry(dialogId, replyId, monoForumPeerId, maxId, maxDate));
            changed = !next.equals(value);
            value = next;
        }
        if (changed) {
            schedulePersist();
        }
    }

    /** Removes only when the stored ids still match the successfully sent request. */
    public void forget(long dialogId, long replyId, int maxId, int maxDate) {
        final boolean changed;
        synchronized (lock) {
            final String next = HgramReadReceiptQueue.removeIfMatches(value, dialogId, replyId, maxId, maxDate);
            changed = !next.equals(value);
            value = next;
        }
        if (changed) {
            schedulePersist();
        }
    }

    public void clear() {
        synchronized (lock) {
            value = "";
        }
        // The preference may still contain an older queue, so always write the empty state.
        schedulePersist();
    }

    public String snapshot() {
        synchronized (lock) {
            return value;
        }
    }

    public List<HgramReadReceiptQueue.Entry> entries() {
        return HgramReadReceiptQueue.parse(snapshot());
    }

    private void schedulePersist() {
        if (!scheduler.post(this::persistLatest)) {
            log("HGRAM_READ persist schedule failed");
        }
    }

    private void persistLatest() {
        final String snapshot;
        synchronized (lock) {
            snapshot = value;
        }
        if (persistAttempts(snapshot, MAX_PERSIST_ATTEMPTS)) {
            return;
        }
        log("HGRAM_READ persist failed");
        retryPersist(1);
    }

    private boolean persistAttempts(String snapshot, int attempts) {
        for (int i = 0; i < attempts; i++) {
            if (persistence.write(snapshot)) {
                return true;
            }
        }
        return false;
    }

    private void retryPersist(int attempt) {
        if (attempt > MAX_PERSIST_ATTEMPTS) {
            log("HGRAM_READ persist retry exhausted");
            return;
        }
        final boolean posted = scheduler.postDelayed(() -> {
            final String snapshot;
            synchronized (lock) {
                snapshot = value;
            }
            if (persistAttempts(snapshot, MAX_PERSIST_ATTEMPTS)) {
                return;
            }
            log("HGRAM_READ persist retry failed attempt=" + attempt);
            retryPersist(attempt + 1);
        }, PERSIST_RETRY_DELAY_MS);
        if (!posted) {
            log("HGRAM_READ persist retry schedule failed");
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }
}
