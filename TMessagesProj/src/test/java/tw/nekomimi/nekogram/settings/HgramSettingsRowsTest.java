package tw.nekomimi.nekogram.settings;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class HgramSettingsRowsTest {

    @Test
    public void voskKeepsFullList() {
        int[] rows = HgramSettingsActivity.buildBaseVisibleRows("vosk");
        assertEquals(50, rows.length);
        assertTrue(contains(rows, HgramSettingsActivity.ROW_STT_ENGINE));
        assertTrue(contains(rows, HgramSettingsActivity.ROW_VOSK_MODELS));
        assertTrue(contains(rows, HgramSettingsActivity.ROW_HAPTIC_TOGGLE));
        assertTrue(contains(rows, HgramSettingsActivity.ROW_HAPTIC_BASE));
        assertTrue(contains(rows, HgramSettingsActivity.ROW_HAPTIC_BASE + 14));
        assertTrue(contains(rows, HgramSettingsActivity.ROW_DOWNLOAD_THREADS));
        assertTrue(contains(rows, HgramSettingsActivity.ROW_UPLOAD_THREADS));
        assertFalse(contains(rows, HgramSettingsActivity.ROW_SHERPA_MODEL));
        assertFalse(contains(rows, HgramSettingsActivity.ROW_GIGAAM_MODEL));
    }

    @Test
    public void sherpaShowsOnlySherpaModels() {
        int[] rows = HgramSettingsActivity.buildBaseVisibleRows("sherpa");
        assertEquals(51, rows.length);
        assertTrue(contains(rows, HgramSettingsActivity.ROW_SHERPA_MODEL));
        assertTrue(contains(rows, HgramSettingsActivity.ROW_SHERPA_DEVICE));
        assertFalse(contains(rows, HgramSettingsActivity.ROW_VOSK_MODELS));
        assertFalse(contains(rows, HgramSettingsActivity.ROW_GIGAAM_MODEL));
    }

    @Test
    public void gigaamShowsOnlyGigaAmModelAndUnknownFallsBackToVosk() {
        int[] gigaam = HgramSettingsActivity.buildBaseVisibleRows("gigaam");
        assertEquals(51, gigaam.length);
        assertTrue(contains(gigaam, HgramSettingsActivity.ROW_GIGAAM_INFO));
        assertFalse(contains(gigaam, HgramSettingsActivity.ROW_VOSK_MODELS));
        assertFalse(contains(gigaam, HgramSettingsActivity.ROW_SHERPA_MODEL));

        int[] fallback = HgramSettingsActivity.buildBaseVisibleRows("broken-engine");
        int[] vosk = HgramSettingsActivity.buildBaseVisibleRows("vosk");
        assertArrayEquals(vosk, fallback);
    }

    @Test
    public void emptySearchProducesInfoRowAndSafeViews() {
        int[] empty = HgramSettingsActivity.buildSearchVisibleRows(new boolean[HgramSettingsActivity.ROWS.length]);
        assertArrayEquals(new int[]{HgramSettingsActivity.ROW_SEARCH_EMPTY}, empty);
        assertEquals(HgramSettingsActivity.TYPE_INFO_PRIVACY,
                HgramSettingsActivity.rowViewType(empty[0]));
        assertEquals(HgramSettingsActivity.TYPE_SETTINGS,
                HgramSettingsActivity.rowViewType(HgramSettingsActivity.ROW_SUMMARIZER_TRIGGER));
        assertEquals(HgramSettingsActivity.TYPE_CHECK,
                HgramSettingsActivity.rowViewType(HgramSettingsActivity.ROW_SUMMARIZER_ENABLE));
        assertEquals(HgramSettingsActivity.TYPE_DOWNLOAD_THREADS,
                HgramSettingsActivity.rowViewType(HgramSettingsActivity.ROW_DOWNLOAD_THREADS));
        assertEquals(HgramSettingsActivity.TYPE_UPLOAD_THREADS,
                HgramSettingsActivity.rowViewType(HgramSettingsActivity.ROW_UPLOAD_THREADS));
    }

    @Test
    public void summarizerLivesInChatAndCompactAlbumsOpensFeed() {
        int[] rows = HgramSettingsActivity.ROWS;
        int chat = indexOf(rows, HgramSettingsActivity.ROW_HEADER_CHAT);
        int pin = indexOf(rows, HgramSettingsActivity.ROW_PIN_TO_SCREEN);
        int summarizer = indexOf(rows, HgramSettingsActivity.ROW_SUMMARIZER_ENABLE);
        int feed = indexOf(rows, HgramSettingsActivity.ROW_HEADER_FEED);
        int compact = indexOf(rows, HgramSettingsActivity.ROW_COMPACT_ALBUMS);
        assertTrue(chat < pin && pin < summarizer && summarizer < feed);
        assertEquals(feed + 1, compact);
    }

    private static boolean contains(int[] rows, int id) {
        for (int row : rows) if (row == id) return true;
        return false;
    }

    private static int indexOf(int[] rows, int id) {
        for (int i = 0; i < rows.length; i++) if (rows[i] == id) return i;
        return -1;
    }
}
