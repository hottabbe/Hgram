package xyz.nextalone.nagram.hgram;

/** Decides whether the chat content may receive a touch while the scrim menu is active. */
public final class HgramScrimTouchPolicy {

    private HgramScrimTouchPolicy() {
    }

    /**
     * The popup menu lives in its own window, so the chat content must stay out of the
     * touch path. The inline menu is a child of the content view and needs that same path.
     */
    public static boolean allowContentDispatch(boolean hasInlineOverlay, boolean hasScrim, boolean isBackButton) {
        return hasInlineOverlay || !hasScrim || isBackButton;
    }
}
