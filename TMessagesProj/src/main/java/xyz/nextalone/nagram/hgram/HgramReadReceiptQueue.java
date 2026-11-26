package xyz.nextalone.nagram.hgram;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable pending read receipts. Plain text so it can be stored in SharedPreferences and
 * restored after process death; no Android or network types.
 */
public final class HgramReadReceiptQueue {

    public static final class Entry {
        public final long dialogId;
        public final long replyId;
        public final long monoForumPeerId;
        public final int maxId;
        public final int maxDate;

        public Entry(long dialogId, long replyId, long monoForumPeerId, int maxId, int maxDate) {
            this.dialogId = dialogId;
            this.replyId = replyId;
            this.monoForumPeerId = monoForumPeerId;
            this.maxId = maxId;
            this.maxDate = maxDate;
        }
    }

    private static final String TASK_SEPARATOR = ";";
    private static final String FIELD_SEPARATOR = ",";

    private HgramReadReceiptQueue() {
    }

    /** Read receipts are allowed when the user enabled them or a one-shot allowance is active. */
    public static boolean shouldSend(boolean sendReadPackets, boolean oneShotAllow) {
        return sendReadPackets || oneShotAllow;
    }

    /** Replaces an existing entry with the same dialog/thread key and keeps latest ids. */
    public static String upsert(String raw, Entry entry) {
        if (entry == null) return raw == null ? "" : raw;
        final List<Entry> entries = parse(raw);
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            final Entry current = entries.get(i);
            if (current.dialogId == entry.dialogId && current.replyId == entry.replyId) {
                entries.set(i, entry);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(entry);
        }
        return encode(entries);
    }

    /** Removes every entry for the dialog/thread key; safe on malformed or empty input. */
    public static String remove(String raw, long dialogId, long replyId) {
        final List<Entry> entries = parse(raw);
        for (int i = entries.size() - 1; i >= 0; i--) {
            final Entry entry = entries.get(i);
            if (entry.dialogId == dialogId && entry.replyId == replyId) {
                entries.remove(i);
            }
        }
        return encode(entries);
    }

    /**
     * Removes the dialog/thread entry only when it still describes the request that succeeded.
     * A newer read may have replaced it while the request was in flight.
     */
    public static String removeIfMatches(String raw, long dialogId, long replyId, int maxId, int maxDate) {
        final List<Entry> entries = parse(raw);
        boolean removed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            final Entry entry = entries.get(i);
            if (entry.dialogId == dialogId && entry.replyId == replyId
                    && entry.maxId == maxId && entry.maxDate == maxDate) {
                entries.remove(i);
                removed = true;
            }
        }
        return removed ? encode(entries) : (raw == null ? "" : raw);
    }

    /** Delay before a read request is retried; a started request waits longer than a missed one. */
    public static long retryDelayMillis(boolean requestStarted) {
        return requestStarted ? 30000L : 5000L;
    }

    public static List<Entry> parse(String raw) {
        final List<Entry> entries = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return entries;
        }
        final String[] tasks = raw.split(TASK_SEPARATOR);
        for (String task : tasks) {
            if (task.isEmpty()) continue;
            final String[] fields = task.split(FIELD_SEPARATOR);
            if (fields.length != 5) continue;
            try {
                entries.add(new Entry(
                        Long.parseLong(fields[0]),
                        Long.parseLong(fields[1]),
                        Long.parseLong(fields[2]),
                        Integer.parseInt(fields[3]),
                        Integer.parseInt(fields[4])));
            } catch (NumberFormatException ignored) {
                // A partially written preference must never break the read path.
            }
        }
        return entries;
    }

    public static String encode(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (entry == null) continue;
            if (out.length() > 0) {
                out.append(TASK_SEPARATOR);
            }
            out.append(entry.dialogId).append(FIELD_SEPARATOR)
                    .append(entry.replyId).append(FIELD_SEPARATOR)
                    .append(entry.monoForumPeerId).append(FIELD_SEPARATOR)
                    .append(entry.maxId).append(FIELD_SEPARATOR)
                    .append(entry.maxDate);
        }
        return out.toString();
    }
}
