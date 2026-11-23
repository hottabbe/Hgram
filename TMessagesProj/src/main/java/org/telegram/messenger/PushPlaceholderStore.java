package org.telegram.messenger;

/**
 * Pure insert-if-absent decision for push placeholders plus the SQL contract of the
 * {@code push_pending_messages} table.
 *
 * The decision is deliberately separated from storage: {@link #insertIfAbsent} only asks the
 * supplied {@link MessageExistence} seam whether a row already exists. A full message row must
 * never be overwritten by a short push preview, so a positive answer skips the insert runnable
 * completely.
 */
public final class PushPlaceholderStore {

    /** Matches {@code org.telegram.ui.ChatActivity#MODE_DEFAULT} without depending on UI classes. */
    static final int MODE_DEFAULT = 0;

    static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS push_pending_messages(" +
                    "uid INTEGER, mid INTEGER, date INTEGER, topic_id INTEGER, " +
                    "state INTEGER, payload_hash INTEGER, PRIMARY KEY(uid, mid))";
    static final String CREATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS push_pending_messages_date_idx " +
                    "ON push_pending_messages(date)";

    /**
     * Applied by {@code DatabaseMigrationHelper} for version 179 -> 180 and by
     * {@code PushPendingStoreTest}. Every statement is idempotent; no DROP is allowed.
     */
    static final String[] MIGRATION_179_TO_180_SQL = new String[]{
            CREATE_TABLE_SQL,
            CREATE_INDEX_SQL,
            "PRAGMA user_version = 180"
    };

    static final String INSERT_PENDING_SQL =
            "INSERT OR REPLACE INTO push_pending_messages" +
                    "(uid, mid, date, topic_id, state, payload_hash) VALUES(?, ?, ?, ?, ?, ?)";
    static final String DELETE_PENDING_SQL =
            "DELETE FROM push_pending_messages WHERE uid = ? AND mid = ?";
    static final String SELECT_PENDING_SQL =
            "SELECT uid, mid FROM push_pending_messages WHERE state = 0";

    /** Exact production statements shared by the strict writer and the storage tests. */
    static final String REPLACE_MESSAGE_V2_SQL =
            "REPLACE INTO messages_v2 VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)";
    static final String REPLACE_MESSAGE_TOPIC_SQL =
            "REPLACE INTO messages_topics VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";

    /**
     * Canonical read barrier: {@code dialogs.inbox_max} is maintained by the storage-queue read
     * updates, so this is the authoritative "already read" value at insert time. Deliberately
     * not clamped by {@code last_mid}: a dialog may have an inbox_max above its last message.
     */
    static final String SELECT_INBOX_MAX_SQL = "SELECT inbox_max FROM dialogs WHERE did = ?";
    static final String SELECT_MESSAGE_ROW_SQL =
            "SELECT mid FROM messages_v2 WHERE uid = ? AND mid = ?";
    static final String SELECT_PENDING_ROW_SQL =
            "SELECT mid FROM push_pending_messages WHERE uid = ? AND mid = ?";

    public interface MessageExistence {
        boolean exists(long uid, int mid);
    }

    /**
     * Storage seam for one atomic placeholder write. Every method runs on the storage queue;
     * the insert methods must throw instead of swallowing a failure so the caller can roll back.
     * {@code throws Exception} keeps the seam independent of {@code org.telegram.SQLite}, whose
     * exceptions are checked.
     */
    public interface PlaceholderTransaction {
        void beginTransaction() throws Exception;

        /** Writes the placeholder into {@code messages_v2} (and {@code messages_topics}). */
        void insertMessageRow() throws Exception;

        /** Writes the durable row into {@code push_pending_messages}. */
        void insertPendingRow() throws Exception;

        boolean messageRowExists() throws Exception;

        boolean pendingRowExists() throws Exception;

        void commitTransaction() throws Exception;

        void rollbackTransaction() throws Exception;

        /** Runs on the storage queue strictly after a successful commit (in-memory registry mark). */
        void onCommitted();
    }

    private PushPlaceholderStore() {
    }

    /**
     * Runs {@code insert} only when {@code (uid, mid)} is absent.
     *
     * @return true when {@code insert} ran, false when the existing row was kept untouched.
     */
    public static boolean insertIfAbsent(MessageExistence existence, long uid, int mid, Runnable insert) {
        if (existence.exists(uid, mid)) {
            return false;
        }
        insert.run();
        return true;
    }

    /**
     * Canonical inbox_max verdict: a push at or below the stored read max is already read, so no
     * placeholder row (neither message nor pending) may be written for it.
     */
    public static boolean isAlreadyRead(int inboxMax, int mid) {
        return mid > 0 && mid <= inboxMax;
    }

    /**
     * Writes the placeholder and its durable pending row in one transaction.
     *
     * The message row and the pending row are inserted under the caller's transaction, then both
     * are verified with a postcondition SELECT. Only a verified pair is committed, and only after
     * the commit is {@link PlaceholderTransaction#onCommitted()} called. Any exception or a failed
     * postcondition rolls the whole transaction back and returns false: nothing is published and
     * the in-memory registry is never marked.
     *
     * @return true when both rows exist and were committed.
     */
    public static boolean storePlaceholder(PlaceholderTransaction transaction) {
        boolean began = false;
        boolean committed = false;
        try {
            transaction.beginTransaction();
            began = true;
            transaction.insertMessageRow();
            transaction.insertPendingRow();
            if (!transaction.messageRowExists() || !transaction.pendingRowExists()) {
                return false;
            }
            transaction.commitTransaction();
            committed = true;
        } catch (Exception e) {
            return false;
        } finally {
            // A failed BEGIN owns no transaction, so rollback only after it succeeded. The rollback
            // itself never commits: a failed preview must not publish or mark anything.
            if (began && !committed) {
                rollbackQuietly(transaction);
            }
        }
        transaction.onCommitted();
        return true;
    }

    private static void rollbackQuietly(PlaceholderTransaction transaction) {
        try {
            transaction.rollbackTransaction();
        } catch (Exception ignore) {
            // The original failure is what matters; the callback is already reporting false.
        }
    }

    /**
     * Pending placeholders are cleared only by a real normal-message write, never by
     * scheduled / quick-replies / welcome / saved-messages modes.
     */
    public static boolean isRealMessageWrite(int mode, boolean scheduled, boolean saved,
                                             boolean quickReplies, boolean welcomeMessages) {
        return mode == MODE_DEFAULT && !scheduled && !saved && !quickReplies && !welcomeMessages;
    }

    /** Deterministic hash of the short preview text and date, written for the stage-3 sweep. */
    public static int payloadHash(String text, int date) {
        int textHash = text == null ? 0 : text.hashCode();
        return 31 * textHash + date;
    }
}
