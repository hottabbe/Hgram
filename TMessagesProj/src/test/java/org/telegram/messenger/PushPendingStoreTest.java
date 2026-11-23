package org.telegram.messenger;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Mirrors the {@code push_pending_messages} contract on a plain Robolectric SQLite database.
 * The real {@code MessagesStorage} needs the native loader and is not constructible in a unit test,
 * so production SQL constants and production predicates are applied to a temporary database.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PushPendingStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static void applyMigration179to180(SQLiteDatabase db) {
        for (String sql : PushPlaceholderStore.MIGRATION_179_TO_180_SQL) {
            db.execSQL(sql);
        }
    }

    private static int userVersion(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA user_version", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        }
    }

    private static boolean schemaObjectExists(SQLiteDatabase db, String type, String name) {
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = ? AND name = ?",
                new String[]{type, name})) {
            return cursor.moveToFirst();
        }
    }

    private static int countRows(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        }
    }

    private static void insertPending(SQLiteDatabase db, long uid, int mid) {
        db.execSQL(PushPlaceholderStore.INSERT_PENDING_SQL,
                new Object[]{uid, mid, 123456789, 5L, 0, 42});
    }

    @Test
    public void migration179to180_createsTable() throws Exception {
        File file = folder.newFile("push_migration.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            db.execSQL("PRAGMA user_version = 179");
            assertEquals(179, userVersion(db));

            applyMigration179to180(db);

            assertEquals(180, MessagesStorage.LAST_DB_VERSION);
            assertEquals(180, userVersion(db));
            assertTrue("table", schemaObjectExists(db, "table", "push_pending_messages"));
            assertTrue("index", schemaObjectExists(db, "index", "push_pending_messages_date_idx"));

            ContentValues first = new ContentValues();
            first.put("uid", 777L);
            first.put("mid", 1001);
            db.insertOrThrow("push_pending_messages", null, first);
            try {
                db.insertOrThrow("push_pending_messages", null, first);
                fail("PRIMARY KEY (uid, mid) must reject a duplicate");
            } catch (SQLiteConstraintException expected) {
                // expected
            }

            // The migration is additive and repeatable: no DROP, no data loss.
            applyMigration179to180(db);
            assertEquals(180, userVersion(db));
            assertEquals(1, countRows(db, "push_pending_messages"));
        } finally {
            db.close();
        }
    }

    @Test
    public void pendingSurvivesReopen() throws Exception {
        File file = folder.newFile("push_pending.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL("CREATE TABLE messages_v2(uid INTEGER, mid INTEGER, data TEXT, PRIMARY KEY(uid, mid))");
        applyMigration179to180(db);
        db.execSQL("INSERT INTO messages_v2(uid, mid, data) VALUES(?, ?, ?)",
                new Object[]{777L, 1001, "push preview"});
        insertPending(db, 777L, 1001);
        db.close();

        db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            try (Cursor cursor = db.rawQuery(
                    "SELECT uid, mid, date, topic_id, state, payload_hash FROM push_pending_messages",
                    null)) {
                assertTrue("pending row must survive reopen", cursor.moveToFirst());
                assertEquals(777L, cursor.getLong(0));
                assertEquals(1001, cursor.getInt(1));
                assertEquals(123456789, cursor.getInt(2));
                assertEquals(5L, cursor.getLong(3));
                assertEquals(0, cursor.getInt(4));
                assertEquals(42, cursor.getInt(5));
                assertFalse(cursor.moveToNext());
            }
            try (Cursor cursor = db.rawQuery(
                    "SELECT data FROM messages_v2 WHERE uid = 777 AND mid = 1001", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("push preview", cursor.getString(0));
            }
        } finally {
            db.close();
        }
    }

    @Test
    public void realMessageClearsPending() throws Exception {
        File file = folder.newFile("push_real_message.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            db.execSQL("CREATE TABLE messages_v2(uid INTEGER, mid INTEGER, data TEXT, PRIMARY KEY(uid, mid))");
            applyMigration179to180(db);
            db.execSQL("INSERT INTO messages_v2(uid, mid, data) VALUES(?, ?, ?)",
                    new Object[]{777L, 1001, "push preview"});
            insertPending(db, 777L, 1001);

            PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
            registry.mark(777L, 1001);

            assertTrue("a normal message write clears the pending marker",
                    PushPlaceholderStore.isRealMessageWrite(0, false, false, false, false));

            // Storage-side path: delete the durable pending row and REPLACE the preview with the
            // full row, then acknowledge the durable marker in the same storage runnable. The
            // one-shot UI replacement right must survive for the later UI consume.
            db.execSQL(PushPlaceholderStore.DELETE_PENDING_SQL, new Object[]{777L, 1001});
            db.execSQL("REPLACE INTO messages_v2(uid, mid, data) VALUES(?, ?, ?)",
                    new Object[]{777L, 1001, "full message text"});
            registry.clear(777L, 1001);

            assertEquals(0, countRows(db, "push_pending_messages"));
            assertFalse("the durable marker is cleared with its row",
                    registry.hasPending(777L, 1001));
            assertTrue("the UI path still consumes the marker once",
                    registry.consume(777L, 1001));
            assertFalse(registry.consume(777L, 1001));
            assertEquals(1, countRows(db, "messages_v2"));
            try (Cursor cursor = db.rawQuery(
                    "SELECT data FROM messages_v2 WHERE uid = 777 AND mid = 1001", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals("full message text", cursor.getString(0));
            }

            // Scheduled / saved / quick-replies / welcome writes must keep the marker.
            insertPending(db, 777L, 1002);
            assertFalse(PushPlaceholderStore.isRealMessageWrite(0, true, false, false, false));
            assertFalse(PushPlaceholderStore.isRealMessageWrite(0, false, true, false, false));
            assertFalse(PushPlaceholderStore.isRealMessageWrite(0, false, false, true, false));
            assertFalse(PushPlaceholderStore.isRealMessageWrite(0, false, false, false, true));
            assertEquals(1, countRows(db, "push_pending_messages"));
        } finally {
            db.close();
        }
    }
}
