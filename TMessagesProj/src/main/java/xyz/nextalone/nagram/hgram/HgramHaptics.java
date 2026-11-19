package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import tw.nekomimi.nekogram.NekoConfig;

/** Single entry point for every Hgram haptic response. */
public final class HgramHaptics {

    private static volatile String lastPath = "";

    private HgramHaptics() {
    }

    public static boolean isEnabled() {
        return HgramConfig.isHapticEnabled() && !NekoConfig.disableVibration.Bool();
    }

    public static void setEnabled(boolean enabled) {
        HgramConfig.setHapticEnabled(enabled);
    }

    public static void play(String place) {
        play(ApplicationLoader.applicationContext, place, HgramHapticProfile.of(place));
    }

    public static void play(String place, int fallbackEffect) {
        play(place);
    }

    public static void play(View view, String place) {
        lastPath = "";
        if (view == null) {
            play(place);
            return;
        }
        if (!isEnabled()) {
            return;
        }
        final Context context = view.getContext();
        if (!canPlay(context)) {
            return;
        }
        final HgramHapticProfile profile = HgramHapticProfile.of(place);
        if (isDefaultProfile(place, profile)) {
            if (view.performHapticFeedback(fallbackEffect(place))) {
                lastPath = "View";
            }
        } else {
            play(context, place, profile);
        }
    }

