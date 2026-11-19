package xyz.nextalone.nagram.hgram;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Tracks alerting notification senders in memory only; no message text or identifiers are persisted. */
public final class HgramNotificationDebouncer {

    public static final long DEBOUNCE_WINDOW_MS = 30_000L;
    private static final int MAX_TRACKED_SENDERS = 128;

    private final HashMap<Long, Long> lastAlertAt = new HashMap<>();

    /**
     * Returns true when this sender already produced an alert in the current window.
     * A suppressed message does not extend the original window.
     */
    public synchronized boolean shouldSuppress(long senderId, long elapsedRealtime) {
        if (senderId == 0) {
            return false;
        }

        Iterator<Map.Entry<Long, Long>> iterator = lastAlertAt.entrySet().iterator();
        while (iterator.hasNext()) {
            long previous = iterator.next().getValue();
            if (elapsedRealtime < previous || elapsedRealtime - previous >= DEBOUNCE_WINDOW_MS) {
                iterator.remove();
            }
        }

        Long previousAlert = lastAlertAt.get(senderId);
        if (previousAlert != null && elapsedRealtime >= previousAlert && elapsedRealtime - previousAlert < DEBOUNCE_WINDOW_MS) {
            return true;
        }

        if (lastAlertAt.size() >= MAX_TRACKED_SENDERS) {
            Long oldestSender = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<Long, Long> entry : lastAlertAt.entrySet()) {
                if (oldestSender == null || entry.getValue() < oldestTime) {
                    oldestSender = entry.getKey();
                    oldestTime = entry.getValue();
                }
            }
            lastAlertAt.remove(oldestSender);
        }
        lastAlertAt.put(senderId, elapsedRealtime);
        return false;
    }

    public synchronized void clear() {
        lastAlertAt.clear();
    }
}
