package xyz.nextalone.nagram.hgram;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Pure naming/policy helpers for send-as-file metadata cleanup. */
public final class HgramExifCleaner {

    private HgramExifCleaner() {
    }

    public static boolean shouldClean(boolean sendAsFile, boolean compressedPhoto) {
        return sendAsFile && !compressedPhoto;
    }

    public static boolean shouldRewriteVideoName(boolean sendAsFile, boolean video) {
        return sendAsFile && video;
    }

    public static String neutralName(String originalName, long timestamp, boolean image) {
        String extension = extension(originalName);
        String prefix = image ? "IMG_" : "FILE_";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(timestamp));
        return prefix + stamp + (extension.isEmpty() ? "" : "." + extension);
    }

    public static File targetFile(File cacheDir, String neutralName) {
        File dir = new File(cacheDir, "hgram_exif");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, neutralName);
    }

    public static boolean isSweepCandidate(File file, long now, long maxAgeMs) {
        return file != null && file.isFile() && now - file.lastModified() > maxAgeMs;
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }
}
