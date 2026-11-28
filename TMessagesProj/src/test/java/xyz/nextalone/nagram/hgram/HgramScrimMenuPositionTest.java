package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HgramScrimMenuPositionTest {
    private static final int TOP = 100;
    private static final int BOTTOM = 1000;
    private static final int GAP = 8;

    @Test
    public void lowerHalfLiftsBubbleToUpperQuarter() {
        // available bottom = 992, height = 892, target top = 100 + 223 = 323
        assertEquals(-377f, HgramScrimMenuPosition.computeTranslationY(TOP, BOTTOM, GAP, 700, 100), 0.01f);
    }

    @Test
    public void upperHalfKeepsBubbleInPlace() {
        assertEquals(0f, HgramScrimMenuPosition.computeTranslationY(TOP, BOTTOM, GAP, 150, 100), 0.01f);
    }

    @Test
    public void bubbleAtExactMidpointIsNotLifted() {
        // cell bottom = (100 + 992) / 2 = 546, so lowerHalf must be false
        assertEquals(0f, HgramScrimMenuPosition.computeTranslationY(TOP, BOTTOM, GAP, 500, 46), 0.01f);
    }

    @Test
    public void bubbleTallerThanAvailableAreaIsNotLifted() {
        assertEquals(0f, HgramScrimMenuPosition.computeTranslationY(TOP, BOTTOM, GAP, 700, 1000), 0.01f);
    }

    @Test
    public void keyboardShrunkBottomMovesTargetTop() {
        // available bottom = 592, height = 492, target top = 100 + 123 = 223
        assertEquals(-177f, HgramScrimMenuPosition.computeTranslationY(TOP, 600, GAP, 400, 100), 0.01f);
    }

    @Test
    public void attachedLayoutKeepsMenuBelowLiftedBubble() {
        HgramScrimMenuPosition.AttachedLayout layout = HgramScrimMenuPosition.computeAttached(
                TOP, BOTTOM, GAP, 700, 100, 400, 400, 0, 160, 160, 120, TOP);
        assertEquals(-377f, layout.translationY, 0.01f);
        assertEquals(431, layout.popupY);
        assertEquals(561, layout.maxHeight);
    }

    @Test
    public void attachedLayoutClampsMenuToAvailableBottom() {
        HgramScrimMenuPosition.AttachedLayout layout = HgramScrimMenuPosition.computeAttached(
                TOP, BOTTOM, GAP, 700, 100, 600, 600, 0, 160, 160, 120, TOP);
        assertEquals(-408f, layout.translationY, 0.01f);
        assertEquals(400, layout.popupY);
        assertEquals(592, layout.maxHeight);
    }
}
