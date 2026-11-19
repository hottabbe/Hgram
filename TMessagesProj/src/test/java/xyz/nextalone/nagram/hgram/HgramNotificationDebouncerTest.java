package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramNotificationDebouncerTest {

    @Test
    public void firstMessageAlertsAndNextIsSuppressedForThirtySeconds() {
        HgramNotificationDebouncer debouncer = new HgramNotificationDebouncer();

        assertFalse(debouncer.shouldSuppress(42, 1_000));
        assertTrue(debouncer.shouldSuppress(42, 29_000));
        assertFalse(debouncer.shouldSuppress(42, 31_000));
    }

    @Test
    public void suppressedMessagesDoNotExtendTheWindow() {
        HgramNotificationDebouncer debouncer = new HgramNotificationDebouncer();

        assertFalse(debouncer.shouldSuppress(42, 1_000));
        assertTrue(debouncer.shouldSuppress(42, 20_000));
        assertFalse(debouncer.shouldSuppress(42, 31_000));
    }

    @Test
    public void differentSendersHaveIndependentWindows() {
        HgramNotificationDebouncer debouncer = new HgramNotificationDebouncer();

        assertFalse(debouncer.shouldSuppress(42, 1_000));
        assertFalse(debouncer.shouldSuppress(43, 2_000));
        assertTrue(debouncer.shouldSuppress(42, 3_000));
        assertTrue(debouncer.shouldSuppress(43, 4_000));
    }

    @Test
    public void zeroSenderIdIsNeverTracked() {
        HgramNotificationDebouncer debouncer = new HgramNotificationDebouncer();

        assertFalse(debouncer.shouldSuppress(0, 1_000));
        assertFalse(debouncer.shouldSuppress(0, 2_000));
    }

    @Test
    public void capacityEvictsOldestAlertRatherThanAnArbitrarySender() {
        HgramNotificationDebouncer debouncer = new HgramNotificationDebouncer();
        for (int i = 128; i >= 1; i--) {
            assertFalse(debouncer.shouldSuppress(i, 129 - i));
        }
        assertFalse(debouncer.shouldSuppress(129, 129));
        assertTrue(debouncer.shouldSuppress(1, 130));
        assertFalse(debouncer.shouldSuppress(128, 131));
    }

    @Test
    public void clockResetDoesNotKeepFutureEntries() {
        HgramNotificationDebouncer debouncer = new HgramNotificationDebouncer();
        assertFalse(debouncer.shouldSuppress(42, 20_000));
        assertFalse(debouncer.shouldSuppress(43, 1_000));
        assertFalse(debouncer.shouldSuppress(42, 1_001));
    }
}
