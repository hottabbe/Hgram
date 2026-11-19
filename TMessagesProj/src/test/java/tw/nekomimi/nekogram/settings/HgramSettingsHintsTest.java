package tw.nekomimi.nekogram.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class HgramSettingsHintsTest {

    @Test
    public void rowsWithoutHintAreSilent() {
        assertEquals(0, HgramSettingsHints.hintRes(48)); // section description
        assertEquals(0, HgramSettingsHints.hintRes(37)); // header
        assertEquals(0, HgramSettingsHints.hintRes(-101)); // empty search row
        assertEquals(0, HgramSettingsHints.hintRes(9999));
    }

    @Test
    public void settingsRowsHaveHints() {
        int[] rows = {1, 3, 5, 8, 12, 14, 15, 16, 18, 20, 22, 24, 27, 29, 31, 33, 35, 41, 42, 43, 44, 45, 46, 47};
        for (int row : rows) {
            assertNotEquals("row " + row, 0, HgramSettingsHints.hintRes(row));
        }
    }
}
