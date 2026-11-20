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
    public void gigaAmInfoDisappearsWhenTranscriptionTurnsOff() {
        int[] on = HgramSettingsActivity.buildBaseVisibleRows("gigaam", true);
        assertEquals(51, on.length);
        assertTrue(contains(on, HgramSettingsActivity.ROW_GIGAAM_INFO));
        assertTrue(contains(on, HgramSettingsActivity.ROW_GIGAAM_MODEL));

        int[] off = HgramSettingsActivity.buildBaseVisibleRows("gigaam", false);
        assertEquals(50, off.length);
        assertFalse(contains(off, HgramSettingsActivity.ROW_GIGAAM_INFO));
        assertTrue(contains(off, HgramSettingsActivity.ROW_GIGAAM_MODEL));

        int[] vosk = HgramSettingsActivity.buildBaseVisibleRows("vosk", false);
        assertEquals(50, vosk.length);
        assertFalse(contains(vosk, HgramSettingsActivity.ROW_GIGAAM_INFO));
        assertFalse(contains(vosk, HgramSettingsActivity.ROW_GIGAAM_MODEL));
    }

    @Test
    public void gigaAmInfoHiddenFromSearchWhenOff() {
        int[] rows = HgramSettingsActivity.buildSearchVisibleRows(searchKeep("gigaam", false, "giga"));
        assertFalse(contains(rows, HgramSettingsActivity.ROW_GIGAAM_INFO));
        assertTrue(contains(rows, HgramSettingsActivity.ROW_GIGAAM_MODEL));

        int[] enabled = HgramSettingsActivity.buildSearchVisibleRows(searchKeep("gigaam", true, "giga"));
        assertTrue(contains(enabled, HgramSettingsActivity.ROW_GIGAAM_INFO));
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

    @Test
    public void gigaAmInfoSearchUsesProductionIndex() {
        int[] on = HgramSettingsActivity.buildSearchVisibleRows(searchKeep("gigaam", true, "giga"));
        assertTrue(contains(on, HgramSettingsActivity.ROW_GIGAAM_INFO));
        assertTrue(contains(on, HgramSettingsActivity.ROW_GIGAAM_MODEL));

        int[] onUpper = HgramSettingsActivity.buildSearchVisibleRows(searchKeep("gigaam", true, "GIGA"));
        assertTrue(contains(onUpper, HgramSettingsActivity.ROW_GIGAAM_INFO));

        int[] onRu = HgramSettingsActivity.buildSearchVisibleRows(searchKeep("gigaam", true, "офлайн"));
        assertTrue(contains(onRu, HgramSettingsActivity.ROW_GIGAAM_INFO));

        int[] off = HgramSettingsActivity.buildSearchVisibleRows(searchKeep("gigaam", false, "giga"));
        assertFalse(contains(off, HgramSettingsActivity.ROW_GIGAAM_INFO));
        assertTrue(contains(off, HgramSettingsActivity.ROW_GIGAAM_MODEL));
    }

    @Test
    public void toggleKeepsPartiallyVisibleAnchorOffset() {
        int[] off = HgramSettingsActivity.buildBaseVisibleRows("gigaam", false);
        int[] on = HgramSettingsActivity.buildBaseVisibleRows("gigaam", true);

        int anchorIndex = indexOf(off, HgramSettingsSearch.ROW_OFFLINE_TRANSCRIPTION);
        int anchorRow = off[anchorIndex];
        // ROW_GIGAAM_INFO is inserted below the toggle, so the anchor index must not shift.
        assertEquals(anchorIndex, HgramSettingsActivity.findRowIndex(on, anchorRow));

        int infoIndex = HgramSettingsActivity.findRowIndex(on, HgramSettingsActivity.ROW_GIGAAM_INFO);
        int nearest = HgramSettingsActivity.nearestSurvivingRow(on, off, infoIndex);
        assertTrue(nearest >= 0);
        assertTrue(indexOf(off, nearest) >= 0);

        assertArrayEquals(off, HgramSettingsActivity.buildBaseVisibleRows("gigaam", false));
    }

    private static boolean[] searchKeep(String engine, boolean offlineEnabled, String query) {
        int[] rows = HgramSettingsActivity.ROWS;
        boolean[] keep = new boolean[rows.length];
        for (int i = 0; i < rows.length; i++) {
            int row = rows[i];
            if (HgramSettingsActivity.isHeaderRow(row)
                    || row == HgramSettingsActivity.ROW_RESET_HGRAM_SETTINGS) {
                continue;
            }
            // searchTextForRow() is private/LocaleController-bound; production matching must rely on
            // HgramSettingsSearch.keywords() for rows whose localized text is unavailable here.
            String text = "";
            keep[i] = HgramSettingsSearch.isRowVisible(row, engine, offlineEnabled)
                    && HgramSettingsSearch.matches(row, query, text);
        }
        return keep;
    }

    private static int indexOf(int[] rows, int id) {
        for (int i = 0; i < rows.length; i++) if (rows[i] == id) return i;
        return -1;
    }
}
