package xyz.nextalone.nagram.hgram;

/** Reserved state hook for the Hgram pinned-message card. */
public final class HgramPinnedState {
    private HgramPinnedState() {
    }

    public static void clear() {
        // The upstream 13.2.2 LaunchActivity has no pinned card to clear.
    }
}
