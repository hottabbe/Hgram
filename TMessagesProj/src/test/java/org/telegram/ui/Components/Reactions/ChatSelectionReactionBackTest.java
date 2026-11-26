package org.telegram.ui.Components.Reactions;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChatSelectionReactionBackTest {

    @Test
    public void customEmojiWindowClosesBeforeOverlay() {
        assertEquals(ChatSelectionReactionBack.CLOSE_CUSTOM_EMOJI,
                ChatSelectionReactionBack.resolve(true, true));
        assertEquals(ChatSelectionReactionBack.CLOSE_CUSTOM_EMOJI,
                ChatSelectionReactionBack.resolve(true, false));
    }

    @Test
    public void visibleOverlayHidesOnBack() {
        assertEquals(ChatSelectionReactionBack.HIDE_OVERLAY,
                ChatSelectionReactionBack.resolve(false, true));
    }

    @Test
    public void repeatedBackAfterHidePassesThrough() {
        assertEquals(ChatSelectionReactionBack.PASS,
                ChatSelectionReactionBack.resolve(false, false));
    }
}
