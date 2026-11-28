package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramScrimTouchPolicyTest {

    @Test
    public void inlineMenuKeepsContentTouchPath() {
        assertTrue(HgramScrimTouchPolicy.allowContentDispatch(true, true, false));
    }

    @Test
    public void popupScrimStillBlocksContentTouchPath() {
        assertFalse(HgramScrimTouchPolicy.allowContentDispatch(false, true, false));
    }

    @Test
    public void backButtonAndNoScrimAreAllowed() {
        assertTrue(HgramScrimTouchPolicy.allowContentDispatch(false, true, true));
        assertTrue(HgramScrimTouchPolicy.allowContentDispatch(false, false, false));
    }

    @Test
    public void menuHitTestAccountsForTranslation() {
        Application application = ApplicationProvider.getApplicationContext();
        View menu = new View(application);
        menu.layout(100, 200, 300, 400);
        menu.setTranslationX(10);
        menu.setTranslationY(20);

        assertTrue(HgramScrimInlineMenuOverlay.isInsideMenu(menu, 150, 250));
        assertTrue(HgramScrimInlineMenuOverlay.isInsideMenu(menu, 110, 220));
        assertFalse(HgramScrimInlineMenuOverlay.isInsideMenu(menu, 105, 250));
        assertFalse(HgramScrimInlineMenuOverlay.isInsideMenu(menu, 150, 215));
    }
}
