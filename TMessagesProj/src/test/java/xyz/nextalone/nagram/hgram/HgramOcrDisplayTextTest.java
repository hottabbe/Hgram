package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
