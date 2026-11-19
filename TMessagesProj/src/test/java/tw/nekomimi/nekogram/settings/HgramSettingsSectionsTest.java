package tw.nekomimi.nekogram.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramSettingsSectionsTest {

    private static void assertSectionOrder(int[] rows) {
        boolean seenHeader = false;
        boolean sectionHasContent = false;
        for (int i = 0; i < rows.length; i++) {
            final int row = rows[i];
            if (HgramSettingsActivity.isHeaderRow(row)) {
                assertFalse("header without content before it", seenHeader && !sectionHasContent);
                seenHeader = true;
                sectionHasContent = false;
                continue;
            }
            if (row == HgramSettingsActivity.ROW_SEARCH_EMPTY) {
                continue;
            }
            assertTrue("row without a preceding header", seenHeader);
            sectionHasContent = true;
        }
        assertTrue("last header has no rows", !seenHeader || sectionHasContent);
    }

    @Test
    public void visibleRowsKeepHeadersBeforeTheirOwnContent() {
        for (String engine : new String[]{"vosk", "sherpa", "gigaam"}) {
            assertSectionOrder(HgramSettingsActivity.buildBaseVisibleRows(engine));
        }
        assertSectionOrder(HgramSettingsActivity.ROWS);
    }

    private static boolean contains(int[] rows, int id) {
        for (int row : rows) {
            if (row == id) return true;
        }
        return false;
    }

    @Test
    public void resetRebuildsRowsForDefaultVoskEngine() {
        final int[] sherpa = HgramSettingsActivity.buildBaseVisibleRows("sherpa");
        assertTrue(contains(sherpa, HgramSettingsActivity.ROW_SHERPA_MODEL));
        assertTrue(contains(sherpa, HgramSettingsActivity.ROW_SHERPA_DEVICE));

        final int[] gigaAm = HgramSettingsActivity.buildBaseVisibleRows("gigaam");
        assertTrue(contains(gigaAm, HgramSettingsActivity.ROW_GIGAAM_MODEL));

        // HgramConfig.reset() clears the engine preference, so the rebuilt list must use Vosk.
        final int[] reset = HgramSettingsActivity.buildBaseVisibleRows("vosk");
        assertTrue(contains(reset, HgramSettingsActivity.ROW_VOSK_MODELS));
        assertFalse(contains(reset, HgramSettingsActivity.ROW_SHERPA_MODEL));
        assertFalse(contains(reset, HgramSettingsActivity.ROW_SHERPA_DEVICE));
        assertFalse(contains(reset, HgramSettingsActivity.ROW_GIGAAM_MODEL));
        assertSectionOrder(reset);

        // Repeating the reset/rebuild yields the same list.
        final int[] again = HgramSettingsActivity.buildBaseVisibleRows("vosk");
        assertEquals(reset.length, again.length);
        for (int i = 0; i < reset.length; i++) {
            assertEquals(reset[i], again[i]);
        }
    }

    @Test
    public void searchRowsSkipHeadersAndEmptySections() {
        final boolean[] keep = new boolean[HgramSettingsActivity.ROWS.length];
        for (int i = 0; i < keep.length; i++) {
            final int row = HgramSettingsActivity.ROWS[i];
            keep[i] = !HgramSettingsActivity.isHeaderRow(row)
                    && row != HgramSettingsActivity.ROW_RESET_HGRAM_SETTINGS
                    && HgramSettingsSearch.isRowVisible(row, "vosk");
        }
        final int[] all = HgramSettingsActivity.buildSearchVisibleRows(keep);
        assertSectionOrder(all);

        // Keep only the first normal row after the first header: its header remains, and no
        // other section header is emitted without content.
        final boolean[] one = new boolean[keep.length];
        for (int i = 0; i < one.length; i++) {
            final int row = HgramSettingsActivity.ROWS[i];
            if (!HgramSettingsActivity.isHeaderRow(row)) {
                one[i] = true;
                break;
            }
        }
        final int[] filtered = HgramSettingsActivity.buildSearchVisibleRows(one);
        assertSectionOrder(filtered);
        assertEquals(2, filtered.length);

        final int[] empty = HgramSettingsActivity.buildSearchVisibleRows(new boolean[keep.length]);
        assertEquals(1, empty.length);
        assertFalse(HgramSettingsActivity.isHeaderRow(empty[0]));
    }
}
