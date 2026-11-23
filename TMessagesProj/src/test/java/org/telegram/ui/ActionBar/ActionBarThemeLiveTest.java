package org.telegram.ui.ActionBar;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;

import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.DialogsHeaderClipTest;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34,
        shadows = {DialogsHeaderClipTest.NoJni.class})
public class ActionBarThemeLiveTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
    }

    private static int filterColor(ActionBar bar) {
        return bar.getVisibleBackButtonTintForTests();
    }

    private static void refresh(ActionBar bar) {
        ReflectionHelpers.callInstanceMethod(bar, "refreshThemeItemsColors");
    }

    private static String sourceName(ActionBar bar, String field) {
        final Object value = ReflectionHelpers.getField(bar, field);
        return value == null ? null : value.toString();
    }

    private static void searchProgress(DialogsActivity dialogs, float progress) {
        try {
            final Method method = DialogsActivity.class.getDeclaredMethod(
                    "setSearchAnimationProgress", float.class, boolean.class);
            method.setAccessible(true);
            method.invoke(dialogs, progress, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ActionBar bar(Context context, Map<Integer, Integer> palette) {
        final Theme.ResourcesProvider provider = key ->
                palette.getOrDefault(key, 0xFF000000);
        final ActionBar[] result = new ActionBar[1];
        final BaseFragment fragment = new BaseFragment() {
            @Override
            public Theme.ResourcesProvider getResourceProvider() {
                return provider;
            }
        };
        result[0] = fragment.createActionBar(context);
        result[0].setBackButtonDrawable(new BitmapDrawable(context.getResources(),
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
        return result[0];
    }

    @Test
    public void baseFragmentHeaderTracksTwoThemeChangesAndAccentWithoutDescription() {
        final Context context = Robolectric.buildActivity(Activity.class).setup().get();
        final Map<Integer, Integer> palette = new HashMap<>();
        palette.put(Theme.key_actionBarDefaultIcon, 0xFF111111);
        palette.put(Theme.key_actionBarActionModeDefaultIcon, 0xFF222222);
        final ActionBar bar = bar(context, palette);

        assertEquals(0xFF111111, filterColor(bar));
        palette.put(Theme.key_actionBarDefaultIcon, 0xFF333333);
        refresh(bar);
        assertEquals(0xFF333333, filterColor(bar));
        palette.put(Theme.key_actionBarDefaultIcon, 0xFF444444);
        refresh(bar);
        assertEquals(0xFF444444, filterColor(bar));
    }

    @Test
    public void themeDescriptionKeepsArchivedKeyAndDoesNotReplaceExplicitReportTint() {
        final Context context = Robolectric.buildActivity(Activity.class).setup().get();
        final Map<Integer, Integer> palette = new HashMap<>();
        palette.put(Theme.key_actionBarDefaultIcon, 0xFF111111);
        palette.put(Theme.key_actionBarDefaultArchivedIcon, 0xFFAAAAAA);
        final ActionBar bar = bar(context, palette);
        final ThemeDescription archived = new ThemeDescription(bar,
                ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null,
                Theme.key_actionBarDefaultArchivedIcon);

        archived.setColor(0xFFBBBBBB, false, false);
        assertEquals(0xFFBBBBBB, filterColor(bar));
        palette.put(Theme.key_actionBarDefaultArchivedIcon, 0xFFCCCCCC);
        refresh(bar);
        assertEquals("archived description key must stay archived", 0xFFCCCCCC, filterColor(bar));

        bar.setItemsColor(0xFFFFFFFF, false);
        final ThemeDescription normal = new ThemeDescription(bar,
                ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon);
        normal.setColor(0xFF0000FF, false, false);
        refresh(bar);
        assertEquals("explicit report tint must survive description + refresh",
                0xFFFFFFFF, filterColor(bar));

        bar.setItemsColor(0xFF00FF00, false);
        assertEquals("later explicit tint must still apply", 0xFF00FF00, filterColor(bar));
    }

    @Test
    public void sharedVectorTintFollowsShowHideAndLiveUpdates() {
        final Context context = Robolectric.buildActivity(Activity.class).setup().get();
        final Map<Integer, Integer> palette = new HashMap<>();
        palette.put(Theme.key_actionBarDefaultIcon, 0xFF111111);
        palette.put(Theme.key_actionBarActionModeDefaultIcon, 0xFF222222);
        final ActionBar bar = bar(context, palette);
        bar.createActionMode();

        bar.showActionMode(false);
        assertEquals(0xFF222222, filterColor(bar));
        bar.hideActionMode();
        assertEquals(0xFF111111, filterColor(bar));

        bar.setSharedBackButtonActionModeVisible(true);
        assertEquals(0xFF222222, filterColor(bar));
        bar.setItemsColor(0xFF333333, false);
        assertEquals("normal update must not repaint visible action tint",
                0xFF222222, filterColor(bar));
        bar.setItemsColor(0xFF444444, true);
        assertEquals(0xFF444444, filterColor(bar));
        bar.setSharedBackButtonActionModeVisible(false);
        assertEquals("hide must restore the last normal tint immediately",
                0xFF333333, filterColor(bar));
    }

    @Test
    public void dialogsSearchCloseRestoresThemeOwnedArchiveTintAndSelector() {
        final Context context = Robolectric.buildActivity(Activity.class).setup().get();
        final Map<Integer, Integer> palette = new HashMap<>();
        palette.put(Theme.key_actionBarDefaultIcon, 0xFF111111);
        palette.put(Theme.key_actionBarDefaultArchivedIcon, 0xFFAAAAAA);
        palette.put(Theme.key_actionBarActionModeDefaultIcon, 0xFF222222);
        palette.put(Theme.key_actionBarDefaultSelector, 0xFF010101);
        palette.put(Theme.key_actionBarDefaultArchivedSelector, 0xFF020202);
        palette.put(Theme.key_actionBarActionModeDefaultSelector, 0xFF030303);
        final Theme.ResourcesProvider provider = key ->
                palette.getOrDefault(key, 0xFF000000);
        final ActionBar bar = bar(context, palette);
        final DialogsActivity dialogs = new DialogsActivity(new Bundle()) {
            @Override
            public Theme.ResourcesProvider getResourceProvider() {
                return provider;
            }
        };
        ReflectionHelpers.setField(dialogs, "actionBar", bar);
        ReflectionHelpers.setField(dialogs, "folderId", 1);
        ReflectionHelpers.setField(dialogs, "whiteActionBar", true);
        ReflectionHelpers.setField(dialogs, "searchIsShowed", true);
        ReflectionHelpers.setField(dialogs, "searchActionBarColorOwned", true);

        searchProgress(dialogs, 0.4f);
        assertEquals("EXPLICIT", sourceName(bar, "itemsColorSource"));
        assertEquals("EXPLICIT",
                sourceName(bar, "itemsActionModeColorSource"));

        // Animated close order: whiteActionBar is cleared before the final frame.
        ReflectionHelpers.setField(dialogs, "whiteActionBar", false);
        ReflectionHelpers.setField(dialogs, "searchIsShowed", false);
        searchProgress(dialogs, 0f);
        assertEquals("THEME", sourceName(bar, "itemsColorSource"));
        assertEquals(Theme.key_actionBarDefaultArchivedIcon,
                (int) ReflectionHelpers.getField(bar, "itemsColorThemeKey"));
        assertEquals("THEME",
                sourceName(bar, "itemsActionModeColorSource"));
        assertEquals(Theme.key_actionBarActionModeDefaultIcon,
                (int) ReflectionHelpers.getField(bar, "itemsActionModeColorThemeKey"));
        assertEquals(0xFFAAAAAA, filterColor(bar));
        assertEquals("animated close must restore the archived selector",
                0xFF020202, bar.itemsBackgroundColor);

        palette.put(Theme.key_actionBarDefaultArchivedIcon, 0xFFBBBBBB);
        refresh(bar);
        assertEquals("archived theme key must follow the first change", 0xFFBBBBBB, filterColor(bar));
        palette.put(Theme.key_actionBarDefaultArchivedIcon, 0xFFCCCCCC);
        palette.put(Theme.key_actionBarDefaultArchivedSelector, 0xFF121212);
        refresh(bar);
        assertEquals("archived theme key must follow the accent change", 0xFFCCCCCC, filterColor(bar));

        // Instant close leaves whiteActionBar true and has no final animation frame.
        ReflectionHelpers.setField(dialogs, "whiteActionBar", true);
        ReflectionHelpers.setField(dialogs, "searchIsShowed", false);
        ReflectionHelpers.setField(dialogs, "searchActionBarColorOwned", true);
        bar.setItemsColor(0xFF123456, false);
        searchProgress(dialogs, 0f);
        assertEquals("THEME", sourceName(bar, "itemsColorSource"));
        assertEquals(Theme.key_actionBarDefaultArchivedIcon,
                (int) ReflectionHelpers.getField(bar, "itemsColorThemeKey"));
        assertEquals(0xFFCCCCCC, filterColor(bar));
        assertEquals("instant close must restore the new archived selector",
                0xFF121212, bar.itemsBackgroundColor);

        // Reopening search must use the current palette and become an explicit blend again.
        ReflectionHelpers.setField(dialogs, "whiteActionBar", true);
        ReflectionHelpers.setField(dialogs, "searchIsShowed", true);
        ReflectionHelpers.setField(dialogs, "searchActionBarColorOwned", true);
        searchProgress(dialogs, 0.4f);
        assertEquals("EXPLICIT", sourceName(bar, "itemsColorSource"));
        assertEquals(ColorUtils.blendARGB(0xFF121212, 0xFF030303, 0.4f),
                bar.itemsBackgroundColor);
    }
}
