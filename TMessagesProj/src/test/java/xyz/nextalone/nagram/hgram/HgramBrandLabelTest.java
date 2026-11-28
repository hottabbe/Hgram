package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HgramBrandLabelTest {

    @Test
    public void brandValuesBecomeGram() {
        assertEquals("gram", HgramBrandLabel.displayText("Hgram"));
        assertEquals("gram", HgramBrandLabel.displayText("hgram"));
        assertEquals("gram", HgramBrandLabel.displayText("HGRAM"));
    }

    @Test
    public void customTitleIsUntouched() {
        assertEquals("Мой клиент", HgramBrandLabel.displayText("Мой клиент"));
        assertEquals("Nagram Custom", HgramBrandLabel.displayText("Nagram Custom"));
    }

    @Test
    public void emptyAndWhitespaceAreUntouched() {
        assertEquals("", HgramBrandLabel.displayText(""));
        assertEquals("   ", HgramBrandLabel.displayText("   "));
        assertNull(HgramBrandLabel.displayText(null));
    }

    @Test
    public void rtlTitleIsUntouched() {
        assertEquals("عميل خاص", HgramBrandLabel.displayText("عميل خاص"));
        assertEquals("عميل Hgram", HgramBrandLabel.displayText("عميل Hgram"));
    }
}
