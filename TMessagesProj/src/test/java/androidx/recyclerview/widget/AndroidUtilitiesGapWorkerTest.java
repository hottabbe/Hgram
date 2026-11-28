package androidx.recyclerview.widget;

import android.app.Activity;
import android.app.Application;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.utils.Choreographer60FpsContent;
import xyz.nextalone.nagram.hgram.HgramConfig;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class AndroidUtilitiesGapWorkerTest {

    private GapWorker worker;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
        Choreographer60FpsContent.setTargetFps(60);
        worker = new GapWorker();
        worker.mFrameIntervalNs = 123456789L;
        GapWorker.sGapWorker.set(worker);
    }

    // Robolectric has one 60 Hz display; alternate 120 Hz display modes are unavailable.
    @Test
    public void onThenOffResetsGapWorkerToDisplayInterval() {
        HgramConfig.setHighRefreshBoostEnabled(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Window window = activity.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.preferredRefreshRate = 120f;
        window.setAttributes(params);
        Display display = window.getDecorView().getDisplay();
        Shadows.shadowOf(display).setRefreshRate(90f);
        AndroidUtilities.setPreferredMaxRefreshRate(window, 120f);
        assertEquals((long) (1000000000 / 120f), worker.mFrameIntervalNs);

        HgramConfig.setHighRefreshBoostEnabled(false);
        // 90 != 60 so a hard-coded 60 fallback cannot pass this assertion.
        AndroidUtilities.setPreferredMaxRefreshRate(window, 90f);
        assertEquals((long) (1000000000 / 90f), worker.mFrameIntervalNs);
        Shadows.shadowOf(display).setRefreshRate(60f);
    }

    @Test
    public void offToOffDoesNotRewriteGapWorkerInterval() {
        HgramConfig.setHighRefreshBoostEnabled(false);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Window window = activity.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.preferredRefreshRate = 120f;
        window.setAttributes(params);
        AndroidUtilities.setPreferredMaxRefreshRate(window, 60f);
        assertEquals(123456789L, worker.mFrameIntervalNs);
    }
}
