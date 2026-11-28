package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramCursorHistoryTest {

    @Test
    public void postsStopsCursorCycleWithExistingRows() {
        HgramCursorHistory history = new HgramCursorHistory();
        history.mark(7, false, 100, 10, 5);
        history.mark(7, false, 100, 9, 4);
        assertTrue(history.isRepeated(7, false, 100, 10, 5));
        assertTrue(history.isRepeated(7, false, 100, 9, 4));
        assertFalse(history.isRepeated(7, false, 100, 8, 3));
    }

    @Test
    public void postsStopsRateOnlyCursorCycleWithoutRows() {
        HgramCursorHistory history = new HgramCursorHistory();
        history.mark(3, true, 0, 0, 11);
        history.mark(3, true, 0, 0, 22);
        assertTrue(history.isRepeated(3, true, 0, 0, 11));
        assertTrue(history.isRepeated(3, true, 0, 0, 22));
        assertFalse(history.isRepeated(3, true, 0, 0, 33));
    }

    @Test
    public void postsFailedCursorCanRetry() {
        HgramCursorHistory history = new HgramCursorHistory();
        history.mark(4, false, 55, 6, 0);
        assertTrue(history.isRepeated(4, false, 55, 6, 0));
        history.forget(4, false, 55, 6, 0);
        assertFalse(history.isRepeated(4, false, 55, 6, 0));
    }

    @Test
    public void newGenerationStartsWithEmptyHistory() {
        HgramCursorHistory history = new HgramCursorHistory();
        history.mark(1, false, 10, 2, 0);
        assertFalse(history.isRepeated(2, false, 10, 2, 0));
    }
}