    /** Plays a raw Mini Apps/Bot API effect without remapping its amplitudes. */
    public static void playRaw(android.os.VibrationEffect effect, long[] fallbackTimings) {
        lastPath = "";
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !isEnabled() || !canPlay(context)) {
            return;
        }
        try {
            final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect play = effect != null ? effect
                        : VibrationEffect.createWaveform(fallbackTimings, -1);
                try {
                    vibrator.vibrate(play, VibrationAttributes.createForUsage(
                            VibrationAttributes.USAGE_TOUCH));
                } catch (Throwable fallback) {
                    vibrator.vibrate(play, new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                lastPath = "Raw";
            } else {
                vibrator.vibrate(fallbackTimings, -1);
                lastPath = "Legacy";
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static String preview(String place) {
        play(place);
        return lastPath;
    }

    public static String lastPath() {
        return lastPath;
    }

    private static int fallbackEffect(String place) {
        if (HgramHapticProfile.PLACE_LONG_PRESS.equals(place)) {
            return android.view.HapticFeedbackConstants.LONG_PRESS;
        }
        if (HgramHapticProfile.PLACE_TEXT_CURSOR.equals(place)) {
            return android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE;
        }
        if (HgramHapticProfile.PLACE_LIST_ITEMS.equals(place)) {
            return android.view.HapticFeedbackConstants.VIRTUAL_KEY;
        }
        return android.view.HapticFeedbackConstants.CONFIRM;
    }

    public static void play(Context context, String place) {
        play(context, place, HgramHapticProfile.of(place));
    }

    public static void play(Context context, HgramHapticProfile profile) {
        play(context, null, profile);
    }

    public static void preview(Context context, HgramHapticProfile profile) {
        play(context, null, profile);
    }

    public static void preview(Context context, String place, HgramHapticProfile profile) {
        play(context, place, profile);
    }

    public static void play(Context context, String place, HgramHapticProfile profile) {
        lastPath = "";
        if (context == null || profile == null || !isEnabled() || !canPlay(context)) {
            return;
        }
        try {
            final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            final HgramHapticCapabilities capabilities = HgramHapticCapabilities.detect(context);
            if (!capabilities.systemHapticsEnabled) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = null;
                if (canUseComposition(place, profile, capabilities, vibrator)) {
                    effect = composition(profile);
                    lastPath = "Composition";
                }
                if (effect == null && capabilities.useWaveform()) {
                    effect = VibrationEffect.createWaveform(timings(profile),
                            amplitudesForPlayback(profile, capabilities.exactIntensity()), -1);
                    lastPath = "Waveform";
                }
                if (effect == null) {
                    effect = VibrationEffect.createOneShot(profile.durationMs,
                            capabilities.exactIntensity() && profile.intensity > 0
                                    ? profile.intensity : VibrationEffect.DEFAULT_AMPLITUDE);
                    lastPath = "OneShot";
                }
                playEffect(vibrator, effect, capabilities);
            } else {
                vibrator.vibrate(legacyPattern(profile), -1);
                lastPath = "Legacy";
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void playEffect(Vibrator vibrator, VibrationEffect effect,
                                   HgramHapticCapabilities capabilities) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(
                        capabilities.vibrationUsage()));
            } else {
                vibrator.vibrate(effect, new AudioAttributes.Builder()
                        .setUsage(capabilities.audioUsage())
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            }
        } catch (Throwable fallback) {
            vibrator.vibrate(effect);
        }
    }

    /**
     * Composition primitives cannot encode a custom duration, so it is used only for the
     * untouched default of the requested place. Every customized profile goes through a
     * waveform that carries duration, gap, repeats and (when supported) amplitude.
     */
    static boolean canUseComposition(String place, HgramHapticProfile profile,
                                     HgramHapticCapabilities capabilities, Vibrator vibrator) {
        if (place == null || capabilities == null || vibrator == null
                || !capabilities.useComposition() || !isDefaultProfile(place, profile)) {
            return false;
        }
        try {
            return vibrator.areAllPrimitivesSupported(primitives(profile));
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static VibrationEffect composition(HgramHapticProfile profile) {
        final int[] primitives = primitives(profile);
        final float scale = profile.intensity <= 0 ? 1f
                : Math.max(0.01f, Math.min(1f, profile.intensity / 255f));
        VibrationEffect.Composition composition = VibrationEffect.startComposition();
        for (int i = 0; i < primitives.length; i++) {
            if (i > 0 && profile.gapMs > 0) {
                composition.addPrimitive(primitives[i], scale, profile.gapMs);
            } else {
                composition.addPrimitive(primitives[i], scale);
            }
        }
        return composition.compose();
    }

    public static int[] primitives(HgramHapticProfile profile) {
        if (profile.pattern == 2) {
            return new int[]{VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK};
        }
        if (profile.pattern == 1) {
            return new int[]{VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK};
        }
        return new int[]{VibrationEffect.Composition.PRIMITIVE_TICK};
    }

    public static long[] timings(HgramHapticProfile profile) {
        int repeats = Math.max(1, profile.pattern + 1);
        long[] result = new long[repeats * 2 - 1];
        for (int i = 0; i < repeats; i++) {
            result[i * 2] = profile.durationMs;
            if (i < repeats - 1) {
                result[i * 2 + 1] = profile.gapMs;
            }
        }
        return result;
    }

    /** Legacy pattern: leading delay 0, then on/off alternation. */
    public static long[] legacyPattern(HgramHapticProfile profile) {
        int repeats = Math.max(1, profile.pattern + 1);
        long[] result = new long[repeats * 2];
        for (int i = 0; i < repeats; i++) {
            result[i * 2 + 1] = profile.durationMs;
            if (i < repeats - 1) {
                result[i * 2 + 2] = profile.gapMs;
            }
        }
        return result;
    }

    public static int[] amplitudes(HgramHapticProfile profile, boolean systemForZero) {
        long[] timings = timings(profile);
        int level = profile.intensity == 0
                ? (systemForZero ? VibrationEffect.DEFAULT_AMPLITUDE : 0)
                : profile.intensity;
        int[] result = new int[timings.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = i % 2 == 0 ? level : 0;
        }
        return result;
    }

    /** On/off amplitudes used for playback; no amplitude control => system default. */
    public static int[] amplitudesForPlayback(HgramHapticProfile profile, boolean exactIntensity) {
        long[] timings = timings(profile);
        int level = !exactIntensity || profile.intensity == 0
                ? VibrationEffect.DEFAULT_AMPLITUDE : profile.intensity;
        int[] result = new int[timings.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = i % 2 == 0 ? level : 0;
        }
        return result;
    }

    public static int totalDuration(HgramHapticProfile profile) {
        int repeats = Math.max(1, profile.pattern + 1);
        return repeats * profile.durationMs + (repeats - 1) * profile.gapMs;
    }

    static boolean isDefaultProfile(String place, HgramHapticProfile profile) {
        if (place == null || profile == null) {
            return false;
        }
        final HgramHapticProfile defaults = HgramHapticProfile.defaults(place);
        return profile.intensity == defaults.intensity
                && profile.durationMs == defaults.durationMs
                && profile.pattern == defaults.pattern
                && profile.gapMs == defaults.gapMs;
    }

    private static boolean canPlay(Context context) {
        if (context == null) {
            return false;
        }
        try {
            if (Settings.System.getInt(context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) {
                return false;
            }
            final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            return vibrator != null && vibrator.hasVibrator();
        } catch (Throwable ignore) {
            return false;
        }
    }
}
