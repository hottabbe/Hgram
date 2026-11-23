package org.telegram.messenger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PushPlaceholderStoreTest {

    @Test
    public void insertIfAbsent_insertsOnce() {
        Set<String> rows = new HashSet<>();
        AtomicInteger inserts = new AtomicInteger();
        PushPlaceholderStore.MessageExistence exists =
                (uid, mid) -> rows.contains(uid + ":" + mid);
        Runnable insert = () -> {
            inserts.incrementAndGet();
            rows.add("10:20");
        };

        assertTrue(PushPlaceholderStore.insertIfAbsent(exists, 10, 20, insert));
        assertFalse(PushPlaceholderStore.insertIfAbsent(exists, 10, 20, insert));
        assertEquals("only one physical insert", 1, inserts.get());
        assertEquals(1, rows.size());
    }

    @Test
    public void insertIfAbsent_keepsExistingFullMessage() {
        Map<String, String> rows = new HashMap<>();
        rows.put("10:20", "full message text");
        AtomicInteger overwrites = new AtomicInteger();
        PushPlaceholderStore.MessageExistence exists =
                (uid, mid) -> rows.containsKey(uid + ":" + mid);

        boolean inserted = PushPlaceholderStore.insertIfAbsent(exists, 10, 20, () -> {
            overwrites.incrementAndGet();
            rows.put("10:20", "short push preview");
        });

        assertFalse(inserted);
        assertEquals("the existing full row must not be overwritten", 0, overwrites.get());
        assertEquals("full message text", rows.get("10:20"));
    }

    /** Canonical inbox_max verdict used by MessagesStorage before any placeholder write. */
    @Test
    public void isAlreadyRead_comparesInboxMaxWithoutClamp() {
        assertTrue(PushPlaceholderStore.isAlreadyRead(1001, 1001));
        assertTrue(PushPlaceholderStore.isAlreadyRead(1010, 1001));
        assertFalse(PushPlaceholderStore.isAlreadyRead(1000, 1001));
        assertFalse("no read information", PushPlaceholderStore.isAlreadyRead(0, 1001));
    }

    @Test
    public void storePlaceholder_commitsThenPublishes() {
        FakeTransaction transaction = new FakeTransaction();

        assertTrue(PushPlaceholderStore.storePlaceholder(transaction));
        assertEquals(Arrays.asList("begin", "insertMessage", "insertPending", "messageExists",
                "pendingExists", "commit", "publish"), transaction.calls);
        assertFalse("rollback after a successful commit", transaction.calls.contains("rollback"));
    }

    /** The message row step succeeded but the postcondition SELECT cannot see the row. */
    @Test
    public void placeholderRowFailureDoesNotPublish() {
        FakeTransaction transaction = new FakeTransaction();
        transaction.dropMessageRow = true;

        assertFalse(PushPlaceholderStore.storePlaceholder(transaction));
        assertTrue(transaction.calls.contains("rollback"));
        assertFalse("no commit without both rows", transaction.calls.contains("commit"));
        assertFalse("registry must not be marked", transaction.calls.contains("publish"));
    }

    /** The durable pending write throws: the whole placeholder transaction is rolled back. */
    @Test
    public void pendingInsertFailureRollsBackPlaceholder() {
        FakeTransaction transaction = new FakeTransaction();
        transaction.failPendingInsert = true;

        assertFalse(PushPlaceholderStore.storePlaceholder(transaction));
        assertEquals(Arrays.asList("begin", "insertMessage", "insertPending", "rollback"),
                transaction.calls);
    }

    @Test
    public void messageInsertFailureRollsBackPlaceholder() {
        FakeTransaction transaction = new FakeTransaction();
        transaction.failMessageInsert = true;

        assertFalse(PushPlaceholderStore.storePlaceholder(transaction));
        assertFalse(transaction.calls.contains("commit"));
        assertFalse(transaction.calls.contains("publish"));
        assertTrue(transaction.calls.contains("rollback"));
    }

    @Test
    public void commitFailureRollsBackAndDoesNotPublish() {
        FakeTransaction transaction = new FakeTransaction();
        transaction.failCommit = true;

        assertFalse(PushPlaceholderStore.storePlaceholder(transaction));
        assertFalse(transaction.calls.contains("publish"));
        assertTrue(transaction.calls.contains("rollback"));
    }

    /** Native COMMIT seam throws: no publish/mark, and the caller-owned rollback still runs. */
    @Test
    public void failedNativeCommitDoesNotPublishOrMark() {
        FakeTransaction transaction = new FakeTransaction();
        transaction.failCommit = true;

        assertFalse(PushPlaceholderStore.storePlaceholder(transaction));
        assertEquals(Arrays.asList("begin", "insertMessage", "insertPending", "messageExists",
                "pendingExists", "commit", "rollback"), transaction.calls);
        assertFalse("registry mark must wait for a confirmed commit",
                transaction.calls.contains("publish"));
    }

    @Test
    public void missingPendingPostconditionDoesNotPublish() {
        FakeTransaction transaction = new FakeTransaction();
        transaction.dropPendingRow = true;

        assertFalse(PushPlaceholderStore.storePlaceholder(transaction));
        assertFalse(transaction.calls.contains("commit"));
        assertFalse(transaction.calls.contains("publish"));
        assertTrue(transaction.calls.contains("rollback"));
    }

    private static final class FakeTransaction implements PushPlaceholderStore.PlaceholderTransaction {
        final List<String> calls = new ArrayList<>();
        boolean messageRow;
        boolean pendingRow;
        boolean dropMessageRow;
        boolean dropPendingRow;
        boolean failMessageInsert;
        boolean failPendingInsert;
        boolean failCommit;

        @Override
        public void beginTransaction() {
            calls.add("begin");
        }

        @Override
        public void insertMessageRow() {
            calls.add("insertMessage");
            if (failMessageInsert) {
                throw new IllegalStateException("messages_v2 write failed");
            }
            messageRow = true;
        }

        @Override
        public void insertPendingRow() {
            calls.add("insertPending");
            if (failPendingInsert) {
                throw new IllegalStateException("push_pending_messages write failed");
            }
            pendingRow = true;
        }

        @Override
        public boolean messageRowExists() {
            calls.add("messageExists");
            return messageRow && !dropMessageRow;
        }

        @Override
        public boolean pendingRowExists() {
            calls.add("pendingExists");
            return pendingRow && !dropPendingRow;
        }

        @Override
        public void commitTransaction() {
            calls.add("commit");
            if (failCommit) {
                throw new IllegalStateException("commit failed");
            }
        }

        @Override
        public void rollbackTransaction() {
            calls.add("rollback");
            messageRow = false;
            pendingRow = false;
        }

        @Override
        public void onCommitted() {
            calls.add("publish");
        }
    }
}
