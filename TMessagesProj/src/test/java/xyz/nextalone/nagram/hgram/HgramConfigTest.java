package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramConfigTest {
    @Before
    public void setup() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
    }

    @Test
    public void defaultsKeepUpstreamScreenshotAndNotificationBehavior() {
        assertFalse(HgramConfig.isAllowScreenshotsEnabled());
        assertFalse(HgramConfig.isNotificationImportanceBoostEnabled());
    }

    @Test
    public void resetAlsoLeavesPrivateMode() {
        HgramConfig.setAntiPeekGestureEnabled(true);
        HgramConfig.setAntiPeekActive(true);
        HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_SHERPA);
        HgramConfig.reset();
        assertFalse(HgramConfig.isAntiPeekActive());
        assertFalse(HgramConfig.isAntiPeekGestureEnabled());
        assertEquals(HgramConfig.STT_ENGINE_VOSK, HgramConfig.getSttEngine());
        assertFalse(HgramConfig.areAppUpdatesEnabled());
    }

    @Test
    public void voskKeepsAllFinalizedSegments() throws Exception {
        StringBuilder text = new StringBuilder();
        HgramOfflineTranscriber.appendResult(text, "{\"text\":\"first sentence\"}");
        HgramOfflineTranscriber.appendResult(text, "{\"text\":\"\"}");
        HgramOfflineTranscriber.appendResult(text, "{\"text\":\"second sentence\"}");
        assertEquals("first sentence second sentence", text.toString());
    }

    @Test public void downloadThreadsAreClampedAndReset() {
        HgramConfig.setDownloadThreads(-20);
        assertEquals(1, HgramConfig.getDownloadThreads());
        HgramConfig.setDownloadThreads(99);
        assertEquals(16, HgramConfig.getDownloadThreads());
        HgramConfig.reset();
        assertEquals(4, HgramConfig.getDownloadThreads());
    }

    @Test public void gigaAmEngineIsPersistedAlongsideOtherEngines() {
        HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_GIGAAM);
        assertEquals(HgramConfig.STT_ENGINE_GIGAAM, HgramConfig.getSttEngine());
        HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_SHERPA);
        assertEquals(HgramConfig.STT_ENGINE_SHERPA, HgramConfig.getSttEngine());
        HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_VOSK);
        assertEquals(HgramConfig.STT_ENGINE_VOSK, HgramConfig.getSttEngine());
    }

    @Test public void antiPeekSensitivityIsPersistedAndSanitized() {
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM, HgramConfig.getAntiPeekSensitivity());
        HgramConfig.setAntiPeekSensitivity(HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH);
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH, HgramConfig.getAntiPeekSensitivity());
        HgramConfig.setAntiPeekSensitivity(99);
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM, HgramConfig.getAntiPeekSensitivity());
        HgramConfig.setAntiPeekSensitivity(HgramConfig.ANTI_PEEK_SENSITIVITY_LOW);
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_LOW, HgramConfig.getAntiPeekSensitivity());
    }

    @Test public void antiPeekHoldWindowDefaultsAndSanitizes() {
        assertEquals(200, HgramConfig.getAntiPeekHoldMinMs());
        assertEquals(1200, HgramConfig.getAntiPeekHoldMaxMs());
        HgramConfig.setAntiPeekHoldWindow(400, 1600);
        assertEquals(400, HgramConfig.getAntiPeekHoldMinMs());
        assertEquals(1600, HgramConfig.getAntiPeekHoldMaxMs());
        HgramConfig.setAntiPeekHoldWindow(50, 1200);
        assertEquals(200, HgramConfig.getAntiPeekHoldMinMs());
        assertEquals(1200, HgramConfig.getAntiPeekHoldMaxMs());
        HgramConfig.setAntiPeekHoldWindow(800, 800);
        assertEquals(200, HgramConfig.getAntiPeekHoldMinMs());
        assertEquals(1200, HgramConfig.getAntiPeekHoldMaxMs());
        HgramConfig.setAntiPeekHoldWindow(200, 3001);
        assertEquals(200, HgramConfig.getAntiPeekHoldMinMs());
        assertEquals(1200, HgramConfig.getAntiPeekHoldMaxMs());
    }

    @Test public void antiPeekPrefsSanitizeWrongTypes() {
        final android.content.SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("hgram_settings", Context.MODE_PRIVATE);
        HgramConfig.reset();
        prefs.edit()
                .putString("anti_peek_hold_min_ms", "oops")
                .putBoolean("anti_peek_hold_max_ms", true)
                .apply();
        assertEquals(200, HgramConfig.getAntiPeekHoldMinMs());
        assertEquals(1200, HgramConfig.getAntiPeekHoldMaxMs());
        assertEquals(200, prefs.getInt("anti_peek_hold_min_ms", -1));
        assertEquals(1200, prefs.getInt("anti_peek_hold_max_ms", -1));

        HgramConfig.reset();
        prefs.edit()
                .putString("anti_peek_sensitivity", "oops")
                .putString("anti_peek_hold_min_ms", "oops")
                .apply();
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM,
                HgramConfig.getAntiPeekSensitivity());
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM,
                prefs.getInt("anti_peek_sensitivity", -1));
    }

    @Test public void antiPeekSensitivityMigratesLegacyHoldWindow() {
        final android.content.SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("hgram_settings", Context.MODE_PRIVATE);
        HgramConfig.reset();
        prefs.edit().putInt("anti_peek_hold_min_ms", 300).apply();
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH, HgramConfig.getAntiPeekSensitivity());
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH,
                prefs.getInt("anti_peek_sensitivity", -1));

        HgramConfig.reset();
        prefs.edit().putInt("anti_peek_hold_min_ms", 800).apply();
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_LOW, HgramConfig.getAntiPeekSensitivity());

        HgramConfig.reset();
        assertEquals(HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM, HgramConfig.getAntiPeekSensitivity());
    }
}
