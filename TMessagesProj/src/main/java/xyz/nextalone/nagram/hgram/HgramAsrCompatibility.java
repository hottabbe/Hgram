package xyz.nextalone.nagram.hgram;

import android.os.Build;

import java.lang.reflect.Method;

/**
 * Detects emulators where the packaged ARM-only ONNX Runtime cannot run natively.
 * The sherpa-onnx AAR ships arm64-v8a/armeabi-v7a only; an x86_64 emulator that
 * translates ARM code crashes inside libonnxruntime.so. Keep the check in one
 * place and use it for both offline engines before any native call.
 */
public final class HgramAsrCompatibility {

    private HgramAsrCompatibility() {
    }

    public static boolean isUnsupportedEmulator() {
        if ("1".equals(getSystemProperty("ro.kernel.qemu"))) {
            return true;
        }
        boolean arm64 = false;
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi == null) {
                continue;
            }
            if (abi.startsWith("arm64")) {
                arm64 = true;
            } else if (abi.startsWith("x86")) {
                x86 = true;
            }
        }
        // ARM libraries on a device that also exposes x86 ABIs means native
        // translation is in use (classic x86_64 emulator with an arm64 APK).
        return arm64 && x86;
    }

    private static String getSystemProperty(String key) {
        try {
            final Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            final Method get = systemProperties.getMethod("get", String.class);
            final Object value = get.invoke(null, key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
