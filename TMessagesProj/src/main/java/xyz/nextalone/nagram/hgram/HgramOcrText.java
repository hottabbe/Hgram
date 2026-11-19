package xyz.nextalone.nagram.hgram;

import java.util.List;

/** Pure helpers for local OCR: downscale calculation and ordered text assembly. */
public final class HgramOcrText {

    public static final int MAX_LONG_SIDE = 1600;

    private HgramOcrText() {
    }

    public static int scaledLongSide(int width, int height, int maxLongSide) {
        int longest = Math.max(width, height);
        if (longest <= 0 || maxLongSide <= 0 || longest <= maxLongSide) {
            return Math.max(0, longest);
        }
        return maxLongSide;
    }

    public static int scaledShortSide(int width, int height, int maxLongSide) {
        int longest = Math.max(width, height);
        if (longest <= 0 || maxLongSide <= 0 || longest <= maxLongSide) {
            return Math.min(Math.max(0, width), Math.max(0, height));
        }
        int shortest = Math.min(width, height);
        return Math.max(1, Math.round(shortest * (maxLongSide / (float) longest)));
    }

    /**
     * Joins recognized lines in reading order. Empty lines are dropped, while the
     * original line boundaries are preserved with a single newline.
     */
    public static String joinLines(List<String> lines) {
        if (lines == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(trimmed);
        }
        return out.toString();
    }

    public static String copyText(String text) {
        return text == null ? "" : text;
    }
}
