package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramExifOcrTest {

    @Test
    public void neutralNamesHideOriginalPathAndUnicode() {
        assertTrue(HgramExifCleaner.neutralName("/storage/DCIM/Отпуск 2026.JPG",
                1763383500000L, true).matches("IMG_\\d{8}_\\d{6}\\.jpg"));
        assertTrue(HgramExifCleaner.neutralName("договор.pdf",
                1763383500000L, false).matches("FILE_\\d{8}_\\d{6}\\.pdf"));
        assertTrue(HgramExifCleaner.neutralName("noext",
                1763383500000L, false).matches("FILE_\\d{8}_\\d{6}"));
    }

    @Test
    public void cleanupOnlyForFileSendsAndCompressedPhotosStayUntouched() {
        assertTrue(HgramExifCleaner.shouldClean(true, false));
        assertFalse(HgramExifCleaner.shouldClean(false, false));
        assertFalse(HgramExifCleaner.shouldClean(true, true));
        assertTrue(HgramExifCleaner.shouldRewriteVideoName(true, true));
        assertFalse(HgramExifCleaner.shouldRewriteVideoName(false, true));
    }

    @Test
    public void ocrDownscaleKeepsAspectAndNeverUpscales() {
        assertEquals(1600, HgramOcrText.scaledLongSide(3200, 1600, 1600));
        assertEquals(800, HgramOcrText.scaledShortSide(3200, 1600, 1600));
        assertEquals(900, HgramOcrText.scaledLongSide(900, 600, 1600));
        assertEquals(600, HgramOcrText.scaledShortSide(900, 600, 1600));
        assertEquals(0, HgramOcrText.scaledLongSide(0, 0, 1600));
    }

    @Test
    public void ocrTextPreservesLineOrderAndSkipsEmptyBlocks() {
        assertEquals("Первая строка\nВторая строка",
                HgramOcrText.joinLines(Arrays.asList("Первая строка", " ", "Вторая строка")));
        assertEquals("", HgramOcrText.joinLines(Arrays.asList("", null)));
        assertEquals("", HgramOcrText.copyText(null));
        assertEquals("abc", HgramOcrText.copyText("abc"));
    }
}
