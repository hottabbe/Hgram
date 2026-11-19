package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramCompactAlbumRendererTest {
    @Test
    public void thresholdFallsBackToContainerAndKeepsMinimum() {
        assertEquals(300f, HgramCompactAlbumRenderer.effectiveWidth(0f, 300f), 0.01f);
        assertEquals(200f, HgramCompactAlbumRenderer.effectiveWidth(200f, 300f), 0.01f);
        assertEquals(75f, HgramCompactAlbumRenderer.swipeThreshold(300f, 40f), 0.01f);
        assertEquals(40f, HgramCompactAlbumRenderer.swipeThreshold(100f, 40f), 0.01f);
        assertEquals(1f, HgramCompactAlbumRenderer.effectiveWidth(0f, 0f), 0.01f);
    }

    @Test
    public void rtlInvertsDirectionButNotThreshold() {
        assertTrue(HgramCompactAlbumRenderer.isForward(-10f, false));
        assertFalse(HgramCompactAlbumRenderer.isForward(10f, false));
        assertTrue(HgramCompactAlbumRenderer.isForward(10f, true));
        assertFalse(HgramCompactAlbumRenderer.isForward(-10f, true));
        assertEquals(HgramCompactAlbumRenderer.swipeThreshold(300f, 40f),
                HgramCompactAlbumRenderer.swipeThreshold(300f, 40f), 0.01f);
    }

    @Test
    public void counterShowsOneBasedPosition() {
        assertEquals("1/3", HgramCompactAlbumRenderer.counterLabel(0, 3));
        assertEquals("2/3", HgramCompactAlbumRenderer.counterLabel(1, 3));
        assertEquals("3/3", HgramCompactAlbumRenderer.counterLabel(2, 3));
        assertEquals("1/1", HgramCompactAlbumRenderer.counterLabel(4, 1));
    }
}
