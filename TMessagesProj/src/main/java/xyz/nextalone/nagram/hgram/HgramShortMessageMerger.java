package xyz.nextalone.nagram.hgram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.telegram.tgnet.TLRPC;

/** Presentation-only groups. Message IDs, owners and storage are never modified. */
public final class HgramShortMessageMerger {
    public static final int DEFAULT_MAX_CHARS = 160;
    public static final int DEFAULT_MAX_WORDS = 6;
    public static final int DEFAULT_MAX_WINDOW_SECONDS = 600;
    public static final int DEFAULT_MAX_CHAIN_SIZE = 12;
    public static final int COLLAPSE_ROW_HEIGHT_DP = 24;

    private HgramShortMessageMerger() { }
    public static final class Entry {
        public final int id, date, day;
        public final long sender;
        public final String text;
        public final boolean eligible;
        public final boolean outgoing, read;
        public Entry(int id, int date, int day, long sender, String text, boolean eligible) {
            this(id, date, day, sender, text, eligible, false, true);
        }
        public Entry(int id, int date, int day, long sender, String text, boolean eligible,
                     boolean outgoing, boolean read) {
            this.id = id; this.date = date; this.day = day; this.sender = sender;
            this.text = text; this.eligible = eligible; this.outgoing = outgoing; this.read = read;
        }
    }
    public static final class Chain {
        public final int anchorId, key;
        public final List<Integer> ids;
        public final String text;
        public final int outgoingCount, readCount;
        private Chain(List<Entry> entries) {            anchorId = entries.get(0).id;
            key = entries.get(entries.size() - 1).id;
            ids = new ArrayList<>();
            StringBuilder joined = new StringBuilder();
            int outgoing = 0, read = 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry entry = entries.get(i);
                ids.add(entry.id);
                if (entry.outgoing) {
                    outgoing++;
                    if (entry.read) read++;
                }
                if (joined.length() > 0) joined.append('\n');
                joined.append(entry.text.trim());
            }
            text = joined.toString();
            outgoingCount = outgoing;
            readCount = read;
        }
        private Chain(Chain source, int outgoing, int read) {
            anchorId = source.anchorId;
            key = source.key;
            ids = source.ids;
            text = source.text;
            outgoingCount = outgoing;
            readCount = read;
        }
        public int readState() {
            if (outgoingCount <= 0) return 0;
            if (readCount <= 0) return 1;
            return readCount >= outgoingCount ? 3 : 2;
        }
    }

    /**
     * Replaces only the read counters of an immutable snapshot. Key, anchor, ids and text are
     * kept verbatim so a read receipt cannot regroup the conversation while the selection
     * mode is ending.
     */
    public static Chain withReadCounts(Chain source, int outgoingCount, int readCount) {
        if (source == null) return null;
        if (source.outgoingCount == outgoingCount && source.readCount == readCount) return source;
        return new Chain(source, outgoingCount, readCount);
    }

    /** Read updates must not rebuild/reapply chains while the user is selecting messages. */
    public static boolean shouldRefreshChainsForRead(boolean selectionMode) {
        return !selectionMode;
    }

    /** Shift a right-aligned paragraph left so its right edge stays inside the content column. */
    public static float paragraphShift(float layoutWidth, float contentWidth, boolean rtl) {
        if (!rtl || layoutWidth <= contentWidth) return 0f;
        return contentWidth - layoutWidth;
    }

    public static float clampLeft(float left, float min, float max) {
        return Math.max(min, Math.min(max, left));
    }

    public static int normalCellHeight(int textHeight, int namesOffset, int footerHeight, int reactionsHeight) {
        return normalCellHeight(textHeight, namesOffset, footerHeight, reactionsHeight, 0);
    }

    public static int normalCellHeight(int textHeight, int namesOffset, int footerHeight,
                                       int reactionsHeight, int timeRowHeight) {
        return Math.max(1, textHeight + namesOffset + footerHeight
                + Math.max(0, reactionsHeight) + Math.max(0, timeRowHeight));
    }

    public static boolean isInToggleZone(float x, float y,
                                         float left, float top, float right, float bottom) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    public static boolean isShort(String text) {
        return isShort(text, DEFAULT_MAX_CHARS, DEFAULT_MAX_WORDS);
    }

    public static boolean isShort(String text, int maxChars, int maxWords) {
        if (text == null || text.length() > maxChars) return false;
        int start = 0;
        int end = text.length();
        // String.trim() removes control characters up to U+0020 at both ends.
        while (start < end && text.charAt(start) <= ' ') start++;
        while (end > start && text.charAt(end - 1) <= ' ') end--;
        if (start == end) return false;
        int words = 0;
        boolean inWord = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            // Match the default Java regex \s character class used previously.
            boolean whitespace = c == ' ' || c >= '\t' && c <= '\r';
            if (whitespace) {
                inWord = false;
            } else if (!inWord) {
                if (++words > maxWords) return false;
                inWord = true;
            }
        }
        return true;
    }

    public static boolean canMergeText(TLRPC.Message message, boolean actuallyHidden) {
        return canMergeText(message, actuallyHidden, DEFAULT_MAX_CHARS, DEFAULT_MAX_WORDS);
    }

    public static boolean canMergeText(TLRPC.Message message, boolean actuallyHidden,
                                       int maxChars, int maxWords) {
        if (message == null || actuallyHidden || message.hide || message.fwd_from != null || message.reply_to != null
                || message.reply_markup != null || message.grouped_id != 0
                || message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)) return false;
        if (message.reactions != null && message.reactions.results != null && !message.reactions.results.isEmpty()) return false;
        if (message.entities != null) {
            for (TLRPC.MessageEntity entity : message.entities) {
                // Formatting is safe to flatten in the summary. Never reveal spoilers or hide link actions.
                if (!(entity instanceof TLRPC.TL_messageEntityBold) && !(entity instanceof TLRPC.TL_messageEntityItalic)
                        && !(entity instanceof TLRPC.TL_messageEntityUnderline) && !(entity instanceof TLRPC.TL_messageEntityStrike)) return false;
            }
        }
        return isShort(message.message, maxChars, maxWords);
    }

    public static Map<Integer, Chain> build(List<Entry> newestFirst) {
        return build(newestFirst, DEFAULT_MAX_CHARS, DEFAULT_MAX_WORDS,
                DEFAULT_MAX_WINDOW_SECONDS, DEFAULT_MAX_CHAIN_SIZE);
    }

    public static Map<Integer, Chain> build(List<Entry> newestFirst, int maxChars, int maxWords,
                                            int maxWindowSeconds, int maxChainSize) {
        Map<Integer, Chain> result = new HashMap<>();
        if (maxChainSize < 2) return result;
        boolean ascending = false;
        for (int i = 1; i < newestFirst.size(); i++) {
            if (newestFirst.get(i - 1).date != newestFirst.get(i).date) {
                ascending = newestFirst.get(i - 1).date < newestFirst.get(i).date;
                break;
            }
        }
        if (ascending) {
            List<Entry> normalized = new ArrayList<>(newestFirst);
            java.util.Collections.reverse(normalized);
            newestFirst = normalized;
        }
        for (int i = 0; i < newestFirst.size();) {
            Entry first = newestFirst.get(i);
            int end = i + 1;
            if (first.eligible && isShort(first.text, maxChars, maxWords)) {
                while (end < newestFirst.size() && end - i < maxChainSize) {
                    Entry next = newestFirst.get(end);
                    Entry previous = newestFirst.get(end - 1);
                    if (!next.eligible || !isShort(next.text, maxChars, maxWords) || next.sender != first.sender || next.day != first.day
                            || next.date > previous.date || (long) first.date - next.date > maxWindowSeconds) break;
                    end++;
                }
                if (end - i >= 2) {
                    Chain chain = new Chain(newestFirst.subList(i, end));
                    for (int id : chain.ids) result.put(id, chain);
                }
            }
            i = end;
        }
        return result;
    }
}
