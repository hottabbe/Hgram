package xyz.nextalone.nagram.hgram;

import java.util.HashSet;

/** Remembers every pagination cursor already requested in one query generation. */
public final class HgramCursorHistory {

    private int generation = Integer.MIN_VALUE;
    private boolean news;
    private final HashSet<String> cursors = new HashSet<String>();

    public boolean isRepeated(int requestGeneration, boolean requestNews,
                              long peer, int messageId, int rate) {
        ensureGeneration(requestGeneration, requestNews);
        return cursors.contains(key(peer, messageId, rate));
    }

    public void mark(int requestGeneration, boolean requestNews,
                     long peer, int messageId, int rate) {
        ensureGeneration(requestGeneration, requestNews);
        cursors.add(key(peer, messageId, rate));
    }

    public void forget(int requestGeneration, boolean requestNews,
                       long peer, int messageId, int rate) {
        ensureGeneration(requestGeneration, requestNews);
        cursors.remove(key(peer, messageId, rate));
    }

    public void reset() {
        generation = Integer.MIN_VALUE;
        cursors.clear();
    }

    private void ensureGeneration(int requestGeneration, boolean requestNews) {
        if (requestGeneration != generation || requestNews != news) {
            generation = requestGeneration;
            news = requestNews;
            cursors.clear();
        }
    }

    private static String key(long peer, int messageId, int rate) {
        return peer + ":" + messageId + ":" + rate;
    }
}
