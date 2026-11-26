package org.telegram.ui;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.Components.FilterTabsView;

import me.vkryl.android.animator.BoolAnimator;
import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.hgram.HgramDialogsClip;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34, shadows = {DialogsHeaderClipTest.LocalStrings.class, DialogsHeaderClipTest.NoJni.class})
public class DialogsHeaderClipTest {
    @org.robolectric.annotation.Implements(org.telegram.tgnet.ConnectionsManager.class)
    public static class NoJni {
        @org.robolectric.annotation.Implementation
        protected static int native_isTestBackend(int instance) {
            return 0;
        }
    }

    // Localized labels are unrelated to clip geometry. Avoid starting the account/JNI stack.
    @org.robolectric.annotation.Implements(org.telegram.messenger.LocaleController.class)
    public static class LocalStrings {
        @org.robolectric.annotation.Implementation
        protected static String getString(int resourceId) {
            return "test";
        }

        @org.robolectric.annotation.Implementation
        protected static String getString(String key, int resourceId) {
            return key;
        }

        @org.robolectric.annotation.Implementation
        protected static String getString(String key) {
            return key;
        }

        @org.robolectric.annotation.Implementation
        protected static org.telegram.messenger.LocaleController getInstance(int num) {
            return null;
        }
    }

    @Test
    public void dialogsClipCommitsAfterGeometryBeforeDownWithoutDrawing() {
        Fixture f = new Fixture(false);
        f.scroll(-24);
        int oldEdge = f.clip(f.first);
        assertTrue(oldEdge > 0);
        f.assertBoundary(f.first);

        f.first.invalidations = 0;
        f.frame(.25f, -10);
        int newEdge = f.clip(f.first);
        assertNotEquals(oldEdge, newEdge);
        assertEquals(1, f.first.invalidations);
        assertEquals(newEdge < oldEdge, f.down(f.first, Math.min(oldEdge, newEdge)));
        f.assertBoundary(f.first);
        f.frame(.25f, -10);
        assertEquals(1, f.first.invalidations);

        f.frame(.5f, -20);
        f.assertBoundary(f.first);
        f.frame(0, 0);
        assertEquals(oldEdge, f.clip(f.first));

        // The pages have different vertical origins during this transition.
        f.secondPage.setTranslationY(9);
        f.swipe(.25f);
        assertEquals(-80f, f.firstPage.getTranslationX(), 0f);
        assertEquals(240f, f.secondPage.getTranslationX(), 0f);
        assertEquals(9, f.clip(f.first) - f.clip(f.second));
        f.assertBoundary(f.first);
        f.assertBoundary(f.second);
        f.scroll(0);
        f.secondPage.setTranslationY(0);
        f.reapply();
        assertEquals(oldEdge, f.clip(f.first));
        assertEquals(oldEdge, f.clip(f.second));
        f.tabsVisible(true);
        assertEquals(View.VISIBLE, f.ensureTabs().getVisibility());
        assertTrue(f.clip(f.first) > oldEdge);
        f.assertBoundary(f.first);
        f.assertBoundary(f.second);
        f.tabsVisible(false);
        assertEquals(oldEdge, f.clip(f.first));
        f.assertBoundary(f.first);
    }

    @Test
    public void dialogsClipTracksFolderPanelReservedHeightDuringFade() {
        Fixture f = new Fixture(false);
        f.ensureTabs();
        f.first.invalidations = 0;
        int full = f.tabsFactor(1f);
        assertEquals(56 + (int) Math.ceil(f.reserveTabs(1f)), full);
        int half = f.tabsFactor(.5f);
        assertEquals(56 + (int) Math.ceil(f.reserveTabs(.5f)), half);
        assertTrue("full=" + full + " half=" + half, half < full && half > 56);
        int nearlyGone = f.tabsFactor(.02f);
        assertEquals(56 + (int) Math.ceil(f.reserveTabs(.02f)), nearlyGone);
        assertTrue(nearlyGone <= 58);
        int hidden = f.tabsFactor(0f);
        assertEquals(56, hidden);
        int invalidations = f.first.invalidations;
        assertEquals(half, f.tabsFactor(.5f));
        assertEquals(invalidations + 1, f.first.invalidations);
        int stable = f.first.invalidations;
        f.reapply();
        assertEquals(stable, f.first.invalidations);
        f.ensureBoundary(f.first, half);
        f.assertBoundary(f.first);
        f.assertBoundary(f.second);
    }

    @Test
    public void dialogsSearchTabsTranslationPublishesBeforeDraw() {
        Fixture f = new Fixture(true);
        View tabs = f.ensureSearchTabs();
        int before = f.clip(f.first);
        final int publishes = f.publishes();
        f.searchTranslation(30);
        assertEquals(publishes + 1, f.publishes());
        int moved = f.clip(f.first);
        assertTrue(moved > before);
        f.ensureBoundary(f.first, moved);
        int invalidations = f.first.invalidations;
        f.searchTranslation(30);
        assertEquals(moved, f.clip(f.first));
        assertEquals(publishes + 1, f.publishes());
        assertEquals(invalidations, f.first.invalidations);
    }

