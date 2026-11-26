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

    @Test
    public void effectivePaddingTracksStoriesOverscroll() {
        assertEquals(120, HgramDialogsClip.effectivePaddingTop(120, 0f));
        assertEquals(151, HgramDialogsClip.effectivePaddingTop(120, 30.5f));
        assertEquals(210, HgramDialogsClip.effectivePaddingTop(120, 90f));
        assertEquals(120, HgramDialogsClip.effectivePaddingTop(120, -10f));
        assertEquals(120, HgramDialogsClip.effectivePaddingTop(120, Float.NaN));
    }

    @Test
    public void clipFollowsRowsDuringOverscrollAndReturnsAfterRelease() {
        final int padding = 120;
        final float overscroll = 40f;
        final int computed = HgramDialogsClip.clipTop(260f, 100f, 1200);
        assertEquals(160, computed);
        // Static padding alone would clamp to 120 and leave a 40 px empty band.
        assertEquals(120, HgramDialogsClip.clampToPadding(computed, padding));
        assertEquals(160, HgramDialogsClip.clampToPadding(
                computed, HgramDialogsClip.effectivePaddingTop(padding, overscroll)));

        // Release: the animator brings overscroll back to zero, clip returns to padding.
        assertEquals(120, HgramDialogsClip.clampToPadding(
                computed, HgramDialogsClip.effectivePaddingTop(padding, 0f)));
    }

    @Test
    public void effectivePaddingStillCutsStaleHeaderDuringOverscroll() {
        final int padding = HgramDialogsClip.effectivePaddingTop(120, 40f);
        assertEquals(160, padding);
        assertEquals(160, HgramDialogsClip.clampToPadding(900, padding));
        assertEquals(0, HgramDialogsClip.clampToPadding(-10, padding));
    }

    @Test
    public void resolveClipTopTracksTheSameOverscrollAsTheRows() {
        // headerBottom 260, listTop 100, scale 1 -> computed clip is 160 for every row offset.
        final float headerBottom = 260f;
        final float listTop = 100f;
        final float[] overscrolls = {0f, 30.5f, 90f, -10f, Float.NaN};
        final int[] expectedPadding = {120, 151, 210, 120, 120};
        final int[] expectedClip = {120, 151, 160, 120, 120};
        for (int i = 0; i < overscrolls.length; i++) {
            final HgramDialogsClip.Clip clip = HgramDialogsClip.resolveClipTop(
                    headerBottom, listTop, 1f, 1200, 120, overscrolls[i]);
            assertEquals(160, clip.computed);
            assertEquals(expectedPadding[i], clip.paddingTop);
            assertEquals(expectedClip[i], clip.clipTop);
        }
    }

    @Test
    public void resolveClipTopHandlesScaleAndHeightEdges() {
        assertEquals(0, HgramDialogsClip.resolveClipTop(
                900f, 100f, 0f, 1200, 120, 0f).clipTop);
        assertEquals(0, HgramDialogsClip.resolveClipTop(
                900f, 100f, -1f, 1200, 120, 0f).clipTop);

        final HgramDialogsClip.Clip tall = HgramDialogsClip.resolveClipTop(
                2000f, 100f, 1f, 1200, 120, 0f);
        assertEquals(1200, tall.computed);
        assertEquals(120, tall.clipTop);

        final HgramDialogsClip.Clip negative = HgramDialogsClip.resolveClipTop(
                80f, 100f, 1f, 1200, -10, 0f);
        assertEquals(0, negative.computed);
        assertEquals(0, negative.paddingTop);
        assertEquals(0, negative.clipTop);
    }
}
