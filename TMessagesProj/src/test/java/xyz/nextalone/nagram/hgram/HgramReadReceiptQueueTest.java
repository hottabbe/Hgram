package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class HgramReadReceiptQueueTest {

    @Test
    public void upsertKeepsOneEntryPerDialogAndThread() {
        final HgramReadReceiptQueue.Entry first = new HgramReadReceiptQueue.Entry(
                -100123L, 0L, 0L, 10, 1000);
        final HgramReadReceiptQueue.Entry updated = new HgramReadReceiptQueue.Entry(
                -100123L, 0L, 0L, 25, 1200);
        final String raw = HgramReadReceiptQueue.upsert(
                HgramReadReceiptQueue.upsert("", first), updated);
        final List<HgramReadReceiptQueue.Entry> entries = HgramReadReceiptQueue.parse(raw);
        assertEquals(1, entries.size());
        assertEquals(25, entries.get(0).maxId);
        assertEquals(1200, entries.get(0).maxDate);
    }

    @Test
    public void differentThreadsCoexistAndRemoveIsTargeted() {
        final String raw = HgramReadReceiptQueue.upsert(
                HgramReadReceiptQueue.upsert("",
                        new HgramReadReceiptQueue.Entry(42L, 0L, 0L, 5, 50)),
                new HgramReadReceiptQueue.Entry(42L, 77L, 0L, 9, 90));
        assertEquals(2, HgramReadReceiptQueue.parse(raw).size());

        final String withoutThread = HgramReadReceiptQueue.remove(raw, 42L, 77L);
        final List<HgramReadReceiptQueue.Entry> entries = HgramReadReceiptQueue.parse(withoutThread);
        assertEquals(1, entries.size());
        assertEquals(0L, entries.get(0).replyId);
        assertEquals(5, entries.get(0).maxId);
    }

    @Test
    public void malformedOrEmptyPreferencesAreSafe() {
        assertTrue(HgramReadReceiptQueue.parse(null).isEmpty());
        assertTrue(HgramReadReceiptQueue.parse("").isEmpty());
        assertTrue(HgramReadReceiptQueue.parse("broken;1,2,3").isEmpty());
        assertEquals("", HgramReadReceiptQueue.remove("broken", 1L, 0L));
    }

    @Test
    public void ghostPolicySendsOnlyWithConsentOrOneShotAllowance() {
        assertTrue(HgramReadReceiptQueue.shouldSend(true, false));
        assertTrue(HgramReadReceiptQueue.shouldSend(false, true));
        assertFalse(HgramReadReceiptQueue.shouldSend(false, false));
    }

    @Test
    public void successfulOldRequestDoesNotRemoveNewerMaxIds() {
        String raw = HgramReadReceiptQueue.upsert("",
                new HgramReadReceiptQueue.Entry(42L, 0L, 0L, 10, 100));
        raw = HgramReadReceiptQueue.upsert(raw,
                new HgramReadReceiptQueue.Entry(42L, 0L, 0L, 20, 200));

        final String afterOld = HgramReadReceiptQueue.removeIfMatches(raw, 42L, 0L, 10, 100);
        assertEquals(raw, afterOld);
        assertEquals(20, HgramReadReceiptQueue.parse(afterOld).get(0).maxId);

        final String afterCurrent = HgramReadReceiptQueue.removeIfMatches(raw, 42L, 0L, 20, 200);
        assertTrue(HgramReadReceiptQueue.parse(afterCurrent).isEmpty());
    }

    @Test
    public void retryDelayKeepsStartedRequestsOnTheLongRetry() {
        assertEquals(30000L, HgramReadReceiptQueue.retryDelayMillis(true));
        assertEquals(5000L, HgramReadReceiptQueue.retryDelayMillis(false));
    }
}
