package org.telegram.ui.ActionBar;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActionBarThemeIconTest {

    @Test
    public void themeOwnedItemsColorFollowsThemeChange() {
        final int oldTheme = 0xFF010203;
        final int newTheme = 0xFF070809;

        assertEquals("a value still equal to the old theme is theme-owned",
                newTheme, ActionBar.resolveThemeItemsColor(oldTheme, oldTheme, newTheme));
        assertEquals("an unset value is theme-owned",
                newTheme, ActionBar.resolveThemeItemsColor(0, oldTheme, newTheme));
    }

    @Test
    public void explicitItemsColorIsPreserved() {
        final int explicit = 0xFF123456;
        final int oldTheme = 0xFF010203;
        final int newTheme = 0xFF070809;

        assertEquals("ProfileActivity/ChatActivity report tint must survive",
                explicit, ActionBar.resolveThemeItemsColor(explicit, oldTheme, newTheme));
    }

    @Test
    public void menuDrawableResolvesThemeOnlyWhenColorUnset() {
        assertEquals(0xFF00FF00, MenuDrawable.resolveIconColor(0xFF00FF00, 0xFF000000));
        assertEquals(0xFF000000, MenuDrawable.resolveIconColor(0, 0xFF000000));
    }

    @Test
    public void explicitColorMatchingOldThemeIsNotOverwritten() {
        final int whiteTint = 0xFFFFFFFF;
        final int newTheme = 0xFF070809;
        assertEquals("Chat report/Profile blend must stay explicit",
                whiteTint, ActionBar.resolveThemeItemsColor(
                        whiteTint, ActionBar.ItemsColorSource.EXPLICIT, newTheme));
        assertTrue(ActionBar.isThemeOwned(ActionBar.ItemsColorSource.EXPLICIT, 0));
        assertEquals("an unset color still follows the theme", newTheme,
                ActionBar.resolveThemeItemsColor(
                        0, ActionBar.ItemsColorSource.EXPLICIT, newTheme));
    }

    @Test
    public void themeColorFollowsRepeatedThemeAndAccentChanges() {
        final int first = 0xFF111111;
        final int second = 0xFF222222;
        final int accent = 0xFF333333;
        assertEquals(first, ActionBar.resolveThemeItemsColor(
                0, ActionBar.ItemsColorSource.THEME, first));
        assertEquals(second, ActionBar.resolveThemeItemsColor(
                first, ActionBar.ItemsColorSource.THEME, second));
        assertEquals(accent, ActionBar.resolveThemeItemsColor(
                second, ActionBar.ItemsColorSource.THEME, accent));
    }

    @Test
    public void qrGetsActionModeTintOnlyInActionMode() {
        assertFalse(ActionBar.shouldApplyActionModeBackTint(false, false));
        assertFalse(ActionBar.shouldApplyActionModeBackTint(false, true));
        assertFalse(ActionBar.shouldApplyActionModeBackTint(true, false));
        assertTrue(ActionBar.shouldApplyActionModeBackTint(true, true));
    }
}
