package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.Components.ChatScrimPopupContainerLayout;
import org.telegram.ui.Components.ReactionsContainerLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramScrimInlineMenuOverlayTest {

    @Test
    public void dismissedOverlaySwallowsTouches() throws Exception {
        Application application = ApplicationProvider.getApplicationContext();
        FrameLayout parent = new FrameLayout(application);
        ChatScrimPopupContainerLayout container =
                new ChatScrimPopupContainerLayout(application);
        final int[] downs = {0};
        View child = new View(application);
        child.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downs[0]++;
            }
            return true;
        });
        container.addView(child, new LinearLayout.LayoutParams(200, 200));

        Constructor<HgramScrimInlineMenuOverlay> ctor =
                HgramScrimInlineMenuOverlay.class.getDeclaredConstructor(
                        android.content.Context.class,
                        ChatScrimPopupContainerLayout.class,
                        ReactionsContainerLayout.class,
                        ActionBarMenuSubItem[].class,
                        Runnable.class,
                        HgramScrimInlineMenuOverlay.DismissListener.class);
        ctor.setAccessible(true);
        HgramScrimInlineMenuOverlay overlay = ctor.newInstance(
                application, container, null, null, (Runnable) () -> { }, null);

        overlay.addView(container, new FrameLayout.LayoutParams(200, 200));
        parent.addView(overlay, new FrameLayout.LayoutParams(400, 400));
        parent.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, 400, 400);
        overlay.layout(0, 0, 400, 400);
        container.layout(0, 0, 200, 200);

        MotionEvent before = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 50, 0);
        overlay.dispatchTouchEvent(before);
        before.recycle();
        assertEquals(1, downs[0]);

        Field dismissed = HgramScrimInlineMenuOverlay.class.getDeclaredField("dismissed");
        dismissed.setAccessible(true);
        dismissed.setBoolean(overlay, true);

        MotionEvent after = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50, 50, 0);
        assertTrue(overlay.dispatchTouchEvent(after));
        after.recycle();
        assertEquals(1, downs[0]);
    }
}
