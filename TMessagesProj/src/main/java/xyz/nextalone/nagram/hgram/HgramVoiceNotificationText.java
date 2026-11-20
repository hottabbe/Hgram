package xyz.nextalone.nagram.hgram;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure text helpers and cache for transcription shown in notification BigText.
 * No Android dependencies so the rules stay unit-testable.
 */
public final class HgramVoiceNotificationText {

    /** Upper bound for BigText shown in the notification. */
    public static final int BIG_TEXT_LIMIT = 900;
    private static final int MAX_CACHED_TEXTS = 32;
    private static final String ELLIPSIS = "…";

    private HgramVoiceNotificationText() {
    }

    /** Identity of one voice message notification. */
    public static final class Key {
        public final int account;
        public final long dialogId;
        public final int messageId;

        public Key(int account, long dialogId, int messageId) {
            this.account = account;
            this.dialogId = dialogId;
            this.messageId = messageId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return account == other.account
                    && dialogId == other.dialogId
                    && messageId == other.messageId;
        }

        @Override
        public int hashCode() {
            int result = account;
            result = 31 * result + (int) (dialogId ^ (dialogId >>> 32));
            result = 31 * result + messageId;
            return result;
        }
    }

    public static Key key(int account, long dialogId, int messageId) {
        return new Key(account, dialogId, messageId);
    }

    /** Transcription may be shown only when the chat is visible, not anti-peek and not encrypted. */
    public static boolean canShowText(boolean hidden, boolean antiPeek, boolean encrypted) {
        return !hidden && !antiPeek && !encrypted;
    }

    /** Backwards-compatible overload for callers without an encryption check. */
    public static boolean canShowText(boolean hidden, boolean antiPeek) {
        return canShowText(hidden, antiPeek, false);
    }

    /** Trims, collapses null/empty to "", and cuts to {@code limit} chars with an ellipsis. */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (limit <= 0) {
            return "";
        }
        if (trimmed.isEmpty() || trimmed.length() <= limit) {
            return trimmed;
        }
        int cut = Math.min(limit - 1, trimmed.length());
        // Never leave half of a surrogate pair at the cut point.
        if (cut > 0 && Character.isHighSurrogate(trimmed.charAt(cut - 1))) {
            cut--;
        }
        return trimmed.substring(0, cut) + ELLIPSIS;
    }

    /** Joins base text and transcription with a blank line, then applies {@link #truncate}. */
    public static String formatBigText(String base, String transcription, int limit) {
        String first = base == null ? "" : base.trim();
        String second = transcription == null ? "" : transcription.trim();
        String combined;
        if (first.isEmpty()) {
            combined = second;
        } else if (second.isEmpty()) {
            combined = first;
        } else {
            combined = first + "\n\n" + second;
        }
        return truncate(combined, limit);
    }

    /** Access-order LRU capped at {@value #MAX_CACHED_TEXTS} entries. */
    private static final Map<Key, String> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<Key, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, String> eldest) {
                    return size() > MAX_CACHED_TEXTS;
                }
            });

    public static String cached(Key key) {
        if (key == null) {
            return null;
        }
        return CACHE.get(key);
    }

    public static void cache(Key key, String text) {
        if (key == null) {
            return;
        }
        if (text == null || text.isEmpty()) {
            CACHE.remove(key);
            return;
        }
        CACHE.put(key, text);
    }

    public static void clearCacheForTests() {
        CACHE.clear();
    }
}
