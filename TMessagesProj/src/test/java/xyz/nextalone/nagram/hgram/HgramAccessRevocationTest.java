package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramAccessRevocationTest {

    @Test
    public void revocationHidesEverySurfaceImmediately() {
        Application context = ApplicationProvider.getApplicationContext();
        FrameLayout window = new FrameLayout(context);
        FrameLayout aspect = new FrameLayout(context);
        View surface = new View(context);
        View texture = new View(context);
        View pager = new View(context);

        HgramAccessRevocation.hide(window, aspect, surface, texture, pager);

        assertHidden(window);
        assertHidden(aspect);
        assertHidden(surface);
        assertHidden(texture);
        assertHidden(pager);
    }

    @Test
    public void revocationIsNullSafe() {
        HgramAccessRevocation.hide(null, null, null, null, null);
    }

    private static void assertHidden(View view) {
        assertEquals(0f, view.getAlpha(), 0f);
        assertEquals(View.INVISIBLE, view.getVisibility());
    }
}
