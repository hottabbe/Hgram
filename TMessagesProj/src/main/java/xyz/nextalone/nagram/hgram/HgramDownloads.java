package xyz.nextalone.nagram.hgram;

import android.os.Build;

import java.net.URLConnection;

/** Shared helpers for the on-demand model downloaders. */
public final class HgramDownloads {

    private HgramDownloads() {
    }

    /** Content length in bytes, or -1 when unknown. {@code getContentLengthLong} needs API 24. */
    public static long contentLength(URLConnection connection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return connection.getContentLengthLong();
        }
        return connection.getContentLength();
    }
}
