package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.AndroidUtilities;

import java.util.function.Consumer;

/**
 * Debounces haptic previews for one dialog: a slider drag plays the profile at most once,
 * and only when the value actually changed since the drag started or the last preview.
 */
public final class HgramHapticPreview {

    public static final long DEBOUNCE_MS = 150;

    private Runnable pending;
    private String lastPlayedState;
    private String dragStartState;
    private int generation;

    /** Remembers the profile the current drag started from and drops any queued preview. */
    public void beginTracking(HgramHapticProfile current) {
        cancel();
        dragStartState = stateOf(current);
    }

    /** Queues {@code play} after {@link #DEBOUNCE_MS} if the value differs from the drag start. */
    public void release(HgramHapticProfile current, Runnable play) {
        if (play == null) {
            cancel();
            return;
        }
        release(current, ignored -> play.run());
    }

    /**
     * Queues {@code play} after {@link #DEBOUNCE_MS} if the value differs from the drag start.
     * The callback receives a copy of the profile captured at release time, so later edits to
     * the dialog cannot change what is played.
     */
    public void release(HgramHapticProfile current, Consumer<HgramHapticProfile> play) {
        cancel();
        if (current == null || play == null) return;
        final String state = stateOf(current);
        if (state.equals(dragStartState) || state.equals(lastPlayedState)) return;
        final HgramHapticProfile snapshot = current.copy();
        final int token = ++generation;
        pending = () -> {
            if (token != generation) return;
            pending = null;
            lastPlayedState = state;
            play.accept(snapshot);
        };
        AndroidUtilities.runOnUIThread(pending, DEBOUNCE_MS);
    }

    /** Drops a queued preview without playing it. */
    public void cancel() {
        generation++;
        if (pending != null) {
            AndroidUtilities.cancelRunOnUIThread(pending);
            pending = null;
        }
    }

    /** Cancels a queued preview and forgets the drag/last-played baselines (dialog reset). */
    public void reset() {
        cancel();
        lastPlayedState = null;
        dragStartState = null;
    }

    private static String stateOf(HgramHapticProfile p) {
        if (p == null) return "";
        return p.intensity + "," + p.durationMs + "," + p.pattern + "," + p.gapMs + ","
                + (p.effect == null ? HgramHapticEffect.AUTO : p.effect).name();
    }
}
