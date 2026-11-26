package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class HgramAlbumNavigatorTest {

    private static final int FORWARD = HgramAlbumNavigator.DIRECTION_FORWARD;
    private static final int BACK = HgramAlbumNavigator.DIRECTION_BACK;
    private static final int CLOSE = HgramAlbumNavigator.CLOSE;
    private static final int NO_MOVE = HgramAlbumNavigator.NO_MOVE;

    /** Chat imagesArr is newest -> oldest: index 1 is the newest album item, index 4 the oldest. */
    private static final long[] CHAT_ALBUM = {0L, 7L, 7L, 7L, 7L, 0L};
    /** Media tab is oldest -> newest: index 1 is the oldest album item. */
    private static final long[] MEDIA_ALBUM = {0L, 7L, 7L, 7L, 7L, 0L};

    @Test
    public void chatAlbumFollowsGridOrderWithoutRepeats() {
        // Grid left -> right for the chat array is 4, 3, 2, 1.
        int index = 4;
        int[] expectedForward = {3, 2, 1, CLOSE};
        for (int expected : expectedForward) {
            int target = HgramAlbumNavigator.resolveMove(
                    CHAT_ALBUM.length, index, false, CHAT_ALBUM, FORWARD, true);
            assertEquals(expected, target);
            assertNotEquals(index, target);
            index = target;
        }

        index = 1;
        int[] expectedBack = {2, 3, 4, CLOSE};
        for (int expected : expectedBack) {
            int target = HgramAlbumNavigator.resolveMove(
                    CHAT_ALBUM.length, index, false, CHAT_ALBUM, BACK, true);
            assertEquals(expected, target);
            assertNotEquals(index, target);
            index = target;
        }

        // Chat album: forward means a lower array index, so the forward preview is the right slot.
        assertTrue(HgramAlbumNavigator.isForwardStep(false, 7L, 7L, 2, 1));
        assertFalse(HgramAlbumNavigator.isForwardStep(false, 7L, 7L, 2, 3));
        assertTrue(HgramAlbumNavigator.isForwardStep(false, 0L, 0L, 2, 3));
    }

    @Test
    public void mediaTabAlbumKeepsOldestToNewestSequence() {
        int index = 1;
        int[] expectedForward = {2, 3, 4, 5, CLOSE};
        for (int expected : expectedForward) {
            int target = HgramAlbumNavigator.resolveMove(
                    MEDIA_ALBUM.length, index, true, MEDIA_ALBUM, FORWARD, true);
            assertEquals(expected, target);
            assertNotEquals(index, target);
            index = target;
        }

        index = 4;
        int[] expectedBack = {3, 2, 1, 0, CLOSE};
        for (int expected : expectedBack) {
            int target = HgramAlbumNavigator.resolveMove(
                    MEDIA_ALBUM.length, index, true, MEDIA_ALBUM, BACK, true);
            assertEquals(expected, target);
            assertNotEquals(index, target);
            index = target;
        }
    }

    @Test
    public void albumEdgesCloseInsteadOfLeavingTheGroup() {
        // Newest album item: forward is the right grid edge, next array item belongs to another message.
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(
                CHAT_ALBUM.length, 1, false, CHAT_ALBUM, FORWARD, true));
        // Oldest album item: back is the left grid edge, index 5 must not be reached.
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(
                CHAT_ALBUM.length, 4, false, CHAT_ALBUM, BACK, true));
        assertEquals(CLOSE, HgramAlbumNavigator.neighborIndex(
                CHAT_ALBUM.length, 1, false, CHAT_ALBUM, FORWARD));
        assertEquals(CLOSE, HgramAlbumNavigator.neighborIndex(
                CHAT_ALBUM.length, 4, false, CHAT_ALBUM, BACK));
    }

    @Test
    public void mediaTabEdgeOfAlbumMovesToFlatNeighbourNotClose() {
        // Media tab arrays are flat even when the current item belongs to a grouped album.
        assertEquals(0, HgramAlbumNavigator.resolveMove(
                MEDIA_ALBUM.length, 1, true, MEDIA_ALBUM, BACK, true));
        assertEquals(5, HgramAlbumNavigator.resolveMove(
                MEDIA_ALBUM.length, 4, true, MEDIA_ALBUM, FORWARD, true));
        // Only the real edges of the media array close the viewer.
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(
                MEDIA_ALBUM.length, 0, true, MEDIA_ALBUM, BACK, true));
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(
                MEDIA_ALBUM.length, 5, true, MEDIA_ALBUM, FORWARD, true));
    }

    @Test
    public void chatAlbumEdgeStillCloses() {
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(
                CHAT_ALBUM.length, 1, false, CHAT_ALBUM, FORWARD, true));
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(
                CHAT_ALBUM.length, 4, false, CHAT_ALBUM, BACK, true));
    }

    @Test
    public void singlePhotoAndSingleItemAlbumUseFlatRules() {
        long[] flat = {0L, 0L, 0L};
        int index = 1;
        int[] forward = {2, CLOSE};
        for (int expected : forward) {
            int target = HgramAlbumNavigator.resolveMove(flat.length, index, false, flat, FORWARD, true);
            assertEquals(expected, target);
            index = target;
        }
        assertEquals(0, HgramAlbumNavigator.resolveMove(flat.length, 1, false, flat, BACK, true));
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(flat.length, 0, false, flat, BACK, true));

        // A group with a single element must close instead of switching to itself.
        long[] singleItemAlbum = {0L, 9L, 0L};
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(
                singleItemAlbum.length, 1, false, singleItemAlbum, FORWARD, true));
        assertEquals(CLOSE, HgramAlbumNavigator.resolveMove(
                singleItemAlbum.length, 1, false, singleItemAlbum, BACK, true));
    }

    @Test
    public void flatFallbackLeavesChatAlbumToAdjacentMessage() {
        // Newest album item (right edge of the mirrored grid): forward goes to the newer chat photo.
        assertEquals(0, HgramAlbumNavigator.flatFallbackIndex(
                CHAT_ALBUM.length, 1, false, CHAT_ALBUM, FORWARD));
        // Oldest album item (left edge): back goes to the older chat photo.
        assertEquals(5, HgramAlbumNavigator.flatFallbackIndex(
                CHAT_ALBUM.length, 4, false, CHAT_ALBUM, BACK));
    }

    @Test
    public void flatFallbackClosesOnlyAtRealArrayEdges() {
        final long[] flat = {0L, 0L, 0L};
        assertEquals(CLOSE, HgramAlbumNavigator.flatFallbackIndex(
                flat.length, 0, false, flat, BACK));
        assertEquals(CLOSE, HgramAlbumNavigator.flatFallbackIndex(
                flat.length, 2, false, flat, FORWARD));
        assertEquals(1, HgramAlbumNavigator.flatFallbackIndex(
                flat.length, 0, false, flat, FORWARD));
        assertEquals(1, HgramAlbumNavigator.flatFallbackIndex(
                flat.length, 2, false, flat, BACK));
    }

    @Test
    public void flatFallbackHandlesSingleItemAlbumAndMediaTab() {
        final long[] singleItemAlbum = {0L, 9L, 0L};
        assertEquals(0, HgramAlbumNavigator.flatFallbackIndex(
                singleItemAlbum.length, 1, false, singleItemAlbum, FORWARD));
        assertEquals(2, HgramAlbumNavigator.flatFallbackIndex(
                singleItemAlbum.length, 1, false, singleItemAlbum, BACK));

        assertEquals(0, HgramAlbumNavigator.flatFallbackIndex(
                MEDIA_ALBUM.length, 1, true, MEDIA_ALBUM, BACK));
        assertEquals(5, HgramAlbumNavigator.flatFallbackIndex(
                MEDIA_ALBUM.length, 4, true, MEDIA_ALBUM, FORWARD));
        assertEquals(CLOSE, HgramAlbumNavigator.flatFallbackIndex(
                MEDIA_ALBUM.length, 0, true, MEDIA_ALBUM, BACK));
        assertEquals(CLOSE, HgramAlbumNavigator.flatFallbackIndex(
                MEDIA_ALBUM.length, 5, true, MEDIA_ALBUM, FORWARD));
    }

    @Test
    public void unloadedNeighbourSuppressesTheMove() {
        assertEquals(NO_MOVE, HgramAlbumNavigator.resolveMove(
                CHAT_ALBUM.length, 2, false, CHAT_ALBUM, FORWARD, false));
        assertEquals(NO_MOVE, HgramAlbumNavigator.resolveMove(
                CHAT_ALBUM.length, 2, false, CHAT_ALBUM, BACK, false));
        assertEquals(1, HgramAlbumNavigator.resolveMove(
                CHAT_ALBUM.length, 2, false, CHAT_ALBUM, FORWARD, true));
        assertEquals(3, HgramAlbumNavigator.resolveMove(
                CHAT_ALBUM.length, 2, false, CHAT_ALBUM, BACK, true));
    }

    @Test
    public void rtlLocaleDoesNotChangeLtrGridSequence() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals(3, HgramAlbumNavigator.neighborIndex(
                    CHAT_ALBUM.length, 4, false, CHAT_ALBUM, FORWARD));
            assertEquals(2, HgramAlbumNavigator.neighborIndex(
                    CHAT_ALBUM.length, 3, false, CHAT_ALBUM, FORWARD));
            assertEquals(2, HgramAlbumNavigator.neighborIndex(
                    MEDIA_ALBUM.length, 1, true, MEDIA_ALBUM, FORWARD));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void previewNeighbourFallsBackToFlatIndexAtAlbumEdges() {
        // setImages() preloads these indexes into the side receivers before the swipe.
        assertEquals(0, HgramAlbumNavigator.previewIndex(
                CHAT_ALBUM.length, 1, false, CHAT_ALBUM, FORWARD));
        assertEquals(5, HgramAlbumNavigator.previewIndex(
                CHAT_ALBUM.length, 4, false, CHAT_ALBUM, BACK));
        // Inside the album the preview stays group-bound; real array edges stay empty.
        assertEquals(3, HgramAlbumNavigator.previewIndex(
                CHAT_ALBUM.length, 4, false, CHAT_ALBUM, FORWARD));
        assertEquals(CLOSE, HgramAlbumNavigator.previewIndex(
                CHAT_ALBUM.length, 0, false, CHAT_ALBUM, BACK));
        assertEquals(CLOSE, HgramAlbumNavigator.previewIndex(
                CHAT_ALBUM.length, 5, false, CHAT_ALBUM, FORWARD));
        // Media tab keeps the flat preview at album edges.
        assertEquals(0, HgramAlbumNavigator.previewIndex(
                MEDIA_ALBUM.length, 1, true, MEDIA_ALBUM, BACK));
        assertEquals(5, HgramAlbumNavigator.previewIndex(
                MEDIA_ALBUM.length, 4, true, MEDIA_ALBUM, FORWARD));
        // Single photo at the array edge has no preview target.
        long[] flat = {0L, 0L, 0L};
        assertEquals(CLOSE, HgramAlbumNavigator.previewIndex(flat.length, 0, false, flat, BACK));
        assertEquals(1, HgramAlbumNavigator.previewIndex(flat.length, 0, false, flat, FORWARD));
    }

    @Test
    public void boundaryForwardStepKeepsAlbumMirror() {
        // Leaving a chat album keeps the mirrored direction; entering it uses the flat step.
        assertTrue(HgramAlbumNavigator.isForwardStep(false, 7L, 0L, 4, 3));
        assertFalse(HgramAlbumNavigator.isForwardStep(false, 7L, 0L, 4, 5));
        assertTrue(HgramAlbumNavigator.isForwardStep(false, 0L, 7L, 2, 3));
        assertFalse(HgramAlbumNavigator.isForwardStep(false, 0L, 7L, 2, 1));
        // Media tab stays flat across album boundaries.
        assertTrue(HgramAlbumNavigator.isForwardStep(true, 7L, 0L, 2, 3));
        assertFalse(HgramAlbumNavigator.isForwardStep(true, 7L, 0L, 2, 1));
    }

    @Test
    public void edgeReturnContinuesOutwardAndReturnsToAlbum() {
        // Forward exit: album edge 2 -> flat 1. Same direction goes further out, reverse returns.
        assertEquals(0, HgramAlbumNavigator.edgeReturnIndex(6, 1, 2, FORWARD, FORWARD));
        assertEquals(2, HgramAlbumNavigator.edgeReturnIndex(6, 1, 2, FORWARD, BACK));
        assertEquals(CLOSE, HgramAlbumNavigator.edgeReturnIndex(6, 0, 2, FORWARD, FORWARD));
        assertEquals(1, HgramAlbumNavigator.edgeReturnIndex(6, 0, 2, FORWARD, BACK));

        // Backward exit: album edge 3 -> flat 4. Same direction goes further out, reverse returns.
        assertEquals(5, HgramAlbumNavigator.edgeReturnIndex(6, 4, 3, BACK, BACK));
        assertEquals(3, HgramAlbumNavigator.edgeReturnIndex(6, 4, 3, BACK, FORWARD));
        assertEquals(CLOSE, HgramAlbumNavigator.edgeReturnIndex(6, 5, 3, BACK, BACK));
        assertEquals(4, HgramAlbumNavigator.edgeReturnIndex(6, 5, 3, BACK, FORWARD));

        // Outside the outward side or with an unrelated direction there is no override.
        assertEquals(HgramAlbumNavigator.NO_EDGE_RETURN,
                HgramAlbumNavigator.edgeReturnIndex(6, 2, 2, FORWARD, FORWARD));
        assertEquals(HgramAlbumNavigator.NO_EDGE_RETURN,
                HgramAlbumNavigator.edgeReturnIndex(6, 1, 2, FORWARD, 0));
    }
}
