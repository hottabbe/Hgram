package xyz.nextalone.nagram.hgram;

import java.util.List;

/**
 * Pure identity helpers for the sheet foreground lifecycle. A sheet must stay registered in
 * {@code LaunchActivity.sheetFragmentsStack} for exactly as long as its window is visible, and a
 * closing sheet must acquire the anti-peek guard only when the dismissal was actually accepted.
 */
public final class HgramSheetState {

    private HgramSheetState() {
    }


    public interface DismissDecision {
        boolean canDismiss();
    }

    /**
     * Atomic acceptance sequence for a sheet close. The delegate decision is taken exactly once,
     * before {@code onPreFinished} and before the reopen bit is cleared. A veto returns
     * {@code false} with no side effects; an already dismissed sheet is a no-op success.
     */
    public static boolean acceptDismiss(boolean alreadyDismissed,
                                        DismissDecision canDismiss,
                                        Runnable onPreFinished,
                                        Runnable clearReopenFlag) {
        if (alreadyDismissed) {
            return true;
        }
        if (canDismiss != null && !canDismiss.canDismiss()) {
            return false;
        }
        if (onPreFinished != null) {
            onPreFinished.run();
        }
        if (clearReopenFlag != null) {
            clearReopenFlag.run();
        }
        return true;
    }

    public static <T> boolean addIdentityOnce(List<T> entries, T token) {
        if (entries == null || token == null) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == token) {
                return false;
            }
        }
        entries.add(token);
        return true;
    }

    public static <T> boolean removeIdentity(List<T> entries, T token) {
        if (entries == null || token == null) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == token) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    public static <T> T restoreHolder(T[] holder, T token) {
        if (holder == null || holder.length == 0 || token == null) {
            return null;
        }
        if (holder[0] == null) {
            holder[0] = token;
        }
        return holder[0];
    }

    /**
     * Finalizes an actually accepted dismissal. A delegate veto ({@code dismissedNow == false})
     * and a repeated dismiss ({@code alreadyDismissed == true}) must leave the stack entry, the
     * layout holder and the closing guard untouched.
     */
    public static <T> boolean finalizeDismiss(List<T> entries, T[] holder, T token,
                                              boolean alreadyDismissed, boolean dismissedNow) {
        if (token == null || !dismissedNow || alreadyDismissed) {
            return false;
        }
        // Set the guard before the stack entry disappears, so the resolver cannot fall through
        // to the chat underneath between these two operations.
        HgramAntiPeek.onSheetDismissStarted(token);
        removeIdentity(entries, token);
        if (holder != null && holder.length > 0 && holder[0] == token) {
            holder[0] = null;
        }
        return true;
    }

    /**
     * Restores stack ownership and the stable layout reference for a same-sheet reopen. The
     * entry is added at most once; the existing holder wins if it already references a layout.
     */
    public static <T> T restoreForShow(List<T> entries, T[] holder, T token) {
        if (token == null) {
            return null;
        }
        addIdentityOnce(entries, token);
        return restoreHolder(holder, token);
    }
}
