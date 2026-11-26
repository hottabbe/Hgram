package org.telegram.ui.Components.Reactions;

/** Pure back-order policy for the selection reaction overlay. */
final class ChatSelectionReactionBack {

    static final int CLOSE_CUSTOM_EMOJI = 0;
    static final int HIDE_OVERLAY = 1;
    static final int PASS = 2;

    private ChatSelectionReactionBack() {
    }

    /**
     * Custom-emoji window is a separate floating window and must close first. Then the
     * selection overlay hides; once it is no longer visible, back is passed through.
     */
    static int resolve(boolean customEmojiWindowOpen, boolean overlayVisible) {
        if (customEmojiWindowOpen) {
            return CLOSE_CUSTOM_EMOJI;
        }
        return overlayVisible ? HIDE_OVERLAY : PASS;
    }
}
