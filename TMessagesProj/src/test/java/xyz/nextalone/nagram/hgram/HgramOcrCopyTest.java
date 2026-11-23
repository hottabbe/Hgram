package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgramOcrCopyTest {

    @Test
    public void copyWritesOnlyThroughClipboardSink() {
        final int[] calls = {0};
        final String[] written = new String[1];
        assertTrue(HgramOcrCopy.copy("ECHO FOXTROT", text -> {
            calls[0]++;
            written[0] = text;
        }));
        assertEquals(1, calls[0]);
        assertEquals("ECHO FOXTROT", written[0]);
    }

    @Test
    public void emptyOrMissingTextNeverTouchesSink() {
        final int[] calls = {0};
        assertFalse(HgramOcrCopy.copy(null, text -> calls[0]++));
        assertFalse(HgramOcrCopy.copy("", text -> calls[0]++));
        assertFalse(HgramOcrCopy.copy("ECHO", null));
        assertEquals(0, calls[0]);
    }
}
