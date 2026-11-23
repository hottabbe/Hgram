package org.telegram.messenger;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PUSH-CHAT-2 restart coverage for the push placeholder index.
 *
 * <p>A full UI path through {@code MessagesStorage} is unreachable in a unit test: the production
 * class uses the native {@code org.telegram.SQLite} wrapper and cannot be constructed here. These
 * tests therefore mirror the two production storage runnables on a temporary Robolectric SQLite
 * database using the production SQL constants:
 * <ul>
 *   <li>{@code MessagesStorage.loadPushPending()} — {@link PushPlaceholderStore#SELECT_PENDING_SQL}
 *   followed by {@link PushPlaceholderRegistry#mark};</li>
 *   <li>the real-message write — {@link PushPlaceholderStore#DELETE_PENDING_SQL} plus
 *   {@link PushPlaceholderRegistry#clear} in one transaction/runnable.</li>
 * </ul>
 * The registry itself is the real production object, so the one-shot replacement contract is
 * exercised end to end. PUSH-CHAT-3 additionally drives
 * {@link MessagesController#publishPushPlaceholderReplacements} with the real
 * {@link NotificationCenter}, so the authoritative-history packet and the delayed push callback
 * gate are covered as production code paths rather than mirrored SQL.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34,
        shadows = {PushChatSyncRestartTest.NoJni.class,
                PushChatSyncRestartTest.NoHeavyMessagesController.class})
public class PushChatSyncRestartTest {

    /** MessageObject/Theme must not load the native connection singleton in a unit test. */
    @org.robolectric.annotation.Implements(org.telegram.tgnet.ConnectionsManager.class)
    public static class NoJni {
        @org.robolectric.annotation.Implementation
        protected static int native_isTestBackend(int instance) {
            return 0;
        }
    }

    /** Theme only needs the global preferences; the real controller would open storage. */
    @org.robolectric.annotation.Implements(
            value = MessagesController.class, isInAndroidSdk = false)
    public static class NoHeavyMessagesController {
        @org.robolectric.annotation.Implementation
        protected void __constructor__(int account) {
        }

        @org.robolectric.annotation.Implementation
        protected static android.content.SharedPreferences getGlobalMainSettings() {
            return ApplicationLoader.applicationContext.getSharedPreferences(
                    "mainconfig", android.content.Context.MODE_PRIVATE);
        }
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
    }

    private static SQLiteDatabase openDatabase(File file) {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL("CREATE TABLE messages_v2(uid INTEGER, mid INTEGER, data TEXT, PRIMARY KEY(uid, mid))");
        for (String sql : PushPlaceholderStore.MIGRATION_179_TO_180_SQL) {
            db.execSQL(sql);
        }
        return db;
    }

    private static void insertPreview(SQLiteDatabase db, long dialogId, int mid, String text) {
        db.execSQL("INSERT INTO messages_v2(uid, mid, data) VALUES(?, ?, ?)",
                new Object[]{dialogId, mid, text});
        db.execSQL(PushPlaceholderStore.INSERT_PENDING_SQL,
                new Object[]{dialogId, mid, 123456789, 5L, 0, 42});
    }

    /** Mirrors {@code MessagesStorage.loadPushPending()} on the storage queue. */
    private static void restorePending(SQLiteDatabase db, PushPlaceholderRegistry registry) {
        registry.clear();
        try (Cursor cursor = db.rawQuery(PushPlaceholderStore.SELECT_PENDING_SQL, null)) {
            while (cursor.moveToNext()) {
                registry.mark(cursor.getLong(0), cursor.getInt(1));
            }
        }
    }

    /** Mirrors the real-message write runnable: row delete and durable marker clear together. */
    private static void writeRealMessage(SQLiteDatabase db, PushPlaceholderRegistry registry,
                                         long dialogId, int mid, String text) {
        db.beginTransaction();
        try {
            db.execSQL(PushPlaceholderStore.DELETE_PENDING_SQL, new Object[]{dialogId, mid});
            db.execSQL("REPLACE INTO messages_v2(uid, mid, data) VALUES(?, ?, ?)",
                    new Object[]{dialogId, mid, text});
            registry.clear(dialogId, mid);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static int countPending(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM push_pending_messages", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        }
    }

    private static String messageText(SQLiteDatabase db, long dialogId, int mid) {
        try (Cursor cursor = db.rawQuery(
                "SELECT data FROM messages_v2 WHERE uid = ? AND mid = ?",
                new String[]{String.valueOf(dialogId), String.valueOf(mid)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    /** Real event observer: collects replaceMessagesObjects packets posted on the UI thread. */
    private static final class ReplacementCapture implements NotificationCenter.NotificationCenterDelegate {
        final ArrayList<Object[]> events = new ArrayList<>();

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.replaceMessagesObjects) {
                events.add(args);
            }
        }
    }

    /** Builds the production MessageObject shape for a server history row (normal or forum topic). */
    private static MessageObject serverMessage(long dialogId, int mid, String text, boolean topic) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = mid;
        message.dialog_id = dialogId;
        message.date = 1700000123;
        message.message = text;
        if (dialogId < 0) {
            TLRPC.TL_peerChannel channel = new TLRPC.TL_peerChannel();
            channel.channel_id = -dialogId;
            message.peer_id = channel;
        } else {
            TLRPC.TL_peerUser user = new TLRPC.TL_peerUser();
            user.user_id = dialogId;
            message.peer_id = user;
        }
        if (topic) {
            TLRPC.TL_messageReplyHeader reply = new TLRPC.TL_messageReplyHeader();
            reply.forum_topic = true;
            reply.reply_to_top_id = 55;
            reply.reply_to_msg_id = mid;
            message.reply_to = reply;
            message.flags |= TLRPC.MESSAGE_FLAG_REPLY;
        }
        return new MessageObject(0, message, false, false);
    }

    private static ArrayList<MessageObject> one(MessageObject object) {
        ArrayList<MessageObject> result = new ArrayList<>(1);
        result.add(object);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<MessageObject> replacedObjects(Object[] event) {
        return (ArrayList<MessageObject>) event[1];
    }

    @Test
    public void restoredPlaceholderIsReplacedInOpenChat() throws Exception {
        File file = folder.newFile("push_restart.db");
        SQLiteDatabase db = openDatabase(file);
        NotificationCenter center = NotificationCenter.getInstance(0);
        ReplacementCapture capture = new ReplacementCapture();
        center.addObserver(capture, NotificationCenter.replaceMessagesObjects);
        try {
            insertPreview(db, 777L, 1001, "push preview");
            insertPreview(db, -1001234567890L, 2002, "topic preview");

            // Cold start: the durable index is restored into the shared registry.
            PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
            restorePending(db, registry);
            assertTrue(registry.hasPending(777L, 1001));
            assertTrue(registry.hasPending(-1001234567890L, 2002));
            assertEquals("push preview", messageText(db, 777L, 1001));

            // Normal chat: the overlapping server history is published as exactly one packet with
            // one real object, replacing the restored preview in place.
            MessageObject normal = serverMessage(777L, 1001, "full message text", false);
            assertTrue("restored preview must be replaced by the server copy",
                    MessagesController.publishPushPlaceholderReplacements(
                            center, registry, 777L, ChatActivity.MODE_DEFAULT, false, one(normal)));
            assertEquals("one replacement packet", 1, capture.events.size());
            assertEquals(Long.valueOf(777L), capture.events.get(0)[0]);
            ArrayList<MessageObject> normalReplaced = replacedObjects(capture.events.get(0));
            assertEquals("one element in the packet", 1, normalReplaced.size());
            assertSame("the real object replaces the preview", normal, normalReplaced.get(0));
            assertEquals("full message text", normalReplaced.get(0).messageText.toString());
            assertFalse("the one-shot right is spent", registry.hasReplacement(777L, 1001));
            assertTrue("durable pending waits for the storage commit", registry.hasPending(777L, 1001));

            // Forum topic: same contract with a topic real object.
            capture.events.clear();
            MessageObject topic = serverMessage(-1001234567890L, 2002, "topic full text", true);
            assertTrue(MessagesController.publishPushPlaceholderReplacements(
                    center, registry, -1001234567890L, ChatActivity.MODE_DEFAULT, false, one(topic)));
            assertEquals("one replacement packet in the topic", 1, capture.events.size());
            ArrayList<MessageObject> topicReplaced = replacedObjects(capture.events.get(0));
            assertEquals(1, topicReplaced.size());
            assertSame(topic, topicReplaced.get(0));
            assertFalse(registry.hasReplacement(-1001234567890L, 2002));

            // The real storage write removes the durable rows; a repeated history load cannot
            // replace the same preview a second time.
            writeRealMessage(db, registry, 777L, 1001, "full message text");
            writeRealMessage(db, registry, -1001234567890L, 2002, "topic full text");
            capture.events.clear();
            assertFalse(MessagesController.publishPushPlaceholderReplacements(
                    center, registry, 777L, ChatActivity.MODE_DEFAULT, false, one(normal)));

            assertEquals(0, countPending(db));
            assertFalse(registry.hasPending(777L, 1001));
            assertFalse(registry.hasAny());
            assertFalse("the right was already used", registry.consume(777L, 1001));
            assertEquals("full message text", messageText(db, 777L, 1001));
        } finally {
            center.removeObserver(capture, NotificationCenter.replaceMessagesObjects);
            db.close();
        }
    }

    /**
     * PUSH-CHAT-3: only a non-cache normal history load is authoritative for a preview. A cache
     * load and non-default chat modes keep the preview and the one-shot right untouched.
     */
    @Test
    public void restoredPreviewIsReplacedByOverlappingServerHistory() throws Exception {
        File file = folder.newFile("push_restart_history.db");
        SQLiteDatabase db = openDatabase(file);
        NotificationCenter center = NotificationCenter.getInstance(0);
        ReplacementCapture capture = new ReplacementCapture();
        center.addObserver(capture, NotificationCenter.replaceMessagesObjects);
        try {
            insertPreview(db, 888L, 3003, "push preview");
            PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
            restorePending(db, registry);
            MessageObject real = serverMessage(888L, 3003, "server text", false);

            assertFalse("cache history must not replace",
                    MessagesController.publishPushPlaceholderReplacements(
                            center, registry, 888L, ChatActivity.MODE_DEFAULT, true, one(real)));
            assertFalse("scheduled history must not touch a push preview",
                    MessagesController.publishPushPlaceholderReplacements(
                            center, registry, 888L, ChatActivity.MODE_SCHEDULED, false, one(real)));
            assertEquals(0, capture.events.size());
            assertTrue("the one-shot right survives a skipped load", registry.hasReplacement(888L, 3003));

            assertTrue("non-cache normal history replaces the preview",
                    MessagesController.publishPushPlaceholderReplacements(
                            center, registry, 888L, ChatActivity.MODE_DEFAULT, false, one(real)));
            assertEquals(1, capture.events.size());
            assertSame(real, replacedObjects(capture.events.get(0)).get(0));
            assertFalse(registry.hasReplacement(888L, 3003));

            // The history write also clears the durable row; after that nothing is left to replace.
            writeRealMessage(db, registry, 888L, 3003, "server text");
            assertFalse(registry.hasPending(888L, 3003));
            capture.events.clear();
            assertFalse("nothing to replace on the next load",
                    MessagesController.publishPushPlaceholderReplacements(
                            center, registry, 888L, ChatActivity.MODE_DEFAULT, false, one(real)));
            assertEquals(0, capture.events.size());
        } finally {
            center.removeObserver(capture, NotificationCenter.replaceMessagesObjects);
            db.close();
        }
    }

    /**
     * PUSH-CHAT-3: a delayed push UI callback that runs after the real storage commit must neither
     * publish the preview nor re-mark it. The durable pending marker is owned by storage only.
     */
    @Test
    public void delayedPushCallbackCannotRemarkAfterRealCommit() throws Exception {
        File file = folder.newFile("push_restart_delayed_callback.db");
        SQLiteDatabase db = openDatabase(file);
        try {
            insertPreview(db, 777L, 1001, "push preview");
            PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
            restorePending(db, registry);
            assertTrue(registry.hasPending(777L, 1001));

            // Difference/update committed the real row before the push callback reached the UI.
            writeRealMessage(db, registry, 777L, 1001, "full message text");
            assertFalse(registry.hasPending(777L, 1001));
            assertFalse("delayed callback must not publish after the real commit",
                    PushChatSync.shouldPublishPlaceholder(registry, 777L, 1001, false));
            assertFalse("delayed callback must not re-mark the durable pending",
                    registry.hasAny());
            assertFalse(registry.hasPending(777L, 1001));

            // The one-shot right still lets the update/difference replace the bubble once.
            assertTrue(registry.hasReplacement(777L, 1001));
            assertTrue(registry.consume(777L, 1001));

            // A real copy already visible in the UI also blocks a still-pending callback.
            registry.mark(999L, 4004);
            assertFalse(PushChatSync.shouldPublishPlaceholder(registry, 999L, 4004, true));
            assertTrue(PushChatSync.shouldPublishPlaceholder(registry, 999L, 4004, false));
        } finally {
            db.close();
        }
    }

    @Test
    public void realWriteBeforeUiUpdate_stillReplacesOnce() throws Exception {
        File file = folder.newFile("push_restart_storage_first.db");
        SQLiteDatabase db = openDatabase(file);
        try {
            insertPreview(db, 777L, 1001, "push preview");

            PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
            restorePending(db, registry);
            assertTrue(registry.hasPending(777L, 1001));

            // Difference/update wins the race: storage writes the real row before the UI callback.
            writeRealMessage(db, registry, 777L, 1001, "full message text");

            assertEquals(0, countPending(db));
            assertFalse("durable confirmation is done", registry.hasPending(777L, 1001));
            assertFalse("no difference refresh is needed", registry.hasAny());
            assertTrue("the UI still replaces the preview once", registry.consume(777L, 1001));
            assertFalse(registry.consume(777L, 1001));
            assertEquals("full message text", messageText(db, 777L, 1001));
        } finally {
            db.close();
        }
    }

    @Test
    public void duplicatePushAndCallbackRace_consumeOnce() throws Exception {
        File file = folder.newFile("push_restart_duplicate.db");
        SQLiteDatabase db = openDatabase(file);
        try {
            PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
            insertPreview(db, 777L, 1001, "push preview");
            registry.mark(777L, 1001);

            // A second push for the same (dialogId, mid) must not publish or re-mark.
            boolean duplicateInserted = PushPlaceholderStore.insertIfAbsent(
                    (uid, mid) -> {
                        try (Cursor cursor = db.rawQuery(
                                "SELECT mid FROM messages_v2 WHERE uid = ? AND mid = ?",
                                new String[]{String.valueOf(uid), String.valueOf(mid)})) {
                            return cursor.moveToFirst();
                        }
                    },
                    777L, 1001, () -> fail("duplicate push must not insert a preview"));
            assertFalse(duplicateInserted);
            assertEquals(1, countPending(db));

            // Two consumers (UI update and a duplicate callback) race for one replacement.
            assertTrue(registry.consume(777L, 1001));
            assertFalse(registry.consume(777L, 1001));
            assertFalse(registry.consume(777L, 1001));

            writeRealMessage(db, registry, 777L, 1001, "full message text");
            assertEquals(0, countPending(db));
            assertFalse(registry.consume(777L, 1001));
        } finally {
            db.close();
        }
    }
}
