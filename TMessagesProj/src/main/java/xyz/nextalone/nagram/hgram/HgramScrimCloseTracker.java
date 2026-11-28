package xyz.nextalone.nagram.hgram;

/**
 * Tracks one message-menu session so a close triggered by the reaction path is not executed
 * twice (the star/paid reaction sheet closes the panel before it is shown).
 */
public final class HgramScrimCloseTracker {
    public enum Reason {
        REACTION,
        MENU_ITEM,
        OUTSIDE,
        BACK,
        OTHER
    }

    private boolean closed;
    private Reason reason;

    public void beginSession() {
        closed = false;
        reason = null;
    }

    /**
     * @return true when this call was the first close of the session.
     */
    public boolean markClosed(Reason reason) {
        if (closed) {
            return false;
        }
        closed = true;
        this.reason = reason;
        return true;
    }

    public boolean isClosed() {
        return closed;
    }

    public Reason getReason() {
        return reason;
    }

    public boolean shouldCloseAfterReaction(boolean menuVisible) {
        return menuVisible && !closed;
    }

    public void reset() {
        closed = false;
        reason = null;
    }
}
