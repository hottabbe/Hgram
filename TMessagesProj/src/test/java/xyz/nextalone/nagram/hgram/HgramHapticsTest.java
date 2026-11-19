package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.os.VibrationEffect;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramHapticsTest {

    @Before
    public void setup() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
    }

    @Test
    public void profileRoundTripAndDefaults() {
        HgramHapticProfile profile = new HgramHapticProfile(60, 30, 0, 60);
        profile.save(HgramHapticProfile.PLACE_TOGGLE);
        HgramHapticProfile loaded = HgramHapticProfile.of(HgramHapticProfile.PLACE_TOGGLE);
        assertEquals(60, loaded.intensity);
        assertEquals(30, loaded.durationMs);
        assertEquals(0, loaded.pattern);
        assertEquals(60, loaded.gapMs);
        HgramHapticProfile.reset(HgramHapticProfile.PLACE_TOGGLE);
        assertEquals(60, HgramHapticProfile.of(HgramHapticProfile.PLACE_TOGGLE).intensity);
    }

    @Test
    public void sanitizesBrokenAndOutOfRangeValues() {
        HgramHapticProfile profile = new HgramHapticProfile(-10, 1000, 9, 1);
        assertEquals(0, profile.intensity);
        assertEquals(300, profile.durationMs);
        assertEquals(2, profile.pattern);
        assertEquals(20, profile.gapMs);
        HgramConfig.preferences().edit().putString(
                HgramHapticProfile.key(HgramHapticProfile.PLACE_ALBUM), "bad,data").apply();
        assertEquals(50, HgramHapticProfile.of(HgramHapticProfile.PLACE_ALBUM).intensity);
    }

    @Test
    public void waveformMatchesSingleDoubleAndTriplePatterns() {
        HgramHapticProfile single = new HgramHapticProfile(100, 20, 0, 60);
        assertEquals("[20]", Arrays.toString(HgramHaptics.timings(single)));
        HgramHapticProfile twice = new HgramHapticProfile(100, 20, 1, 60);
        assertEquals("[20, 60, 20]", Arrays.toString(HgramHaptics.timings(twice)));
        HgramHapticProfile thrice = new HgramHapticProfile(100, 20, 2, 60);
        assertEquals("[20, 60, 20, 60, 20]", Arrays.toString(HgramHaptics.timings(thrice)));
        assertEquals(180, HgramHaptics.totalDuration(thrice));
    }

    @Test
    public void registryHasFifteenDistinctPlacesAndDefaults() {
        assertEquals(15, HgramHapticProfile.PLACES.length);
        java.util.HashSet<String> unique = new java.util.HashSet<>();
        for (String place : HgramHapticProfile.PLACES) {
            unique.add(place);
            HgramHapticProfile p = HgramHapticProfile.defaults(place);
            assertTrue(p.intensity >= 1 && p.intensity <= 255);
            assertTrue(p.durationMs >= 10 && p.durationMs <= 300);
        }
        assertEquals(15, unique.size());
        assertEquals(70, HgramHapticProfile.defaults(HgramHapticProfile.PLACE_ANTIPEEK).intensity);
        assertEquals(50, HgramHapticProfile.defaults(HgramHapticProfile.PLACE_ALBUMS_SWIPE).intensity);
    }

    @Test
    public void resetAllRestoresEveryDefault() {
        for (String place : HgramHapticProfile.PLACES) {
            new HgramHapticProfile(200, 200, 2, 150).save(place);
        }
        HgramHapticProfile.resetAll();
        for (String place : HgramHapticProfile.PLACES) {
            assertEquals(HgramHapticProfile.defaults(place).intensity,
                    HgramHapticProfile.of(place).intensity);
        }
    }

    @Test
    public void capabilitiesSelectHonestFallbacks() {
        HgramHapticCapabilities none = new HgramHapticCapabilities(33, false, false, true, true, true);
        assertFalse(none.showIntensity());
        assertFalse(none.useWaveform());
        HgramHapticCapabilities legacy = new HgramHapticCapabilities(25, true, false, false, false, true);
        assertFalse(legacy.showIntensity());
        assertTrue(legacy.showPattern() == false);
        HgramHapticCapabilities approximate = new HgramHapticCapabilities(29, true, false, true, false, true);
        assertTrue(approximate.showIntensity());
        assertFalse(approximate.exactIntensity());
        assertTrue(approximate.intensityHint() != null);
        HgramHapticCapabilities exact = new HgramHapticCapabilities(34, true, true, true, true, true);
        assertTrue(exact.exactIntensity());
        assertTrue(exact.showPattern());
    }

    @Test
    public void zeroIntensityUsesSystemDefaultAndDisabledBlocks() {
        HgramHapticProfile zero = new HgramHapticProfile(0, 20, 0, 60);
        assertEquals(VibrationEffect.DEFAULT_AMPLITUDE, HgramHaptics.amplitudes(zero, true)[0]);
        assertEquals(0, HgramHaptics.amplitudes(zero, false)[0]);
        assertTrue(HgramConfig.isHapticEnabled());
        HgramConfig.setHapticEnabled(false);
        assertFalse(HgramConfig.isHapticEnabled());
        HgramConfig.reset();
        assertTrue(HgramConfig.isHapticEnabled());
    }

    @Test
    public void legacyPatternKeepsPulsesAndGaps() {
        HgramHapticProfile single = new HgramHapticProfile(100, 20, 0, 60);
        assertEquals("[0, 20]", Arrays.toString(HgramHaptics.legacyPattern(single)));
        HgramHapticProfile twice = new HgramHapticProfile(100, 20, 1, 60);
        assertEquals("[0, 20, 60, 20]", Arrays.toString(HgramHaptics.legacyPattern(twice)));
        HgramHapticProfile thrice = new HgramHapticProfile(100, 20, 2, 60);
        assertEquals("[0, 20, 60, 20, 60, 20]",
                Arrays.toString(HgramHaptics.legacyPattern(thrice)));
    }

    @Test
    public void playbackAmplitudesFollowHardwareCapability() {
        HgramHapticProfile profile = new HgramHapticProfile(80, 20, 1, 60);
        assertEquals("[80, 0, 80]", Arrays.toString(
                HgramHaptics.amplitudesForPlayback(profile, true)));
        assertEquals("[-1, 0, -1]", Arrays.toString(
                HgramHaptics.amplitudesForPlayback(profile, false)));
        assertTrue(HgramHaptics.isDefaultProfile(HgramHapticProfile.PLACE_TOGGLES,
                HgramHapticProfile.defaults(HgramHapticProfile.PLACE_TOGGLES)));
        assertFalse(HgramHaptics.isDefaultProfile(HgramHapticProfile.PLACE_TOGGLES,
                new HgramHapticProfile(80, 20, 1, 60)));
    }

    @Test
    public void disabledMasterDoesNotPlayViewFeedback() {
        final int[] calls = {0};
        android.view.View view = new android.view.View(
                ApplicationProvider.getApplicationContext()) {
            @Override
            public boolean performHapticFeedback(int feedbackConstant) {
                calls[0]++;
                return true;
            }
        };
        HgramConfig.setHapticEnabled(false);
        HgramHaptics.play(view, HgramHapticProfile.PLACE_TOGGLES);
        assertEquals(0, calls[0]);
    }
}
