package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramPaginationGateTest {

    @Test
    public void emptyOrRepeatedPageDoesNotContinue() {
        assertTrue(HgramPaginationGate.shouldContinue(true, true, false, 3, 3));
        assertFalse(HgramPaginationGate.shouldContinue(true, false, false, 3, 3));
        assertFalse(HgramPaginationGate.shouldContinue(false, true, false, 3, 3));
        assertFalse(HgramPaginationGate.shouldContinue(true, true, true, 3, 3));
    }

    @Test
    public void staleReplyCannotContinueNewQuery() {
        assertTrue(HgramPaginationGate.isStale(2, 3));
        assertFalse(HgramPaginationGate.isStale(3, 3));
        assertFalse(HgramPaginationGate.shouldContinue(true, true, false, 2, 3));
    }

    @Test
    public void autoLoadRequiresCursorProgressEvenWithVisibleRows() {
        assertTrue(HgramPaginationGate.shouldAutoLoad(true, false, 3, 3));
        assertFalse(HgramPaginationGate.shouldAutoLoad(false, false, 3, 3));
        assertFalse(HgramPaginationGate.shouldAutoLoad(true, true, 3, 3));
        assertFalse(HgramPaginationGate.shouldAutoLoad(true, false, 2, 3));
    }

    @Test
    public void repeatedWireCursorIsRejectedButRateOnlyAdvanceIsAllowed() {
        assertTrue(HgramPaginationGate.sameWireCursor(0, 0, 0, 0, 0, 0));
        assertTrue(HgramPaginationGate.sameWireCursor(100, 7, 0, 100, 7, 0));
        assertFalse(HgramPaginationGate.sameWireCursor(0, 0, 11, 0, 0, 0));
        assertFalse(HgramPaginationGate.sameWireCursor(100, 7, 0, 100, 8, 0));
        assertFalse(HgramPaginationGate.sameWireCursor(100, 7, 0, 101, 7, 0));
    }

    @Test
    public void staleFloodReplyCannotStartNewQuery() {
        assertTrue(HgramPaginationGate.isStaleQuery(4, 5, "old", "new"));
        assertFalse(HgramPaginationGate.isStaleQuery(5, 5, "new", "new"));
    }

    @Test
    public void staleFloodReplyCannotMutateNewsAfterQueryCleared() {
        assertTrue(HgramPaginationGate.isStaleQuery(5, 6, "query", ""));
        assertFalse(HgramPaginationGate.isStaleQuery(6, 6, "", ""));
        assertFalse(HgramPaginationGate.isStaleQuery(6, 6, null, ""));
    }
}
