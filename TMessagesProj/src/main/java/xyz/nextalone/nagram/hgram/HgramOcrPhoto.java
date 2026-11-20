package xyz.nextalone.nagram.hgram;

import java.io.File;

/** Pure decisions used by the PhotoViewer OCR entry point. */
public final class HgramOcrPhoto {

    private HgramOcrPhoto() {
    }

    /** Photos only: videos, GIFs and round videos keep their existing long-press behaviour. */
    public static boolean canRecognize(boolean photo, boolean video, boolean gif, boolean roundVideo) {
        return canRecognize(photo, video, gif, roundVideo, false);
    }

    /** A protected avatar (no message object) must not open the OCR overlay. */
    public static boolean canRecognize(boolean photo, boolean video, boolean gif, boolean roundVideo, boolean protectedContent) {
        return !protectedContent && photo && !video && !gif && !roundVideo;
    }

    /**
     * Prefers the cached message file; falls back to an absolute path supplied by the viewer.
     * Returns {@code null} when nothing readable is available.
     */
    public static File pickFile(File messageFile, String currentFileName) {
        if (messageFile != null && messageFile.isFile()) {
            return messageFile;
        }
        if (currentFileName != null && !currentFileName.isEmpty()) {
            final File candidate = new File(currentFileName);
            if (candidate.isAbsolute() && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }
}
