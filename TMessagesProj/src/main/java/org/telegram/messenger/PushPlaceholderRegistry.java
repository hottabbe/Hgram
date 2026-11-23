package org.telegram.messenger;

import java.util.HashMap;
import java.util.HashSet;

/**
 * In-memory index of push placeholders.
 *
 * <p>Two things are tracked separately:
 * <ul>
 *   <li><b>durable pending</b> — a preview row exists in {@code push_pending_messages} and the real
 *   message was not written yet. It is restored by
 *   {@link MessagesStorage#loadPushPending()} after a process restart and removed together with the
 *   database row when the real message is written.</li>
 *   <li><b>one-shot UI replacement</b> — the open chat still shows the preview and must replace it
 *   when the real message with the same id arrives. {@link #consume} burns this exactly once; a
 *   storage-side {@link #clear(long, int)} keeps the right intact so the "real write first, UI
 *   update second" ordering still replaces the preview.</li>
 * </ul>
 *
 * <p>The two sets have different owners: only {@link #consume} may touch {@code replacements}, and
 * only {@link #clear} may touch {@code pending}. {@link #consume} must never acknowledge the
 * durable row, because a failed or unconfirmed storage write still needs the pending marker.
 */
public class PushPlaceholderRegistry {

    private final HashMap<Long, HashSet<Integer>> pending = new HashMap<>();
    private final HashMap<Long, HashSet<Integer>> replacements = new HashMap<>();

    /**
     * Marks a live placeholder: both the durable pending row and the one-shot UI replacement right.
     * {@link MessagesStorage#loadPushPending()} uses the same method so an index restored from disk
     * also grants the UI replacement after a restart.
     */
    public synchronized void mark(long dialogId, int mid) {
        if (dialogId == 0 || mid <= 0) {
            return;
        }
        add(pending, dialogId, mid);
        add(replacements, dialogId, mid);
    }

    /**
     * Consumes only the one-shot UI replacement right. The durable {@code pending} marker is not
     * touched: it outlives the UI update until {@link #clear(long, int)} confirms that the real row
     * was written to storage. A failed real write therefore keeps the placeholder pending.
     *
     * @return true exactly once for every marked {@code (dialogId, mid)} pair.
     */
    public synchronized boolean consume(long dialogId, int mid) {
        return remove(replacements, dialogId, mid);
    }

    /**
     * Storage-side acknowledgement that the durable {@code push_pending_messages} row is gone.
     * This is the only method that removes the durable marker. The one-shot UI right is
     * intentionally preserved: a real write can reach storage before
     * {@code updateInterfaceWithMessages} gets a chance to consume the marker.
     */
    public synchronized void clear(long dialogId, int mid) {
        remove(pending, dialogId, mid);
    }

    /** True while a durable pending row exists for the dialog. */
    public synchronized boolean hasPending(long dialogId) {
        return contains(pending, dialogId);
    }

    /** True while the durable pending row exists; {@link #consume} does not change it. */
    public synchronized boolean hasPending(long dialogId, int mid) {
        return contains(pending, dialogId, mid);
    }

    /** True while the one-shot UI replacement right was not consumed yet. */
    public synchronized boolean hasReplacement(long dialogId, int mid) {
        return contains(replacements, dialogId, mid);
    }

    /** True while a durable pending row still needs a difference refresh. */
    public synchronized boolean hasAny() {
        return !pending.isEmpty();
    }

    public synchronized void clear() {
        pending.clear();
        replacements.clear();
    }

    private static void add(HashMap<Long, HashSet<Integer>> map, long dialogId, int mid) {
        HashSet<Integer> mids = map.get(dialogId);
        if (mids == null) {
            mids = new HashSet<>();
            map.put(dialogId, mids);
        }
        mids.add(mid);
    }

    private static boolean remove(HashMap<Long, HashSet<Integer>> map, long dialogId, int mid) {
        HashSet<Integer> mids = map.get(dialogId);
        if (mids == null || !mids.remove(mid)) {
            return false;
        }
        if (mids.isEmpty()) {
            map.remove(dialogId);
        }
        return true;
    }

    private static boolean contains(HashMap<Long, HashSet<Integer>> map, long dialogId) {
        HashSet<Integer> mids = map.get(dialogId);
        return mids != null && !mids.isEmpty();
    }

    private static boolean contains(HashMap<Long, HashSet<Integer>> map, long dialogId, int mid) {
        HashSet<Integer> mids = map.get(dialogId);
        return mids != null && mids.contains(mid);
    }
}
