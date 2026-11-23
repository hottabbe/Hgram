package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgramDialogsClipTest {

    @Test
    public void clipNeverExceedsListPadding() {
        final int padding = 120;
        final int computed = HgramDialogsClip.clipTop(900f, 100f, 1200);
        assertEquals(800, computed);
        assertEquals(120, HgramDialogsClip.clampToPadding(computed, padding));
    }

    @Test
    public void negativeClipIsZero() {
        assertEquals(0, HgramDialogsClip.clampToPadding(-40, 120));
        assertEquals(0, HgramDialogsClip.clampToPadding(0, 0));
    }

    @Test
    public void headerAboveListKeepsComputedClip() {
        final int padding = 120;
        final int computed = HgramDialogsClip.clipTop(180f, 100f, 1200);
        assertEquals(80, computed);
        assertEquals(80, HgramDialogsClip.clampToPadding(computed, padding));
        assertTrue(computed < padding);
    }
}
