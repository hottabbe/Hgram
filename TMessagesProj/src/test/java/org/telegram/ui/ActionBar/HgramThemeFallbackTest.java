package org.telegram.ui.ActionBar;

import android.app.Application;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class HgramThemeFallbackTest {

    @Test
    public void blackActionBarGetsWhiteIcon() {
        assertEquals(Color.WHITE, ThemeColors.resolveActionBarDefaultIconColor(Color.BLACK));
        assertFalse(ThemeColors.isLightColor(Color.BLACK));
    }

    @Test
    public void darkGrayActionBarGetsWhiteIcon() {
        // AMOLED / night action bar values
        assertEquals(Color.WHITE, ThemeColors.resolveActionBarDefaultIconColor(0xFF000000));
        assertEquals(Color.WHITE, ThemeColors.resolveActionBarDefaultIconColor(0xFF1A1D21));
        assertEquals(Color.WHITE, ThemeColors.resolveActionBarDefaultIconColor(0xFF333333));
        assertFalse(ThemeColors.isLightColor(0xFF808080));
    }

    @Test
    public void whiteActionBarGetsDefaultBlackIcon() {
        assertEquals(ThemeColors.DEFAULT_BLACK_TEXT, ThemeColors.resolveActionBarDefaultIconColor(Color.WHITE));
        assertTrue(ThemeColors.isLightColor(Color.WHITE));
    }

    @Test
    public void lightActionBarGetsDefaultBlackIcon() {
        assertEquals(ThemeColors.DEFAULT_BLACK_TEXT, ThemeColors.resolveActionBarDefaultIconColor(0xFFF0F0F0));
        assertEquals(ThemeColors.DEFAULT_BLACK_TEXT, ThemeColors.resolveActionBarDefaultIconColor(0xFFEFEFEF));
        assertTrue(ThemeColors.isLightColor(0xFFF0F0F0));
    }
}
