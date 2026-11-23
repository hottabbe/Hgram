package org.telegram.messenger;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Applies the production placeholders SQL contract to a real (Robolectric) SQLite database.
 * {@code MessagesStorage} itself needs the native loader, so the statements and the pure verdicts
 * are taken from {@link PushPlaceholderStore} and exercised in the same order the storage queue uses.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PushPlaceholderStorageTest {

    /**
     * Full 20-column shape of the production {@code messages_v2}. Column 6 is the serialized
     * message blob used by the assertions; the other columns only keep the production column
     * arity so {@link PushPlaceholderStore#REPLACE_MESSAGE_V2_SQL} can be executed verbatim.
     */
    private static final String CREATE_MESSAGES_V2_SQL =
            "CREATE TABLE messages_v2(" +
                    "mid INTEGER, uid INTEGER, unread INTEGER, send_state INTEGER, date INTEGER, " +
                    "data TEXT NOT NULL, out INTEGER, ttl INTEGER, views INTEGER, story TEXT, " +
                    "sticker INTEGER, mentioned INTEGER, forwards INTEGER, replies TEXT, " +
                    "reply_to INTEGER, channel_id INTEGER, extra INTEGER, custom TEXT, " +
                    "grouped_id INTEGER, story_id INTEGER, PRIMARY KEY(uid, mid))";

    /** Same 20-column arity as {@code messages_topics}, with topic_id as the third column. */
    private static final String CREATE_MESSAGES_TOPICS_SQL =
            "CREATE TABLE messages_topics(" +
                    "mid INTEGER, uid INTEGER, topic_id INTEGER, unread INTEGER, send_state INTEGER, " +
                    "date INTEGER, data TEXT NOT NULL, out INTEGER, ttl INTEGER, views INTEGER, " +
                    "story TEXT, sticker INTEGER, mentioned INTEGER, forwards INTEGER, replies TEXT, " +
                    "reply_to INTEGER, channel_id INTEGER, extra INTEGER, custom TEXT, " +
                    "story_id INTEGER, PRIMARY KEY(uid, mid, topic_id))";

    private static final String ADVANCE_READ_MAX_SQL =
            "UPDATE dialogs SET inbox_max = max((SELECT inbox_max FROM dialogs WHERE did = ?), ?) WHERE did = ?";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static void applyMigration(SQLiteDatabase db) {
        for (String sql : PushPlaceholderStore.MIGRATION_179_TO_180_SQL) {
            db.execSQL(sql);
        }
    }

    private static SQLiteDatabase openPushDatabase(TemporaryFolder folder, String name) throws Exception {
        File file = folder.newFile(name);
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL("CREATE TABLE dialogs(did INTEGER PRIMARY KEY, inbox_max INTEGER)");
        db.execSQL(CREATE_MESSAGES_V2_SQL);
        db.execSQL(CREATE_MESSAGES_TOPICS_SQL);
        applyMigration(db);
        return db;
    }

    private static String[] stringArgs(Object[] args) {
        if (args == null) {
            return null;
        }
        String[] result = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = String.valueOf(args[i]);
        }
        return result;
    }

    private static Integer queryInt(SQLiteDatabase db, String sql, Object[] args) {
        try (Cursor cursor = db.rawQuery(sql, stringArgs(args))) {
            return cursor.moveToFirst() ? cursor.getInt(0) : null;
        }
    }

    private static boolean rowExists(SQLiteDatabase db, String sql, Object[] args) {
        try (Cursor cursor = db.rawQuery(sql, stringArgs(args))) {
            return cursor.moveToFirst();
        }
    }

    private static void insertPending(SQLiteDatabase db, long uid, int mid) {
        insertPending(db, uid, mid, 0);
    }

    private static void insertPending(SQLiteDatabase db, long uid, int mid, long topicId) {
        db.execSQL(PushPlaceholderStore.INSERT_PENDING_SQL,
                new Object[]{uid, mid, 1700000000, topicId, 0, 0});
    }

    /** Binding order of {@code MessagesStorage#bindAndStepPushPlaceholderRow} for messages_v2. */
    private static Object[] messageRowArgs(long uid, int mid, String data) {
        return new Object[]{mid, uid, 0, 0, 1700000000, data, 0, 0, 0, null,
                0, 0, 0, null, 0, 0L, null, null, 0};
    }

    /** Same binding, with topic_id inserted after uid for messages_topics. */
    private static Object[] topicRowArgs(long uid, int mid, String data, long topicId) {
        return new Object[]{mid, uid, topicId, 0, 0, 1700000000, data, 0, 0, 0, null,
                0, 0, 0, null, 0, 0L, null, 0};
    }

    /**
     * Storage-queue order of {@code MessagesStorage#putPushPlaceholder}: canonical inbox_max,
     * then insert-if-absent, then the placeholder + pending rows.
     */
    private static boolean tryWritePlaceholder(SQLiteDatabase db, long uid, int mid) {
        Integer inboxMax = queryInt(db, PushPlaceholderStore.SELECT_INBOX_MAX_SQL, new Object[]{uid});
        if (PushPlaceholderStore.isAlreadyRead(inboxMax == null ? 0 : inboxMax, mid)) {
            return false;
        }
        if (rowExists(db, PushPlaceholderStore.SELECT_MESSAGE_ROW_SQL, new Object[]{uid, mid})) {
            return false;
        }
        db.execSQL(PushPlaceholderStore.REPLACE_MESSAGE_V2_SQL, messageRowArgs(uid, mid, "push preview"));
        db.execSQL(PushPlaceholderStore.INSERT_PENDING_SQL,
                new Object[]{uid, mid, 1700000000, 0L, 0, 0});
        return true;
    }

    @Test
    public void canonicalPeerReadMaxRejectsAlreadyReadPush() throws Exception {
        SQLiteDatabase db = openPushDatabase(folder, "push_readmax.db");
        try {
            // inbox_max >= mid, while the in-memory dialogs_read_inbox_max cache is still 0.
            db.execSQL("INSERT INTO dialogs(did, inbox_max) VALUES(?, ?)", new Object[]{777L, 1001});

            Integer inboxMax = queryInt(db, PushPlaceholderStore.SELECT_INBOX_MAX_SQL, new Object[]{777L});
            assertEquals(Integer.valueOf(1001), inboxMax);
            assertTrue(PushPlaceholderStore.isAlreadyRead(inboxMax, 1001));
            assertTrue(PushPlaceholderStore.isAlreadyRead(inboxMax, 1000));
            assertFalse(PushPlaceholderStore.isAlreadyRead(inboxMax, 1002));

            // The stale in-memory hint (readMax = 0) lets the push through; only the canonical
            // database value rejects it, without writing either row.
            PushChatSyncPolicy.Input hint = new PushChatSyncPolicy.Input();
            hint.dialogId = 777L;
            hint.msgId = 1001;
            hint.readMax = 0;
            assertTrue("the cache is only a hint", PushChatSyncPolicy.isEligible(hint));

            assertFalse("an already read push must be rejected", tryWritePlaceholder(db, 777L, 1001));
            assertFalse("no message row", rowExists(db,
                    PushPlaceholderStore.SELECT_MESSAGE_ROW_SQL, new Object[]{777L, 1001}));
            assertFalse("no durable pending row", rowExists(db,
                    PushPlaceholderStore.SELECT_PENDING_ROW_SQL, new Object[]{777L, 1001}));
        } finally {
            db.close();
        }
    }

    @Test
    public void queuedReadAdvanceRejectsPlaceholder() throws Exception {
        SQLiteDatabase db = openPushDatabase(folder, "push_queued_read.db");
        try {
            db.execSQL("INSERT INTO dialogs(did, inbox_max) VALUES(?, ?)", new Object[]{777L, 0});

            // The read update is posted to the storage queue before the placeholder runnable, so it
            // is applied first and becomes the canonical read max when the placeholder runs.
            db.execSQL(ADVANCE_READ_MAX_SQL, new Object[]{777L, 1001, 777L});
            assertEquals(Integer.valueOf(1001),
                    queryInt(db, PushPlaceholderStore.SELECT_INBOX_MAX_SQL, new Object[]{777L}));

            assertFalse(tryWritePlaceholder(db, 777L, 1001));
            assertFalse(rowExists(db, PushPlaceholderStore.SELECT_MESSAGE_ROW_SQL, new Object[]{777L, 1001}));
            assertFalse(rowExists(db, PushPlaceholderStore.SELECT_PENDING_ROW_SQL, new Object[]{777L, 1001}));
        } finally {
            db.close();
        }
    }

    @Test
    public void missingDialogsRowAllowsUnreadPush() throws Exception {
        SQLiteDatabase db = openPushDatabase(folder, "push_no_dialog.db");
        try {
            assertNull(queryInt(db, PushPlaceholderStore.SELECT_INBOX_MAX_SQL, new Object[]{777L}));
            assertTrue(tryWritePlaceholder(db, 777L, 1001));
            assertTrue(rowExists(db, PushPlaceholderStore.SELECT_MESSAGE_ROW_SQL, new Object[]{777L, 1001}));
            assertTrue(rowExists(db, PushPlaceholderStore.SELECT_PENDING_ROW_SQL, new Object[]{777L, 1001}));
        } finally {
            db.close();
        }
    }

    /** The pending insert fails (missing table): the message row is rolled back with it. */
    @Test
    public void pendingInsertFailureRollsBackPlaceholder() throws Exception {
        File file = folder.newFile("push_pending_failure.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            db.execSQL(CREATE_MESSAGES_V2_SQL);
            // push_pending_messages is intentionally absent -> INSERT throws SQLiteException.

            db.beginTransaction();
            boolean committed = false;
            try {
                db.execSQL(PushPlaceholderStore.REPLACE_MESSAGE_V2_SQL,
                        messageRowArgs(777L, 1001, "push preview"));
                db.execSQL(PushPlaceholderStore.INSERT_PENDING_SQL,
                        new Object[]{777L, 1001, 1700000000, 0L, 0, 0});
                db.setTransactionSuccessful();
                committed = true;
            } catch (Exception expected) {
                // production rolls the transaction back and reports false
            } finally {
                db.endTransaction();
            }

            assertFalse(committed);
            assertFalse("rolled back message row", rowExists(db,
                    PushPlaceholderStore.SELECT_MESSAGE_ROW_SQL, new Object[]{777L, 1001}));
        } finally {
            db.close();
        }
    }

    /**
     * The messages_v2 step succeeds, then the topic row step fails. The whole transaction must roll
     * back, so neither the preview nor its durable pending row survives.
     */
    @Test
    public void failedTopicWriteRollsBackPreviewAndPending() throws Exception {
        SQLiteDatabase db = openPushDatabase(folder, "push_topic_failure.db");
        try {
            db.execSQL("CREATE TRIGGER fail_push_topic BEFORE INSERT ON messages_topics " +
                    "BEGIN SELECT RAISE(ABORT, 'messages_topics write failed'); END");

            db.beginTransaction();
            boolean committed = false;
            try {
                db.execSQL(PushPlaceholderStore.REPLACE_MESSAGE_V2_SQL,
                        messageRowArgs(777L, 1001, "push preview"));
                // Inserted before the failing topic step on purpose: it proves the whole
                // transaction (preview + durable marker) is rolled back, not just the last write.
                insertPending(db, 777L, 1001, 55L);
                // Same production statement as the strict writer's second step.
                db.execSQL(PushPlaceholderStore.REPLACE_MESSAGE_TOPIC_SQL,
                        topicRowArgs(777L, 1001, "push preview", 55L));
                db.setTransactionSuccessful();
                committed = true;
            } catch (Exception expected) {
                // production rolls the placeholder transaction back and reports false
            } finally {
                db.endTransaction();
            }

            assertFalse(committed);
            assertFalse("preview rolled back with the failed topic row", rowExists(db,
                    PushPlaceholderStore.SELECT_MESSAGE_ROW_SQL, new Object[]{777L, 1001}));
            assertFalse("pending rolled back with the failed topic row", rowExists(db,
                    PushPlaceholderStore.SELECT_PENDING_ROW_SQL, new Object[]{777L, 1001}));
            assertEquals("no topic row", Integer.valueOf(0), queryInt(db,
                    "SELECT COUNT(*) FROM messages_topics WHERE uid = 777 AND mid = 1001", new Object[0]));
        } finally {
            db.close();
        }
    }

    /** A failed messages_v2 step must keep the durable pending row for the next real write. */
    @Test
    public void realWriteFailureKeepsPending() throws Exception {
        SQLiteDatabase db = openPushDatabase(folder, "push_real_failure.db");
        try {
            insertPending(db, 777L, 1001);
            db.execSQL("CREATE TRIGGER fail_real_message_write BEFORE INSERT ON messages_v2 " +
                    "BEGIN SELECT RAISE(ABORT, 'messages_v2 write failed'); END");

            db.beginTransaction();
            boolean committed = false;
            try {
                // Production statement with an injected step failure.
                db.execSQL(PushPlaceholderStore.REPLACE_MESSAGE_V2_SQL,
                        messageRowArgs(777L, 1001, "full message text"));
                // Reached only when the step above succeeded.
                db.execSQL(PushPlaceholderStore.DELETE_PENDING_SQL, new Object[]{777L, 1001});
                db.setTransactionSuccessful();
                committed = true;
            } catch (Exception expected) {
                // the pending delete is reached only after the successful step
            } finally {
                db.endTransaction();
            }

            assertFalse(committed);
            assertFalse("message row was not written", rowExists(db,
                    PushPlaceholderStore.SELECT_MESSAGE_ROW_SQL, new Object[]{777L, 1001}));
            assertTrue("pending must survive a failed real write", rowExists(db,
                    PushPlaceholderStore.SELECT_PENDING_ROW_SQL, new Object[]{777L, 1001}));
        } finally {
            db.close();
        }
    }

    /** history/real writes use the same putMessagesInternal path: message first, pending delete after. */
    @Test
    public void historyWriteClearsPending() throws Exception {
        SQLiteDatabase db = openPushDatabase(folder, "push_history_write.db");
        try {
            db.execSQL(PushPlaceholderStore.REPLACE_MESSAGE_V2_SQL,
                    messageRowArgs(777L, 1001, "push preview"));
            insertPending(db, 777L, 1001);

            db.beginTransaction();
            try {
                db.execSQL(PushPlaceholderStore.REPLACE_MESSAGE_V2_SQL,
                        messageRowArgs(777L, 1001, "full message text"));
                // Checked DELETE from the same transaction as the real message row.
                db.execSQL(PushPlaceholderStore.DELETE_PENDING_SQL, new Object[]{777L, 1001});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            assertEquals("one full row", Integer.valueOf(1), queryInt(db,
                    "SELECT COUNT(*) FROM messages_v2 WHERE uid = 777 AND mid = 1001", new Object[0]));
            assertEquals("full message text", queryString(db,
                    "SELECT data FROM messages_v2 WHERE uid = 777 AND mid = 1001", new Object[0]));
            assertEquals("pending cleared by the real write", Integer.valueOf(0), queryInt(db,
                    "SELECT COUNT(*) FROM push_pending_messages WHERE uid = 777 AND mid = 1001", new Object[0]));
        } finally {
            db.close();
        }
    }

    private static String queryString(SQLiteDatabase db, String sql, Object[] args) {
        try (Cursor cursor = db.rawQuery(sql, stringArgs(args))) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }
}
