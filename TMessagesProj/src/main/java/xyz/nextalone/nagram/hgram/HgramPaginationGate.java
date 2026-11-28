package xyz.nextalone.nagram.hgram;

/** Guards pagination continuation against empty/repeated pages and stale query generations. */
public final class HgramPaginationGate {

    private HgramPaginationGate() {
    }

    public static boolean shouldContinue(boolean visibleEmpty, boolean cursorAdvanced,
                                         boolean endReached, int requestGeneration,
                                         int currentGeneration) {
        return visibleEmpty && cursorAdvanced && !endReached && requestGeneration == currentGeneration;
    }

    public static boolean shouldAutoLoad(boolean cursorAdvanced, boolean endReached,
                                         int requestGeneration, int currentGeneration) {
        return cursorAdvanced && !endReached && requestGeneration == currentGeneration;
    }

    public static boolean isStale(int requestGeneration, int currentGeneration) {
        return requestGeneration != currentGeneration;
    }

    public static boolean isStaleQuery(int requestGeneration, int currentGeneration,
                                       String requestQuery, String currentQuery) {
        if (requestGeneration != currentGeneration) {
            return true;
        }
        final String requested = requestQuery == null ? "" : requestQuery;
        final String current = currentQuery == null ? "" : currentQuery;
        return !requested.equals(current);
    }

    public static boolean sameWireCursor(long peer, int messageId, int rate,
                                          long otherPeer, int otherMessageId, int otherRate) {
        return peer == otherPeer && messageId == otherMessageId && rate == otherRate;
    }
}
