package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import tw.nekomimi.nekogram.NekoConfig;

/** Single entry point for every Hgram haptic response. */
public final class HgramHaptics {

    private static volatile String lastPath = "";

    /** Where the produced vibration actually comes from. */
    public enum PlaybackPath {
        COMPOSITION, WAVEFORM, ONE_SHOT, LEGACY
    }

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
            // No View state to honour: keep the usual Hgram master/profile path instead of
            // forcing a context vibration and without pretending the platform call happened.
            play(place);
            return;
        }
        if (!isEnabled() || !canPlayOnView(view, 0)) {
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

    /**
     * Routes a converted {@code view.performHapticFeedback(constant, flags)} call through Hgram.
     * The original constant and flags are preserved for the system fallback, so
     * {@code FLAG_IGNORE_GLOBAL_SETTING} / {@code FLAG_IGNORE_VIEW_SETTING} keep their meaning;
     * a customized profile without its own effect reuses {@link #effectForSystemConstant(int)}.
     * <p>From API 33 {@code FLAG_IGNORE_GLOBAL_SETTING} is forwarded as-is but does not guarantee
     * a vibration when system touch haptics are switched off; Hgram does not substitute other
     * usage attributes for it. {@code FLAG_IGNORE_VIEW_SETTING} skips only the
     * {@link View#isHapticFeedbackEnabled()} check and never the Hgram master switch.
     */
    public static void play(View view, String place, int systemConstant, int systemFlags) {
        lastPath = "";
        if (view == null) {
            // No View state to honour: keep the usual Hgram master/profile path instead of
            // forcing a context vibration and without pretending the platform call happened.
            play(place);
            return;
        }
        if (!isEnabled() || !canPlayOnView(view, systemFlags)) {
            return;
        }
        final boolean ignoreGlobal =
                (systemFlags & HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING) != 0;
        final Context context = view.getContext();
        if (!canPlay(context, ignoreGlobal)) {
            return;
        }
        final HgramHapticProfile profile = HgramHapticProfile.of(place);
        if (isDefaultProfile(place, profile)) {
            if (view.performHapticFeedback(systemConstant, systemFlags)) {
                lastPath = "View";
            }
            return;
        }
        if (normalize(profile.effect) == HgramHapticEffect.AUTO) {
            profile.effect = effectForSystemConstant(systemConstant);
        }
        play(context, place, profile, ignoreGlobal);
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

    /**
     * Effect used when a platform {@link HapticFeedbackConstants} call is routed through Hgram.
     * Unknown constants fall back to {@link HgramHapticEffect#AUTO}, which keeps the profile's
     * own effect or the generic waveform.
     */
    public static HgramHapticEffect effectForSystemConstant(int constant) {
        switch (constant) {
            // KEYBOARD_PRESS shares the platform value of KEYBOARD_TAP (3).
            case HapticFeedbackConstants.KEYBOARD_TAP:
            case HapticFeedbackConstants.KEYBOARD_RELEASE:
            case HapticFeedbackConstants.CONTEXT_CLICK:
            case HapticFeedbackConstants.VIRTUAL_KEY:
                return HgramHapticEffect.TICK;
            case HapticFeedbackConstants.LONG_PRESS:
                return HgramHapticEffect.THUD;
            default:
                return HgramHapticEffect.AUTO;
        }
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
        play(context, place, profile, false);
    }

    private static void play(Context context, String place, HgramHapticProfile profile,
                             boolean ignoreGlobalSetting) {
        lastPath = "";
        if (context == null || profile == null || !isEnabled()
                || !canPlay(context, ignoreGlobalSetting)) {
            return;
        }
        try {
            final Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            final HgramHapticCapabilities capabilities = HgramHapticCapabilities.detect(context);
            if (!capabilities.systemHapticsEnabled && !ignoreGlobalSetting) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                final HgramHapticEffect hapticEffect = profile.effect == null
                        ? HgramHapticEffect.AUTO : profile.effect;
                final int[] required = hapticEffect == HgramHapticEffect.AUTO
                        ? primitives(profile) : effectPrimitives(hapticEffect);
                final boolean allSupported = allPrimitivesSupported(vibrator, required);
                final boolean defaultProfile = place != null && isDefaultProfile(place, profile);
                final PlaybackPath path = decidePlayback(profile, Build.VERSION.SDK_INT,
                        capabilities.useComposition(), capabilities.useWaveform(),
                        capabilities.exactIntensity(), allSupported, defaultProfile);
                VibrationEffect effect;
                switch (path) {
                    case COMPOSITION:
                        effect = hapticEffect == HgramHapticEffect.AUTO
                                ? composition(profile) : composition(profile, required);
                        lastPath = "Composition";
                        break;
                    case WAVEFORM:
                        effect = VibrationEffect.createWaveform(timings(profile),
                                amplitudesForPlayback(profile, capabilities.exactIntensity()), -1);
                        lastPath = "Waveform";
                        break;
                    case ONE_SHOT:
                    default:
                        effect = VibrationEffect.createOneShot(profile.durationMs,
                                capabilities.exactIntensity() && profile.intensity > 0
                                        ? profile.intensity : VibrationEffect.DEFAULT_AMPLITUDE);
                        lastPath = "OneShot";
                        break;
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
     * Built-in effects bypass this helper and are checked by {@link #decidePlayback}.
     */
    static boolean canUseComposition(String place, HgramHapticProfile profile,
                                     HgramHapticCapabilities capabilities, Vibrator vibrator) {
        if (place == null || capabilities == null || vibrator == null
                || !capabilities.useComposition() || !isDefaultProfile(place, profile)) {
            return false;
        }
        return allPrimitivesSupported(vibrator, primitives(profile));
    }

    /**
     * Pure decision table for the playback path; performs no Android calls.
     * A concrete effect needs API 31 composition plus amplitude control, everything else
     * falls back to the waveform (or a one-shot). AUTO keeps the legacy behaviour.
     */
    public static PlaybackPath decidePlayback(HgramHapticProfile profile, int sdk,
                                              boolean compositionSupported,
                                              boolean waveformSupported,
                                              boolean amplitudeControl,
                                              boolean allPrimitivesSupported,
                                              boolean defaultProfile) {
        final HgramHapticEffect effect = profile == null ? HgramHapticEffect.AUTO
                : (profile.effect == null ? HgramHapticEffect.AUTO : profile.effect);
        if (effect != HgramHapticEffect.AUTO) {
            if (sdk >= Build.VERSION_CODES.S && compositionSupported && amplitudeControl
                    && allPrimitivesSupported) {
                return PlaybackPath.COMPOSITION;
            }
            return waveformSupported ? PlaybackPath.WAVEFORM : PlaybackPath.ONE_SHOT;
        }
        if (compositionSupported && allPrimitivesSupported && defaultProfile) {
            return PlaybackPath.COMPOSITION;
        }
        return waveformSupported ? PlaybackPath.WAVEFORM : PlaybackPath.ONE_SHOT;
    }

    public static VibrationEffect composition(HgramHapticProfile profile) {
        return composition(profile, profile == null ? null : primitives(profile));
    }

    /** Duration is not representable in a composition: gap and scale carry the profile. */
    public static VibrationEffect composition(HgramHapticProfile profile, int[] primitives) {
        final int[] layout = primitives == null || primitives.length == 0
                ? effectPrimitives(HgramHapticEffect.TICK) : primitives;
        final float scale = profile == null ? 1f
                : Math.max(0.1f, Math.min(1f, profile.intensity / 255f));
        final int gapMs = profile == null ? 0 : profile.gapMs;
        final VibrationEffect.Composition composition = VibrationEffect.startComposition();
        for (int i = 0; i < layout.length; i++) {
            if (i > 0 && gapMs > 0) {
                composition.addPrimitive(layout[i], scale, gapMs);
            } else {
                composition.addPrimitive(layout[i], scale);
            }
        }
        return composition.compose();
    }

    static boolean allPrimitivesSupported(Vibrator vibrator, int[] primitives) {
        if (vibrator == null || primitives == null || primitives.length == 0
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        try {
            return vibrator.areAllPrimitivesSupported(primitives);
        } catch (Throwable ignore) {
            return false;
        }
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

    /**
     * Composition primitives for a built-in effect. {@link HgramHapticEffect#AUTO} is played
     * through {@link #primitives(HgramHapticProfile)} and returns an empty array here.
     */
    public static int[] effectPrimitives(HgramHapticEffect effect) {
        if (effect == null) {
            return new int[0];
        }
        switch (effect) {
            case TICK:
                return new int[]{VibrationEffect.Composition.PRIMITIVE_TICK};
            case CLICK:
                return new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK};
            case THUD:
                return new int[]{VibrationEffect.Composition.PRIMITIVE_THUD};
            case POP:
                return new int[]{VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_FALL};
            case RISE:
                return new int[]{VibrationEffect.Composition.PRIMITIVE_SLOW_RISE};
            case FALL:
                return new int[]{VibrationEffect.Composition.PRIMITIVE_QUICK_FALL};
            case SPIN:
                return new int[]{VibrationEffect.Composition.PRIMITIVE_SPIN};
            case AUTO:
            default:
                return new int[0];
        }
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
                && profile.gapMs == defaults.gapMs
                && normalize(profile.effect) == normalize(defaults.effect);
    }

    private static HgramHapticEffect normalize(HgramHapticEffect effect) {
        return effect == null ? HgramHapticEffect.AUTO : effect;
    }

    /**
     * A converted platform call must not bypass the View state: a detached View or a View with
     * haptic feedback disabled stays silent exactly like the original
     * {@code View.performHapticFeedback} call. {@code FLAG_IGNORE_VIEW_SETTING} bypasses only
     * the enabled flag; the Hgram master switch is checked before this helper and always wins.
     */
    private static boolean canPlayOnView(View view, int systemFlags) {
        if (view == null || !view.isAttachedToWindow()) {
            return false;
        }
        return (systemFlags & HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING) != 0
                || view.isHapticFeedbackEnabled();
    }

    private static boolean canPlay(Context context) {
        return canPlay(context, false);
    }

    private static boolean canPlay(Context context, boolean ignoreGlobalSetting) {
        if (context == null) {
            return false;
        }
        try {
            if (!ignoreGlobalSetting && Settings.System.getInt(context.getContentResolver(),
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
