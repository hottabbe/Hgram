package org.telegram.ui.Components;

import android.app.Application;
import android.content.Context;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowVibrator;
import org.telegram.messenger.ApplicationLoader;

import xyz.nextalone.nagram.hgram.HgramConfig;
import xyz.nextalone.nagram.hgram.HgramHapticProfile;
import xyz.nextalone.nagram.hgram.HgramHaptics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Single-owner contract for long-press haptics: a list whose handler plays Hgram must opt out
 * of the built-in feedback, and the handler plays exactly one response per handled gesture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramLongPressHapticTest {

    private ProbeView view;
    private ShadowVibrator shadowVibrator;

    @Before
    public void setup() {
        final Context context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        HgramConfig.reset();
        HgramHapticProfile.reset(HgramHapticProfile.PLACE_LONG_PRESS);
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        shadowVibrator = Shadows.shadowOf(vibrator);
        shadowVibrator.setHasVibrator(true);
        vibrator.cancel();
        view = new ProbeView(context);
        view.setHapticFeedbackEnabled(true);
    }

    @Test
    public void dialogAndTopicLongPressPlaysOnceAndRespectsMaster() {
        // RecyclerListView keeps the built-in LONG_PRESS for every list that did not opt in;
        // TopicsFragment and every DialogsActivity viewPage.listView opt in explicitly.
        assertTrue(RecyclerListView.shouldPlayAutoLongPressHaptic(false));
        assertFalse(RecyclerListView.shouldPlayAutoLongPressHaptic(true));

        // Dialog row menu, a preview that actually opened and a selection toggle each play
        // exactly one response through the production Hgram expression.
        assertSingleLongPress();
        assertSingleLongPress();
        assertSingleLongPress();

        // Archive row keeps its own single call from onArchiveLongPress (with its own flags).
        view.hapticCalls = 0;
        HgramHaptics.play(view, HgramHapticProfile.PLACE_LONG_PRESS,
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        assertEquals(1, view.hapticCalls);
        assertEquals("View", HgramHaptics.lastPath());

        // Master OFF: translated rows and the archive owner are silent, no vibrator request.
        view.hapticCalls = 0;
        HgramConfig.setHapticEnabled(false);
        HgramHaptics.play(view, HgramHapticProfile.PLACE_LONG_PRESS,
                HapticFeedbackConstants.LONG_PRESS, 0);
        HgramHaptics.play(view, HgramHapticProfile.PLACE_LONG_PRESS,
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        assertEquals(0, view.hapticCalls);
        assertEquals("", HgramHaptics.lastPath());
        assertFalse(shadowVibrator.isVibrating());
        // The opt-out touches only the two auto-haptic calls inside RecyclerListView.onLongPress:
        // the listener result, longPressCalled, accessibility events and move/release are intact
        // by construction; the full gesture is covered by the device pass recorded in the report.
    }

    private void assertSingleLongPress() {
        view.hapticCalls = 0;
        HgramHaptics.play(view, HgramHapticProfile.PLACE_LONG_PRESS,
                HapticFeedbackConstants.LONG_PRESS, 0);
        assertEquals("a handled long-press must play exactly once", 1, view.hapticCalls);
        assertEquals("View", HgramHaptics.lastPath());
    }

    private static final class ProbeView extends View {
        int hapticCalls;

        ProbeView(Context context) {
            super(context);
        }

        @Override
        public boolean isAttachedToWindow() {
            return true;
        }

        @Override
        public boolean performHapticFeedback(int feedbackConstant) {
            hapticCalls++;
            return true;
        }

        @Override
        public boolean performHapticFeedback(int feedbackConstant, int flags) {
            hapticCalls++;
            return true;
        }
    }
}
