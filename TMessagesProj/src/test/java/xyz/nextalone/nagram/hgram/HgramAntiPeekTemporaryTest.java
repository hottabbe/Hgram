package xyz.nextalone.nagram.hgram;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramAntiPeekTemporaryTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
        HgramConfig.setAntiPeekGestureEnabled(true);
        HgramAntiPeek.setActive(true);
    }

    @After
    public void tearDown() {
        HgramAntiPeek.cancelTemporaryReveal(false);
        HgramAntiPeek.setActive(false);
        HgramConfig.reset();
    }

    @Test
    public void disablingMaskDuringRevealCancelsRestore() {
        HgramAntiPeek.showTemporarily(5000L, null);
        assertFalse(HgramAntiPeek.isActive());
        HgramAntiPeek.setActive(false);
        HgramAntiPeek.runTemporaryRestoreNowForTest();
        assertFalse(HgramAntiPeek.isActive());
        assertFalse(HgramAntiPeek.isTemporaryRevealActive());
    }

    @Test
    public void revealRestoresWhenStillAllowed() {
        HgramAntiPeek.showTemporarily(5000L, null);
        assertFalse(HgramAntiPeek.isActive());
        HgramAntiPeek.runTemporaryRestoreNowForTest();
        assertTrue(HgramAntiPeek.isActive());
        assertFalse(HgramAntiPeek.isTemporaryRevealActive());
    }

    @Test
    public void gestureToggleDuringRevealCancelsRestore() {
        HgramAntiPeek.showTemporarily(5000L, null);
        assertFalse(HgramAntiPeek.isActive());
        // The gesture calls toggle(): during a reveal it enables the mask and cancels the timer.
        HgramAntiPeek.toggle();
        assertTrue(HgramAntiPeek.isActive());
        assertFalse(HgramAntiPeek.isTemporaryRevealActive());
        HgramAntiPeek.runTemporaryRestoreNowForTest();
        assertTrue(HgramAntiPeek.isActive());
    }

    @Test
    public void gestureToggleRemovesManualMask() {
        // setUp enabled the mask manually: activatedByGesture is false.
        assertTrue(HgramAntiPeek.isActive());
        HgramAntiPeek.toggle();
        assertFalse(HgramAntiPeek.isActive());
        // There is no restore path left that could bring the mask back.
        HgramAntiPeek.runTemporaryRestoreNowForTest();
        assertFalse(HgramAntiPeek.isActive());
    }

    @Test
    public void disabledGestureDoesNotRestore() {
        HgramAntiPeek.showTemporarily(5000L, null);
        HgramConfig.setAntiPeekGestureEnabled(false);
        HgramAntiPeek.stop();
        HgramAntiPeek.runTemporaryRestoreNowForTest();
        assertFalse(HgramAntiPeek.isActive());
    }
}
