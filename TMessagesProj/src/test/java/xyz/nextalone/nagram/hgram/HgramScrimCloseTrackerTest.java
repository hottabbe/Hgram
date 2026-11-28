package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HgramScrimCloseTrackerTest {
    @Test
    public void firstCloseWinsAndSecondIsIgnored() {
        final HgramScrimCloseTracker tracker = new HgramScrimCloseTracker();
        tracker.beginSession();
        assertTrue(tracker.markClosed(HgramScrimCloseTracker.Reason.REACTION));
        assertFalse(tracker.markClosed(HgramScrimCloseTracker.Reason.MENU_ITEM));
        assertTrue(tracker.isClosed());
        assertEquals(HgramScrimCloseTracker.Reason.REACTION, tracker.getReason());
    }

    @Test
    public void reactionClosesOnlyWhileMenuIsStillOpen() {
        final HgramScrimCloseTracker tracker = new HgramScrimCloseTracker();
        tracker.beginSession();
        assertTrue(tracker.shouldCloseAfterReaction(true));
        assertFalse(tracker.shouldCloseAfterReaction(false));
    }

    @Test
    public void starReactionThatClosedPanelIsNotClosedTwice() {
        final HgramScrimCloseTracker tracker = new HgramScrimCloseTracker();
        tracker.beginSession();
        tracker.markClosed(HgramScrimCloseTracker.Reason.REACTION);
        assertFalse(tracker.shouldCloseAfterReaction(true));
    }

    @Test
    public void newSessionClearsPreviousClose() {
        final HgramScrimCloseTracker tracker = new HgramScrimCloseTracker();
        tracker.beginSession();
        tracker.markClosed(HgramScrimCloseTracker.Reason.BACK);
        tracker.beginSession();
        assertFalse(tracker.isClosed());
        assertNull(tracker.getReason());
        assertTrue(tracker.shouldCloseAfterReaction(true));
    }

    @Test
    public void resetClearsState() {
        final HgramScrimCloseTracker tracker = new HgramScrimCloseTracker();
        tracker.beginSession();
        tracker.markClosed(HgramScrimCloseTracker.Reason.OUTSIDE);
        tracker.reset();
        assertFalse(tracker.isClosed());
        assertNull(tracker.getReason());
    }
}
