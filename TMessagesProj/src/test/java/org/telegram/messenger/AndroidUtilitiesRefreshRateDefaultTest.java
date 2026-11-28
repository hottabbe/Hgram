package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.view.Window;
import android.view.WindowManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.utils.Choreographer60FpsContent;
import xyz.nextalone.nagram.hgram.HgramConfig;
import xyz.nextalone.nagram.hgram.HgramRefreshRatePolicy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class AndroidUtilitiesRefreshRateDefaultTest {
    // Robolectric exposes one 60 Hz display; alternate 120 Hz modes need a device.

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
        Choreographer60FpsContent.setTargetFps(60);
    }

    @Test
    public void offKeepsUpstreamDisplayModeAndFrameCap() {
        HgramConfig.setHighRefreshBoostEnabled(false);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Window window = activity.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.preferredDisplayModeId = 777;
        window.setAttributes(params);
        installRecorder(window);

        AndroidUtilities.setPreferredMaxRefreshRate(window, 120f);

        assertEquals(0, window.getAttributes().preferredDisplayModeId);
        assertEquals(60, Choreographer60FpsContent.getTargetFps());
    }

    @Test
    public void onAtMaxRateUsesPinnedModeSelection() {
        int pinned = HgramRefreshRatePolicy.pickPinnedModeId(
                new int[]{1, 2}, new int[]{100, 100}, new int[]{100, 100},
                new float[]{60f, 120f}, 100, 100);
        assertEquals(2, pinned);
        HgramRefreshRatePolicy.Result result = HgramRefreshRatePolicy.resolve(
                true, false, 0, pinned, 120f, true, 60f);
        assertEquals(2, result.preferredDisplayModeId);
        assertEquals(120, result.targetFps);
        assertTrue(result.updateLayout);
        assertEquals(120f, result.frameIntervalHz, 0.01f);
    }

    @Test
    public void onThenOffRestoresWindowStateAndInterval() {
        HgramConfig.setHighRefreshBoostEnabled(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Window window = activity.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.preferredRefreshRate = 120f;
        params.preferredDisplayModeId = 0;
        window.setAttributes(params);
        RecordingWindowManager recorder = installRecorder(window);
        AndroidUtilities.setPreferredMaxRefreshRate(window, 120f);

        HgramConfig.setHighRefreshBoostEnabled(false);
        AndroidUtilities.setPreferredMaxRefreshRate(window, 60f);

        assertEquals(0, window.getAttributes().preferredDisplayModeId);
        assertEquals(60, Choreographer60FpsContent.getTargetFps());
        assertTrue(recorder.updates.get() > 0);
    }

    @Test
    public void defaultOffDoesNotRewriteFrameInterval() {
        HgramConfig.setHighRefreshBoostEnabled(false);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Window window = activity.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.preferredDisplayModeId = 0;
        params.preferredRefreshRate = 120f;
        window.setAttributes(params);
        installRecorder(window);
        AndroidUtilities.setPreferredMaxRefreshRate(window, 120f);

        assertEquals(60, Choreographer60FpsContent.getTargetFps());
    }

    private static RecordingWindowManager installRecorder(Window window) {
        RecordingWindowManager recorder = new RecordingWindowManager(window);
        WindowManager proxy = (WindowManager) Proxy.newProxyInstance(
                AndroidUtilitiesRefreshRateDefaultTest.class.getClassLoader(),
                new Class[]{WindowManager.class}, recorder);
        org.robolectric.util.ReflectionHelpers.setField(window, "mWindowManager", proxy);
        return recorder;
    }

    private static final class RecordingWindowManager implements InvocationHandler {
        final AtomicInteger updates = new AtomicInteger();
        final AtomicReference<WindowManager.LayoutParams> last = new AtomicReference<>();
        private final Window window;

        RecordingWindowManager(Window window) {
            this.window = window;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("updateViewLayout".equals(method.getName())) {
                updates.incrementAndGet();
                if (args != null && args.length > 1 && args[1] instanceof WindowManager.LayoutParams) {
                    last.set((WindowManager.LayoutParams) args[1]);
                }
                return null;
            }
            if ("getDefaultDisplay".equals(method.getName())) {
                return window.getDecorView().getDisplay();
            }
            return null;
        }
    }
}
