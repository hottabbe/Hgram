package xyz.nextalone.nagram.hgram;

import android.app.Application;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;

/** Hgram process/network integration kept out of upstream ApplicationLoader. */
public final class HgramProcessEnvironment {

    private static volatile boolean sttNativeInitFailed;

    private HgramProcessEnvironment() {
    }

    public static boolean isSttNativeInitFailed() {
        return sttNativeInitFailed;
    }

    public static boolean isSttProcess() {
        String processName = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                processName = Application.getProcessName();
            } catch (Throwable ignored) {
            }
        }
        if (processName == null) {
            try (java.io.FileInputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
                final StringBuilder builder = new StringBuilder();
                int value;
                while ((value = input.read()) > 0 && value != '\0') {
                    builder.append((char) value);
                }
                processName = builder.toString();
            } catch (Throwable ignored) {
            }
        }
        return processName != null && processName.endsWith(":stt");
    }

    public static void notifyConnectionQualityChanged() {
        final boolean slow = ApplicationLoader.isConnectionSlow();
        for (int account : SharedConfig.activeAccounts) {
            ConnectionsManager.getInstance(account).checkConnection();
            FileLoader.getInstance(account).onNetworkChanged(slow);
        }
        if (SharedConfig.loginingAccount != -1) {
            ConnectionsManager.getInstance(SharedConfig.loginingAccount).checkConnection();
            FileLoader.getInstance(SharedConfig.loginingAccount).onNetworkChanged(slow);
        }
    }
}
