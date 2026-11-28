package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Context;
import android.os.VibrationEffect;
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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramHapticsTest {

    @Before
    public void setup() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());
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
        assertEquals(HgramHapticProfile.MIN_INTENSITY, profile.intensity);
        assertEquals(300, profile.durationMs);
        assertEquals(2, profile.pattern);
        assertEquals(20, profile.gapMs);
        HgramConfig.preferences().edit().putString(
                HgramHapticProfile.key(HgramHapticProfile.PLACE_ALBUM), "bad,data").apply();
        assertEquals(50, HgramHapticProfile.of(HgramHapticProfile.PLACE_ALBUM).intensity);
    }

    @Test
    public void intensityIsClampedToTenPercent() {
        assertEquals(26, HgramHapticProfile.MIN_INTENSITY);
        assertEquals(26, new HgramHapticProfile(0, 20, 0, 60).intensity);
        assertEquals(26, new HgramHapticProfile(1, 20, 0, 60).intensity);
        assertEquals(26, new HgramHapticProfile(9, 20, 0, 60).intensity);
        assertEquals(26, new HgramHapticProfile(25, 20, 0, 60).intensity);
        assertEquals(26, new HgramHapticProfile(26, 20, 0, 60).intensity);
        assertEquals(255, new HgramHapticProfile(255, 20, 0, 60).intensity);
        // Legacy saved profiles with zero intensity load without crashing and get the new minimum.
        HgramConfig.preferences().edit().putString(
                HgramHapticProfile.key(HgramHapticProfile.PLACE_ALBUM), "0,20,0,60").apply();
        assertEquals(HgramHapticProfile.MIN_INTENSITY,
                HgramHapticProfile.of(HgramHapticProfile.PLACE_ALBUM).intensity);
        // Older 10/255 (4%) profiles are raised to the 10% floor on load.
        HgramConfig.preferences().edit().putString(
                HgramHapticProfile.key(HgramHapticProfile.PLACE_TOGGLES), "10,20,0,60").apply();
        HgramHapticProfile legacyTen = HgramHapticProfile.of(HgramHapticProfile.PLACE_TOGGLES);
        assertEquals(HgramHapticProfile.MIN_INTENSITY, legacyTen.intensity);
        assertTrue(legacyTen.summary().startsWith("10%"));
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
            assertTrue(p.intensity >= HgramHapticProfile.MIN_INTENSITY && p.intensity <= 255);
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
    public void minimumIntensityAndDisabledToggle() {
        // Intensity 0 is no longer representable: the floor is 10% on every path.
        HgramHapticProfile minimum = new HgramHapticProfile(10, 20, 0, 60);
        assertEquals(HgramHapticProfile.MIN_INTENSITY, HgramHaptics.amplitudes(minimum, true)[0]);
        assertEquals(HgramHapticProfile.MIN_INTENSITY, HgramHaptics.amplitudes(minimum, false)[0]);
        HgramConfig.setHapticEnabled(true);
        assertTrue(HgramConfig.isHapticEnabled());
        HgramConfig.setHapticEnabled(false);
        assertFalse(HgramConfig.isHapticEnabled());
        HgramConfig.setHapticEnabled(true);
        HgramConfig.reset();
        assertFalse(HgramConfig.isHapticEnabled());
    }

    @Test
    public void minimumIntensityPreviewDoesNotThrow() {
        HgramHapticProfile profile = new HgramHapticProfile(0, 20, 0, 60);
        try {
            HgramHaptics.preview(ApplicationProvider.getApplicationContext(), profile);
        } catch (Throwable t) {
            org.junit.Assert.fail("preview threw for minimum intensity: " + t);
        }
        assertEquals(HgramHapticProfile.MIN_INTENSITY, HgramHaptics.amplitudes(profile, true)[0]);
    }

    @Test
    public void profilesAreUnavailableWithoutVibrator() {
        assertFalse(new HgramHapticCapabilities(33, false, false, true, true, true)
                .canConfigureProfiles());
        assertTrue(new HgramHapticCapabilities(33, true, false, true, true, true)
                .canConfigureProfiles());
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

    @Test
    public void viewHapticsFlagsDoNotDependOnProfile() {
        final Context context = ApplicationProvider.getApplicationContext();
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        final ShadowVibrator shadowVibrator = Shadows.shadowOf(vibrator);
        shadowVibrator.setHasVibrator(true);
        vibrator.cancel();
        HgramConfig.setHapticEnabled(true);

        final int[] flagSets = {
                0,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                        | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        };
        final ProbeView probe = new ProbeView(context);
        for (boolean attached : new boolean[]{false, true}) {
            for (boolean hapticEnabled : new boolean[]{false, true}) {
                for (boolean custom : new boolean[]{false, true}) {
                    probe.attached = attached;
                    probe.setHapticFeedbackEnabled(hapticEnabled);
                    saveProfile(custom);
                    for (int flags : flagSets) {
                        probe.hapticCalls = 0;
                        HgramHaptics.play(probe, HgramHapticProfile.PLACE_LONG_PRESS,
                                HapticFeedbackConstants.LONG_PRESS, flags);
                        assertRoute(attached && (hapticEnabled
                                        || (flags & HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING) != 0),
                                custom, probe);
                    }
                    // The two-argument overload has no flags: only the View state applies.
                    probe.hapticCalls = 0;
                    HgramHaptics.play(probe, HgramHapticProfile.PLACE_LONG_PRESS);
                    assertRoute(attached && hapticEnabled, custom, probe);
                }
            }
        }

        // Master OFF wins over every flag and profile combination: neither a View call nor
        // the context playback path may run.
        HgramConfig.setHapticEnabled(false);
        vibrator.cancel();
        for (boolean custom : new boolean[]{false, true}) {
            saveProfile(custom);
            for (int flags : flagSets) {
                probe.attached = true;
                probe.setHapticFeedbackEnabled(true);
                probe.hapticCalls = 0;
                HgramHaptics.play(probe, HgramHapticProfile.PLACE_LONG_PRESS,
                        HapticFeedbackConstants.LONG_PRESS, flags);
                assertEquals("", HgramHaptics.lastPath());
                assertEquals(0, probe.hapticCalls);
                HgramHaptics.play(probe, HgramHapticProfile.PLACE_LONG_PRESS);
                assertEquals("", HgramHaptics.lastPath());
                assertEquals(0, probe.hapticCalls);
            }
        }
        assertFalse("master OFF must not reach the vibrator", shadowVibrator.isVibrating());
    }

    @Test
    public void nullViewKeepsContextSemantics() {
        final Context context = ApplicationProvider.getApplicationContext();
        final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        Shadows.shadowOf(vibrator).setHasVibrator(true);
        HgramHapticProfile.reset(HgramHapticProfile.PLACE_LONG_PRESS);
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        HgramConfig.setHapticEnabled(true);

        // Null View: never the View path, but the regular play(place) path still runs.
        HgramHaptics.play((View) null, HgramHapticProfile.PLACE_LONG_PRESS);
        assertFalse("null View must not pretend a platform call", "View".equals(HgramHaptics.lastPath()));
        assertFalse("null View must keep the context path", HgramHaptics.lastPath().isEmpty());

        // AOSP-silent case: system touch haptics are off, so the context path must stay silent.
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0);
        HgramHaptics.play((View) null, HgramHapticProfile.PLACE_LONG_PRESS);
        assertEquals("", HgramHaptics.lastPath());

        // Master OFF also silences the null-View path.
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        HgramConfig.setHapticEnabled(false);
        HgramHaptics.play((View) null, HgramHapticProfile.PLACE_LONG_PRESS);
        assertEquals("", HgramHaptics.lastPath());
    }

    @Test
    public void masterDisabledByDefaultAfterReset() {
        HgramConfig.setHapticEnabled(true);
        HgramConfig.reset();
        assertFalse(HgramConfig.isHapticEnabled());
        assertFalse(HgramHaptics.isEnabled());
    }

    @Test
    public void profilePathsRequireExplicitEnable() {
        final Context context = ApplicationProvider.getApplicationContext();
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        final ShadowVibrator shadowVibrator = Shadows.shadowOf(vibrator);
        shadowVibrator.setHasVibrator(true);
        vibrator.cancel();
        final ProbeView probe = new ProbeView(context);
        probe.setHapticFeedbackEnabled(true);

        for (boolean custom : new boolean[]{false, true}) {
            saveProfile(custom);
            probe.hapticCalls = 0;
            HgramHaptics.play(probe, HgramHapticProfile.PLACE_LONG_PRESS,
                    HapticFeedbackConstants.LONG_PRESS, 0);
            assertEquals("", HgramHaptics.lastPath());
            assertEquals(0, probe.hapticCalls);
            HgramHaptics.play(probe, HgramHapticProfile.PLACE_LONG_PRESS);
            assertEquals("", HgramHaptics.lastPath());
            assertEquals(0, probe.hapticCalls);
            assertFalse(shadowVibrator.isVibrating());
        }

        HgramConfig.setHapticEnabled(true);
        for (boolean custom : new boolean[]{false, true}) {
            saveProfile(custom);
            probe.hapticCalls = 0;
            HgramHaptics.play(probe, HgramHapticProfile.PLACE_LONG_PRESS,
                    HapticFeedbackConstants.LONG_PRESS, 0);
            assertRoute(true, custom, probe);
        }
    }

    private static void saveProfile(boolean custom) {
        if (custom) {
            new HgramHapticProfile(120, 25, 0, 60).save(HgramHapticProfile.PLACE_LONG_PRESS);
        } else {
            HgramHapticProfile.reset(HgramHapticProfile.PLACE_LONG_PRESS);
        }
    }

    private static void assertRoute(boolean viewStateAllows, boolean custom, ProbeView probe) {
        if (!viewStateAllows) {
            assertEquals("View state must short-circuit before any playback", "",
                    HgramHaptics.lastPath());
            assertEquals(0, probe.hapticCalls);
            return;
        }
        if (!custom) {
            assertEquals("View", HgramHaptics.lastPath());
            assertEquals(1, probe.hapticCalls);
            return;
        }
        assertFalse("custom profile must not reuse the View path",
                "View".equals(HgramHaptics.lastPath()));
        assertEquals("custom profile must not call performHapticFeedback", 0, probe.hapticCalls);
        assertFalse("custom profile must reach the Context path",
                HgramHaptics.lastPath().isEmpty());
    }

    private static final class ProbeView extends View {
        /** Attach state is modelled explicitly: the gate under test reads only this API. */
        boolean attached = true;
        int hapticCalls;

        ProbeView(Context context) {
            super(context);
        }

        @Override
        public boolean isAttachedToWindow() {
            return attached;
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

    @Test
    public void csvOldFormatLoadsAuto() {
        HgramConfig.preferences().edit().putString(
                HgramHapticProfile.key(HgramHapticProfile.PLACE_ALBUM), "100,20,1,60").apply();
        HgramHapticProfile loaded = HgramHapticProfile.of(HgramHapticProfile.PLACE_ALBUM);
        assertEquals(100, loaded.intensity);
        assertEquals(20, loaded.durationMs);
        assertEquals(1, loaded.pattern);
        assertEquals(60, loaded.gapMs);
        assertEquals(HgramHapticEffect.AUTO, loaded.effect);
    }

    @Test
    public void csvRoundTripsEffect() {
        new HgramHapticProfile(100, 20, 1, 60, HgramHapticEffect.THUD)
                .save(HgramHapticProfile.PLACE_TOGGLES);
        HgramHapticProfile loaded = HgramHapticProfile.of(HgramHapticProfile.PLACE_TOGGLES);
        assertEquals(HgramHapticEffect.THUD, loaded.effect);
        assertTrue(loaded.summary().contains("thud"));
    }

    @Test
    public void csvBrokenEffectFallsBackToAuto() {
        HgramConfig.preferences().edit().putString(
                HgramHapticProfile.key(HgramHapticProfile.PLACE_TOGGLES), "100,20,1,60,garbage")
                .apply();
        HgramHapticProfile brokenEffect = HgramHapticProfile.of(HgramHapticProfile.PLACE_TOGGLES);
        assertEquals(HgramHapticEffect.AUTO, brokenEffect.effect);
        assertEquals(100, brokenEffect.intensity);
        assertEquals(60, brokenEffect.gapMs);

        HgramConfig.preferences().edit().putString(
                HgramHapticProfile.key(HgramHapticProfile.PLACE_ALBUM), "not,numbers,at,all")
                .apply();
        HgramHapticProfile defaults = HgramHapticProfile.defaults(HgramHapticProfile.PLACE_ALBUM);
        HgramHapticProfile brokenNumbers = HgramHapticProfile.of(HgramHapticProfile.PLACE_ALBUM);
        assertEquals(defaults.intensity, brokenNumbers.intensity);
        assertEquals(defaults.durationMs, brokenNumbers.durationMs);
        assertEquals(defaults.pattern, brokenNumbers.pattern);
        assertEquals(defaults.gapMs, brokenNumbers.gapMs);
        assertEquals(HgramHapticEffect.AUTO, brokenNumbers.effect);

        HgramConfig.preferences().edit().putString(
                HgramHapticProfile.key(HgramHapticProfile.PLACE_LIST_ITEMS), "bad,20,0,garbage")
                .apply();
        assertEquals(HgramHapticProfile.defaults(HgramHapticProfile.PLACE_LIST_ITEMS).intensity,
                HgramHapticProfile.of(HgramHapticProfile.PLACE_LIST_ITEMS).intensity);
    }

    @Test
    public void summaryShowsEffectOnlyWhenNotAuto() {
        HgramHapticProfile auto = new HgramHapticProfile(100, 20, 1, 60, HgramHapticEffect.AUTO);
        assertFalse(auto.summary().contains(" · auto"));
        assertEquals("39% · 20 ms · double", auto.summary());
        HgramHapticProfile click = new HgramHapticProfile(100, 20, 1, 60, HgramHapticEffect.CLICK);
        assertTrue(click.summary().endsWith("click"));
        assertFalse(click.summary().endsWith("auto"));
    }

    @Test
    public void effectPrimitivesMapAllTypes() {
        assertArrayEquals(new int[]{VibrationEffect.Composition.PRIMITIVE_TICK},
                HgramHaptics.effectPrimitives(HgramHapticEffect.TICK));
        assertArrayEquals(new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK},
                HgramHaptics.effectPrimitives(HgramHapticEffect.CLICK));
        assertArrayEquals(new int[]{VibrationEffect.Composition.PRIMITIVE_THUD},
                HgramHaptics.effectPrimitives(HgramHapticEffect.THUD));
        assertArrayEquals(new int[]{VibrationEffect.Composition.PRIMITIVE_SLOW_RISE},
                HgramHaptics.effectPrimitives(HgramHapticEffect.RISE));
        assertArrayEquals(new int[]{VibrationEffect.Composition.PRIMITIVE_QUICK_FALL},
                HgramHaptics.effectPrimitives(HgramHapticEffect.FALL));
        assertArrayEquals(new int[]{VibrationEffect.Composition.PRIMITIVE_SPIN},
                HgramHaptics.effectPrimitives(HgramHapticEffect.SPIN));
        assertArrayEquals(new int[]{
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_FALL},
                HgramHaptics.effectPrimitives(HgramHapticEffect.POP));
        assertEquals(0, HgramHaptics.effectPrimitives(HgramHapticEffect.AUTO).length);
        assertEquals(0, HgramHaptics.effectPrimitives(null).length);
    }

    @Test
    public void playbackPathMatrix() {
        assertEquals(HgramHaptics.PlaybackPath.WAVEFORM,
                path(HgramHapticEffect.CLICK, 30, true, true, true, true, false));
        assertEquals(HgramHaptics.PlaybackPath.COMPOSITION,
                path(HgramHapticEffect.CLICK, 31, true, true, true, true, false));
        assertEquals(HgramHaptics.PlaybackPath.WAVEFORM,
                path(HgramHapticEffect.CLICK, 31, true, true, true, false, false));
        assertEquals(HgramHaptics.PlaybackPath.WAVEFORM,
                path(HgramHapticEffect.CLICK, 31, true, true, false, true, false));
        assertEquals(HgramHaptics.PlaybackPath.ONE_SHOT,
                path(HgramHapticEffect.CLICK, 31, false, false, true, true, false));
        assertEquals(HgramHaptics.PlaybackPath.COMPOSITION,
                path(HgramHapticEffect.AUTO, 31, true, true, true, true, true));
        assertEquals(HgramHaptics.PlaybackPath.WAVEFORM,
                path(HgramHapticEffect.AUTO, 31, true, true, true, true, false));
    }

    @Test
    public void previewDebounceRunsOnce() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger second = new AtomicInteger();
        preview.beginTracking(profile(100));
        preview.release(profile(101), first::incrementAndGet);
        preview.release(profile(102), second::incrementAndGet);
        idleMainLooper(151);
        assertEquals(0, first.get());
        assertEquals(1, second.get());
    }

    @Test
    public void previewDoesNotRunBeforeDebounce() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger count = new AtomicInteger();
        preview.beginTracking(profile(100));
        preview.release(profile(101), count::incrementAndGet);
        idleMainLooper(149);
        assertEquals(0, count.get());
        idleMainLooper(1);
        assertEquals(1, count.get());
    }

    @Test
    public void previewCancelDropsPending() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger count = new AtomicInteger();
        preview.beginTracking(profile(100));
        preview.release(profile(101), count::incrementAndGet);
        preview.cancel();
        idleMainLooper(200);
        assertEquals(0, count.get());
    }

    @Test
    public void previewRunsAgainAfterCompletion() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger count = new AtomicInteger();
        preview.beginTracking(profile(100));
        preview.release(profile(101), count::incrementAndGet);
        idleMainLooper(151);
        assertEquals(1, count.get());
        preview.beginTracking(profile(101));
        preview.release(profile(102), count::incrementAndGet);
        idleMainLooper(151);
        assertEquals(2, count.get());
    }

    @Test
    public void repeatedReleasePlaysLatestValueOnce() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<HgramHapticProfile> played = new AtomicReference<>();
        final Consumer<HgramHapticProfile> play = p -> {
            calls.incrementAndGet();
            played.set(p);
        };
        preview.beginTracking(profile(100));
        preview.release(profile(101), play);
        idleMainLooper(100);
        final HgramHapticProfile latest = profile(102);
        preview.release(latest, play);
        latest.intensity = 200;
        idleMainLooper(151);
        assertEquals(1, calls.get());
        assertEquals(102, played.get().intensity);
        preview.beginTracking(profile(102));
        preview.release(profile(102), play);
        idleMainLooper(151);
        assertEquals(1, calls.get());
    }

    @Test
    public void resetWhilePreviewPendingDoesNotPlay() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger count = new AtomicInteger();
        final HgramHapticProfile state = profile(101);
        preview.beginTracking(profile(100));
        preview.release(state, count::incrementAndGet);
        preview.reset();
        idleMainLooper(151);
        assertEquals(0, count.get());
        preview.release(state, count::incrementAndGet);
        idleMainLooper(151);
        assertEquals(1, count.get());
        // reset() also forgets lastPlayedState, so the same profile can play again.
        preview.reset();
        preview.release(state, count::incrementAndGet);
        idleMainLooper(151);
        assertEquals(2, count.get());
    }

    @Test
    public void newDragCancelsPendingPreview() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger count = new AtomicInteger();
        preview.beginTracking(profile(100));
        preview.release(profile(101), count::incrementAndGet);
        preview.beginTracking(profile(101));
        idleMainLooper(200);
        assertEquals(0, count.get());
        preview.release(profile(102), count::incrementAndGet);
        idleMainLooper(151);
        assertEquals(1, count.get());
    }

    @Test
    public void cancelBeforeDebounceDropsPreview() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger count = new AtomicInteger();
        preview.beginTracking(profile(100));
        preview.release(profile(101), count::incrementAndGet);
        preview.cancel();
        idleMainLooper(200);
        assertEquals(0, count.get());
    }

    @Test
    public void releaseWithoutChangeDoesNotPlay() {
        final HgramHapticPreview preview = new HgramHapticPreview();
        final AtomicInteger count = new AtomicInteger();
        preview.beginTracking(profile(100));
        preview.release(profile(100), count::incrementAndGet);
        idleMainLooper(151);
        assertEquals(0, count.get());
        preview.beginTracking(profile(100));
        preview.release(profile(101), count::incrementAndGet);
        idleMainLooper(151);
        assertEquals(1, count.get());
        preview.beginTracking(profile(101));
        preview.release(profile(101), count::incrementAndGet);
        idleMainLooper(151);
        assertEquals(1, count.get());
    }

    @Test
    public void keyboardConstantsMapToTick() {
        assertEquals(HgramHapticEffect.TICK,
                HgramHaptics.effectForSystemConstant(HapticFeedbackConstants.KEYBOARD_TAP));
        assertEquals(HgramHapticEffect.TICK,
                HgramHaptics.effectForSystemConstant(HapticFeedbackConstants.KEYBOARD_PRESS));
        assertEquals(HgramHapticEffect.TICK,
                HgramHaptics.effectForSystemConstant(HapticFeedbackConstants.KEYBOARD_RELEASE));
    }

    @Test
    public void longPressMapsToThud() {
        assertEquals(HgramHapticEffect.THUD,
                HgramHaptics.effectForSystemConstant(HapticFeedbackConstants.LONG_PRESS));
    }

    @Test
    public void contextClickAndVirtualKeyMapToTick() {
        assertEquals(HgramHapticEffect.TICK,
                HgramHaptics.effectForSystemConstant(HapticFeedbackConstants.CONTEXT_CLICK));
        assertEquals(HgramHapticEffect.TICK,
                HgramHaptics.effectForSystemConstant(HapticFeedbackConstants.VIRTUAL_KEY));
    }

    @Test
    public void unknownConstantMapsToAuto() {
        assertEquals(HgramHapticEffect.AUTO, HgramHaptics.effectForSystemConstant(999));
        assertEquals(HgramHapticEffect.AUTO, HgramHaptics.effectForSystemConstant(12345));
        assertEquals(HgramHapticEffect.AUTO,
                HgramHaptics.effectForSystemConstant(HapticFeedbackConstants.CONFIRM));
    }

    private static HgramHapticProfile profile(int intensity) {
        return new HgramHapticProfile(intensity, 20, 0, 60, HgramHapticEffect.AUTO);
    }

    private static HgramHaptics.PlaybackPath path(HgramHapticEffect effect, int sdk,
                                                  boolean compositionSupported,
                                                  boolean waveformSupported,
                                                  boolean amplitudeControl,
                                                  boolean allPrimitivesSupported,
                                                  boolean defaultProfile) {
        return HgramHaptics.decidePlayback(
                new HgramHapticProfile(100, 20, 0, 60, effect), sdk, compositionSupported,
                waveformSupported, amplitudeControl, allPrimitivesSupported, defaultProfile);
    }

    private static void idleMainLooper(long millis) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(millis));
    }
}
