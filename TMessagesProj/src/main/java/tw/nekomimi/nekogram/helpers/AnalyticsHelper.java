package tw.nekomimi.nekogram.helpers;

import android.app.Application;

public class AnalyticsHelper {
    public static String DSN = "";
    public static boolean loaded = false;

    public static void start(Application application) {
        // Telemetry disabled
    }

    public static void captureException(Throwable e) {
        // Telemetry disabled
    }

    public static boolean getSentryStatus(Application application) {
        return false;
    }
}