    @Test
    public void dialogsSearchPagerDirectTranslationPublishesAfterPanGeometry() {
        Fixture f = new Fixture(true);
        f.ensureSearchTabs();
        f.reapply();
        f.firstPage.setTranslationY(12);
        int publishes = f.publishes();
        f.directPagerTranslation(40);
        assertEquals(publishes + 1, f.publishes());
        assertEquals(134, f.clip(f.first));
    }

    @Test
    public void dialogsStoriesHeaderUsesSameEdgeForDrawAndDown() {
        Fixture f = new Fixture(true);
        f.frame(0, 0);
        assertTrue(f.clip(f.first) > f.actionBar.getHeight());
        f.assertBoundary(f.first);
        f.assertBoundary(f.second);
        assertEquals(f.headerBottom(),
                f.parentEdge(f.firstPage), 0f);
    }

    @Test
    public void dialogsClipFollowsOverscrollRowsAndReleases() {
        Fixture f = new Fixture(false);
        // Put the static padding above the header so the old code left a visible band.
        f.first.setPadding(0, 20, 0, 0);
        f.first.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(f.first.getContext()));

        final float listTop = f.firstPage.getY() + f.first.getY();
        final float scale = f.firstPage.getScaleY() * f.first.getScaleY();
        final int height = f.first.getHeight();
        final float[] overscrolls = {0f, 30.5f, 90f, -10f, Float.NaN};
        for (float overscroll : overscrolls) {
            ReflectionHelpers.setField(f.activity, "storiesOverscroll", overscroll);
            f.first.setViewsOffset(overscroll);
            assertEquals("view offset=" + overscroll, overscroll,
                    ReflectionHelpers.getStaticField(DialogsActivity.class, "viewOffset"), 0.001f);
            ReflectionHelpers.callInstanceMethod(f.first, "updateHgramClipTop",
                    from(float.class, f.headerBottom()));
            final HgramDialogsClip.Clip expected = HgramDialogsClip.resolveClipTop(
                    f.headerBottom(), listTop, scale, height, 20, overscroll);
            assertEquals("clip overscroll=" + overscroll, expected.clipTop, f.clip(f.first));
        }

