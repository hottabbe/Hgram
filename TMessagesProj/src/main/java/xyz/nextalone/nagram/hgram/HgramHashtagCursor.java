package xyz.nextalone.nagram.hgram;

import org.telegram.tgnet.TLRPC;

/** Raw server cursor for hashtag/public-post pagination, independent of hidden-row filtering. */
public final class HgramHashtagCursor {

    private TLRPC.InputPeer offsetPeer;
    private int offsetRate;
    private int rawCount;
    private int totalCount;
    private boolean endReached;

    public void reset() {
        offsetPeer = null;
        offsetRate = 0;
        rawCount = 0;
        totalCount = 0;
        endReached = false;
    }

    public void initialize(TLRPC.InputPeer peer, int rate, int rawCount, int totalCount) {
        offsetPeer = peer;
        offsetRate = rate;
        this.rawCount = rawCount;
        this.totalCount = totalCount;
        endReached = totalCount > 0 && rawCount >= totalCount;
    }

    public void received(TLRPC.InputPeer tailPeer, int rate, int received, int totalCount, int pageSize) {
        offsetRate = rate;
        if (received > 0 && tailPeer != null) {
            offsetPeer = tailPeer;
        }
        rawCount += received;
        this.totalCount = totalCount;
        endReached = received < pageSize || rawCount >= totalCount;
    }

    public void failed() {
        endReached = true;
        totalCount = rawCount;
    }

    public TLRPC.InputPeer offsetPeer() {
        return offsetPeer;
    }

    public int offsetRate() {
        return offsetRate;
    }

    public boolean endReached() {
        return endReached;
    }

    public int rawCount() {
        return rawCount;
    }
}
