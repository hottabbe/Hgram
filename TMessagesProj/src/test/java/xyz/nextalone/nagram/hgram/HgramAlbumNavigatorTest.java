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
}
