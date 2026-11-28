package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Context;
import android.os.Vibrator;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowVibrator;
import org.telegram.messenger.ApplicationLoader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramHapticsDefaultOffTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
    }

    @Test
    public void defaultSettingsDoNotVibrateListItems() {
        assertFalse(HgramConfig.isHapticEnabled());
        final Context context = ApplicationProvider.getApplicationContext();
        final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        final ShadowVibrator shadow = Shadows.shadowOf(vibrator);
        shadow.setHasVibrator(true);
        vibrator.cancel();

        HgramHaptics.play(HgramHapticProfile.PLACE_LIST_ITEMS);
        HgramHaptics.play(HgramHapticProfile.PLACE_MESSAGE_ACTIONS);

        assertFalse(shadow.isVibrating());
    }

    @Test
    public void explicitOnEnablesHapticPath() {
        HgramConfig.setHapticEnabled(true);
        assertTrue(HgramConfig.isHapticEnabled());
        assertTrue(HgramHaptics.isEnabled());
    }
}
