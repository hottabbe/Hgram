package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.ui.Components.ChatScrimPopupContainerLayout;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramScrimInlineMenuVisibilityTest {
    @Test
    public void inlineOverlayIsTopmostChildAndHostsMenu() {
        Application context = ApplicationProvider.getApplicationContext();
        FrameLayout parent = new FrameLayout(context);
        parent.layout(0, 0, 1080, 2400);
        ChatScrimPopupContainerLayout container = new ChatScrimPopupContainerLayout(context);

        HgramScrimInlineMenuOverlay overlay = HgramScrimInlineMenuOverlay.show(
                parent, container, 100, 500, null, null, () -> {}, ignored -> {});

        assertTrue(parent.getChildCount() > 0);
        assertSame(overlay, parent.getChildAt(parent.getChildCount() - 1));
        assertSame(container, overlay.getChildAt(0));
        overlay.dismiss(false);
    }

    @Test
    public void translatedVisibleEdgeStaysInsideMenu() {
        Application context = ApplicationProvider.getApplicationContext();
        FrameLayout parent = new FrameLayout(context);
        ChatScrimPopupContainerLayout container = new ChatScrimPopupContainerLayout(context);
        HgramScrimInlineMenuOverlay overlay = HgramScrimInlineMenuOverlay.show(
                parent, container, 0, 0, null, null, () -> {}, ignored -> {});
        container.layout(0, 0, 200, 100);
        container.setTranslationY(-20f);

        assertTrue(HgramScrimInlineMenuOverlay.isInsideMenu(container, 10f, -10f));
        assertFalse(HgramScrimInlineMenuOverlay.isInsideMenu(container, 10f, 90f));
        overlay.dismiss(false);
    }
}
