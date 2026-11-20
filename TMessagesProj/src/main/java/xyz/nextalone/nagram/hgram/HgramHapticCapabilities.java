package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.provider.Settings;

/** Snapshot of what the current device can actually play. */
public final class HgramHapticCapabilities {

    public final int sdk;
    public final boolean hasVibrator;
    public final boolean hasAmplitudeControl;
    public final boolean supportsWaveform;
    public final boolean supportsComposition;
    public final boolean supportsEnvelope;
    public final boolean supportsBuilder;
    public final boolean systemHapticsEnabled;

    public HgramHapticCapabilities(int sdk, boolean hasVibrator, boolean hasAmplitudeControl,
                                   boolean supportsWaveform, boolean supportsComposition,
                                   boolean systemHapticsEnabled) {
        this(sdk, hasVibrator, hasAmplitudeControl, supportsWaveform, supportsComposition,
                sdk >= 33, supportsBuilder(), systemHapticsEnabled);
    }

    public HgramHapticCapabilities(int sdk, boolean hasVibrator, boolean hasAmplitudeControl,
                                   boolean supportsWaveform, boolean supportsComposition,
                                   boolean supportsEnvelope, boolean supportsBuilder,
                                   boolean systemHapticsEnabled) {
        this.sdk = sdk;
        this.hasVibrator = hasVibrator;
        this.hasAmplitudeControl = hasAmplitudeControl;
        this.supportsWaveform = supportsWaveform;
        this.supportsComposition = supportsComposition;
        this.supportsEnvelope = supportsEnvelope;
        this.supportsBuilder = supportsBuilder;
        this.systemHapticsEnabled = systemHapticsEnabled;
    }

    public static HgramHapticCapabilities detect(Context context) {
        int sdk = Build.VERSION.SDK_INT;
        boolean hasVibrator = false;
        boolean amplitude = false;
        try {
            Vibrator vibrator = context == null ? null
                    : (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                hasVibrator = vibrator.hasVibrator();
                amplitude = sdk >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl();
            }
        } catch (Throwable ignore) {
        }
        boolean system = true;
        try {
            system = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
        } catch (Throwable ignore) {
        }
        return new HgramHapticCapabilities(sdk, hasVibrator, amplitude,
                sdk >= Build.VERSION_CODES.O, sdk >= Build.VERSION_CODES.R,
                sdk >= 33, supportsBuilder(), system);
    }

    /** Per-place profiles make no sense on a device that cannot vibrate at all. */
    public boolean canConfigureProfiles() {
        return hasVibrator;
    }

    public boolean showIntensity() {
        return hasVibrator && sdk >= Build.VERSION_CODES.O;
    }

    public boolean exactIntensity() {
        return showIntensity() && hasAmplitudeControl;
    }

    public boolean showPattern() {
        return hasVibrator && supportsWaveform;
    }

    public boolean useWaveform() {
        return hasVibrator && supportsWaveform;
    }

    public boolean useComposition() {
        return hasVibrator && supportsComposition;
    }

    public String intensityHint() {
        if (!showIntensity()) {
            return null;
        }
        return exactIntensity() ? null : "Приблизительно (без точной амплитуды)";
    }

    public static boolean supportsBuilder() {
        try {
            Class.forName("android.os.VibrationEffect$Builder");
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    public boolean canPlayComposition(int... primitives) {
        return useComposition();
    }

    public int vibrationUsage() {
        return VibrationAttributes.USAGE_TOUCH;
    }

    public int audioUsage() {
        return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
    }
}