        // Release: the animator returns overscroll to zero and the band must not reappear.
        ReflectionHelpers.setField(f.activity, "storiesOverscroll", 0f);
        f.first.setViewsOffset(0f);
        ReflectionHelpers.callInstanceMethod(f.first, "updateHgramClipTop",
                from(float.class, f.headerBottom()));
        assertEquals(20, f.clip(f.first));
    }

    private static final class Fixture {
        final DialogsActivity activity;
        final android.widget.FrameLayout root;
        final ActionBar actionBar;
        final DialogsActivity.ViewPage firstPage, secondPage;
        final CountingList first, second;

        Fixture(boolean stories) {
            Context context = ApplicationProvider.getApplicationContext();
            ApplicationLoader.applicationContext = context;
            AndroidUtilities.density = 1;
            NekoConfig.disablePullDownSearch.setConfigBool(true);
            activity = new DialogsActivity(new Bundle());
            // This fixture exercises input before drawing; do not capture a blur display list.
            ReflectionHelpers.setField(activity, "scrollableViewNoiseSuppressor", null);
            Class<? extends android.widget.FrameLayout> rootClass;
            try {
                rootClass = Class.forName(DialogsActivity.class.getName() + '\u0024' + "ContentView")
                        .asSubclass(android.widget.FrameLayout.class);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
            root = ReflectionHelpers.callConstructor(rootClass,
                    from(DialogsActivity.class, activity), from(Context.class, context));
            actionBar = new ActionBar(context);
            actionBar.layout(0, 0, 320, 56);
            ReflectionHelpers.setField(activity, "fragmentView", root);
            ReflectionHelpers.setField(activity, "actionBar", actionBar);
            ReflectionHelpers.setField(activity, "hasStories", stories);
            ReflectionHelpers.setField(activity, "rightSlidingDialogContainer", new RightSlidingDialogContainer(context) {
                @Override
                boolean getOccupyStatusbar() {
                    return false;
                }
            });
            firstPage = activity.new ViewPage(context);
            secondPage = activity.new ViewPage(context);
            ReflectionHelpers.callInstanceMethod(firstPage, "setMeasuredDimension",
                    from(int.class, 320), from(int.class, 500));
            ReflectionHelpers.setField(activity, "animatingForward", true);
            first = new CountingList(context, activity, firstPage);
            second = new CountingList(context, activity, secondPage);
            // Real list carries the header height as padding; keep the synthetic geometry above it.
            first.setPadding(0, 200, 0, 0);
            second.setPadding(0, 200, 0, 0);
            firstPage.listView = first;
            secondPage.listView = second;
            firstPage.addView(first);
            secondPage.addView(second);
            root.addView(firstPage);
            root.addView(secondPage);
            root.addView(actionBar);
            first.setBottom(500);
            second.setBottom(500);
            ReflectionHelpers.setField(activity, "viewPages",
                    new DialogsActivity.ViewPage[]{firstPage, secondPage});
        }

        void scroll(float offset) {
            ReflectionHelpers.callInstanceMethod(activity, "setScrollY", from(float.class, offset));
        }

        void frame(float progress, float translationY) {
            ReflectionHelpers.callInstanceMethod(activity, "applyActionModeHeaderFrame",
                    from(float.class, progress), from(float.class, translationY));
        }

        void swipe(float progress) {
            ReflectionHelpers.callInstanceMethod(activity, "applyFolderSwipeHeaderFrame", from(float.class, progress));
        }

        void tabsVisible(boolean visible) {
            FilterTabsView tabs = ensureTabs();
            BoolAnimator animator = ReflectionHelpers.getField(activity, "animatorFilterTabsVisible");
            animator.setValue(visible, false);
        }

        FilterTabsView ensureTabs() {
            FilterTabsView tabs = ReflectionHelpers.getField(activity, "filterTabsView");
            if (tabs == null) {
                tabs = new FilterTabsView(root.getContext(), null);
                root.addView(tabs);
                tabs.layout(0, 56, 320, 106);
                ReflectionHelpers.setField(activity, "filterTabsView", tabs);
            }
            return tabs;
        }

        int tabsFactor(float factor) {
            ensureTabs();
            BoolAnimator animator = ReflectionHelpers.getField(activity, "animatorFilterTabsVisible");
            if (factor > 0) {
                animator.forceValue(true, factor);
            } else {
                animator.setValue(false, false);
            }
            reapply();
            return clip(first);
        }

        View ensureSearchTabs() {
            SearchTabsAndFiltersLayout tabs = ReflectionHelpers.getField(activity, "searchTabsAndFiltersLayout");
            if (tabs == null) {
                tabs = new SearchTabsAndFiltersLayout(root.getContext());
                root.addView(tabs);
                tabs.layout(0, 56, 320, 106);
                ReflectionHelpers.setField(activity, "searchTabsAndFiltersLayout", tabs);
            }
            return tabs;
        }

        void searchTranslation(float value) {
            ReflectionHelpers.callInstanceMethod(activity.SEARCH_TRANSLATION_Y, "setValue",
                    from(View.class, ensureSearchTabs()), from(float.class, value));
        }

        int publishes() {
            return ReflectionHelpers.getField(activity, "hgramHeaderPublishCount");
        }

        void checkSearchFilters() {
            ReflectionHelpers.callInstanceMethod(activity, "checkUi_searchFiltersVisibility");
        }

        float reserveTabs(float factor) {
            return ReflectionHelpers.callInstanceMethod(activity, "reservedFilterTabsHeight",
                    from(float.class, factor));
        }

        void directPagerTranslation(float value) {
            ReflectionHelpers.callInstanceMethod(activity, "onSearchPagerTranslation",
                    from(float.class, value));
        }

        void reapply() {
            ReflectionHelpers.callInstanceMethod(activity, "applyHeaderGeometryAndCommitClips");
        }

        void ensureBoundary(CountingList list, int edge) {
            assertFalse(down(list, edge - 1));
            assertTrue(down(list, edge));
        }

        int clip(CountingList list) {
            HgramDialogsClip.Cache cache = ReflectionHelpers.getField(list, "hgramClipCache");
            return cache.clipTop;
        }

        int headerBottom() {
            int top = ReflectionHelpers.callInstanceMethod(root, "getActionBarTop");
            int height = ReflectionHelpers.callInstanceMethod(root, "getActionBarFullHeight");
            return top + height;
        }

        float parentEdge(DialogsActivity.ViewPage page) {
            return ReflectionHelpers.callInstanceMethod(root, "getHgramPageClipTop",
                    from(DialogsActivity.ViewPage.class, page));
        }

        boolean down(CountingList list, float y) {
            MotionEvent event = MotionEvent.obtain(1, 1, MotionEvent.ACTION_DOWN, 1, y, 0);
            try {
                return list.dispatchTouchEvent(event);
            } finally {
                event.recycle();
            }
        }

        void assertBoundary(CountingList list) {
            int edge = clip(list);
            DialogsActivity.ViewPage page = (DialogsActivity.ViewPage) list.getParent();
            assertEquals(page.getY() + list.getY() + edge, parentEdge(page), 0f);
            assertFalse(down(list, edge - 1));
            assertTrue(down(list, edge));
        }
    }

    private static final class CountingList extends DialogsActivity.DialogsRecyclerView {
        int invalidations;

        CountingList(Context context, DialogsActivity activity, DialogsActivity.ViewPage page) {
            activity.super(context, page);
            setOnTouchListener((v, event) -> true);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return false; // Fixture isolates the DOWN gate from item-animator bookkeeping.
        }

        @Override
        public void invalidate() {
            super.invalidate();
            invalidations++;
        }
    }
}
