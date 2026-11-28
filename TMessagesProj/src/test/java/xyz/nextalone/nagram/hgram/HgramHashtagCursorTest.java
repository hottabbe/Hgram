package xyz.nextalone.nagram.hgram;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HgramHashtagCursorTest {

    @Test
    public void publicPostsContinuesAfterFullyHiddenPageAndResetsOnQueryChange() {
        final TLRPC.TL_inputPeerEmpty firstTail = new TLRPC.TL_inputPeerEmpty();
        final TLRPC.TL_inputPeerEmpty secondTail = new TLRPC.TL_inputPeerEmpty();
        final HgramHashtagCursor cursor = new HgramHashtagCursor();

        cursor.received(firstTail, 100, 10, 30, 10);
        assertFalse(cursor.endReached());
        assertSame(firstTail, cursor.offsetPeer());
        assertEquals(100, cursor.offsetRate());
        assertEquals(10, cursor.rawCount());

        cursor.received(secondTail, 90, 10, 30, 10);
        assertFalse(cursor.endReached());
        assertSame(secondTail, cursor.offsetPeer());
        assertEquals(20, cursor.rawCount());

        cursor.received(null, 80, 10, 30, 10);
        assertTrue(cursor.endReached());

        cursor.reset();
        assertFalse(cursor.endReached());
        assertNull(cursor.offsetPeer());
        assertEquals(0, cursor.offsetRate());
        assertEquals(0, cursor.rawCount());
    }

    @Test
    public void seedKeepsRawTailAndRateBeforeAnyPage() {
        final TLRPC.TL_inputPeerEmpty rawTail = new TLRPC.TL_inputPeerEmpty();
        final HgramHashtagCursor cursor = new HgramHashtagCursor();
        cursor.initialize(rawTail, 77, 10, 30);

        assertSame(rawTail, cursor.offsetPeer());
        assertEquals(77, cursor.offsetRate());
        assertEquals(10, cursor.rawCount());
        assertFalse(cursor.endReached());
    }
}
