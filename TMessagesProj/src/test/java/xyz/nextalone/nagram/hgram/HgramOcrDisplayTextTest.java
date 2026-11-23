package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramOcrDisplayTextTest {

    @Test
    public void layoutFallbackKeepsFullRecognizedText() {
        assertEquals("весь распознанный текст", HgramOcrDisplayText.panelText(
                "весь распознанный текст", "фрагмент"));
    }

    @Test
    public void missingFullTextFallsBackToSelection() {
        assertEquals("фрагмент", HgramOcrDisplayText.panelText(null, "фрагмент"));
        assertEquals("фрагмент", HgramOcrDisplayText.panelText("", "фрагмент"));
    }

    @Test
    public void validLayoutNeverFallsBackToPanel() {
        final List<HgramOcrLayout.Line> lines = new ArrayList<>();
        final List<HgramOcrLayout.Word> words = new ArrayList<>();
        words.add(new HgramOcrLayout.Word(0.1f, 0.1f, 0.5f, 0.2f, "text"));
        lines.add(new HgramOcrLayout.Line(0.1f, 0.1f, 0.9f, 0.2f, "text", words));
        final HgramOcrLayout layout = HgramOcrLayout.ofNormalized(
                100, 100, HgramOcrLayout.SPACE_DISPLAY, lines);
        assertTrue(HgramOcrDisplayText.shouldUseLayout(layout, false, false, 1000, 1000));
        assertFalse(HgramOcrDisplayText.shouldUseLayout(null, false, false, 1000, 1000));
        assertFalse(HgramOcrDisplayText.shouldUseLayout(layout, true, false, 1000, 1000));
        assertFalse(HgramOcrDisplayText.shouldUseLayout(layout, false, true, 1000, 1000));
        assertFalse(HgramOcrDisplayText.shouldUseLayout(layout, false, false, 2000, 1000));
    }
}
