package xyz.nextalone.nagram.hgram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, Android-free geometry of one OCR result.
 *
 * <p>All coordinates are normalized to {@code [0..1]} relative to the bitmap that was passed to
 * {@code TessBaseAPI.setImage}. The model keeps no bitmap, no pixel rectangles and no Android
 * types, so it can be built on a worker thread and consumed by the viewer later.</p>
 *
 * <p>{@link #SPACE_DISPLAY} means the OCR input was already rotated by the engine; the consumer
 * must not rotate it again. {@link #SPACE_RAW} means the input is unrotated; the consumer applies
 * {@link #mapPoint(float, float, int, int)} once using the viewer orientation/invert.</p>
 */
public final class HgramOcrLayout {

    /** Tesseract input is unrotated (raw stored pixels). */
    public static final int SPACE_RAW = 0;
    /** Tesseract input is already in the orientation the user sees. */
    public static final int SPACE_DISPLAY = 1;

    /** Hard limits protecting memory and the overlay path. */
    public static final int MAX_LINES = 2000;
    public static final int MAX_WORDS = 6000;

    /** One recognized word box. Coordinates are normalized and clamped to {@code [0..1]}. */
    public static final class Word {
        public final float left, top, right, bottom;
        public final String text;

        public Word(float left, float top, float right, float bottom, String text) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.text = text == null ? "" : text;
        }
    }

    /** One recognized text line with its words. */
    public static final class Line {
        public final float left, top, right, bottom;
        public final String text;
        public final List<Word> words;

        public Line(float left, float top, float right, float bottom, String text,
                List<Word> words) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.text = text == null ? "" : text;
            this.words = words == null
                    ? Collections.<Word>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(words));
        }
    }

    public final int imageWidth, imageHeight, space;
    public final List<Line> lines;

    private HgramOcrLayout(int imageWidth, int imageHeight, int space, List<Line> lines) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.space = space == SPACE_RAW ? SPACE_RAW : SPACE_DISPLAY;
        this.lines = lines == null
                ? Collections.<Line>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(lines));
    }

    /**
     * Pixel carriers used by the engine immediately after recognition. They never escape the
     * layout factory: the resulting {@link HgramOcrLayout} contains normalized values only.
     */
    static final class PixelWord {
        final int[] box;
        final String text;

        PixelWord(int[] box, String text) {
            this.box = box;
            this.text = text;
        }
    }

    static final class PixelLine {
        final int[] box;
        final String text;
        final List<PixelWord> words = new ArrayList<>();

        PixelLine(int[] box, String text) {
            this.box = box;
            this.text = text;
        }
    }

    /** Normalizes engine pixel boxes. Invalid/empty boxes and words are dropped. */
    static HgramOcrLayout fromPixels(int imageWidth, int imageHeight, int space,
            List<PixelLine> source) {
        if (source == null || imageWidth <= 0 || imageHeight <= 0) {
            return new HgramOcrLayout(imageWidth, imageHeight, space,
                    Collections.<Line>emptyList());
        }
        final List<Line> normalized = new ArrayList<>(Math.min(source.size(), MAX_LINES));
        int wordsSeen = 0;
        for (PixelLine sourceLine : source) {
            if (sourceLine == null) {
                continue;
            }
            float left = Float.NaN, top = Float.NaN, right = Float.NaN, bottom = Float.NaN;
            if (isValidPixelBox(sourceLine.box)) {
                left = normalize(sourceLine.box[0], imageWidth);
                top = normalize(sourceLine.box[1], imageHeight);
                right = normalize(sourceLine.box[2], imageWidth);
                bottom = normalize(sourceLine.box[3], imageHeight);
            }
            final List<Word> words = new ArrayList<>();
            for (PixelWord sourceWord : sourceLine.words) {
                if (wordsSeen >= MAX_WORDS) {
                    break;
                }
                if (sourceWord == null || !isValidPixelBox(sourceWord.box)
                        || isEmptyText(sourceWord.text)) {
                    continue;
                }
                final int[] box = sourceWord.box;
                words.add(new Word(
                        normalize(box[0], imageWidth),
                        normalize(box[1], imageHeight),
                        normalize(box[2], imageWidth),
                        normalize(box[3], imageHeight),
                        sourceWord.text));
                wordsSeen++;
            }
            normalized.add(new Line(left, top, right, bottom, sourceLine.text, words));
        }
        return ofNormalized(imageWidth, imageHeight, space, normalized);
    }

    /** Sanitizes an already-normalized layout. Used by tests and by the pixel factory. */
    static HgramOcrLayout ofNormalized(int imageWidth, int imageHeight, int space,
            List<Line> source) {
        final List<Line> sanitized = new ArrayList<>();
        if (source != null) {
            int wordsSeen = 0;
            for (Line sourceLine : source) {
                if (sanitized.size() >= MAX_LINES) {
                    break;
                }
                if (sourceLine == null) {
                    continue;
                }
                final List<Word> words = new ArrayList<>();
                if (sourceLine.words != null) {
                    for (Word sourceWord : sourceLine.words) {
                        if (wordsSeen >= MAX_WORDS) {
                            break;
                        }
                        if (sourceWord == null || isEmptyText(sourceWord.text)) {
                            continue;
                        }
                        final float left = clamp(sourceWord.left);
                        final float top = clamp(sourceWord.top);
                        final float right = clamp(sourceWord.right);
                        final float bottom = clamp(sourceWord.bottom);
                        if (!isValidRect(left, top, right, bottom)) {
                            continue;
                        }
                        words.add(new Word(left, top, right, bottom, sourceWord.text));
                        wordsSeen++;
                    }
                }
                float left = clamp(sourceLine.left);
                float top = clamp(sourceLine.top);
                float right = clamp(sourceLine.right);
                float bottom = clamp(sourceLine.bottom);
                if (!isValidRect(left, top, right, bottom)) {
                    if (words.isEmpty()) {
                        continue;
                    }
                    left = Float.MAX_VALUE;
                    top = Float.MAX_VALUE;
                    right = -Float.MAX_VALUE;
                    bottom = -Float.MAX_VALUE;
                    for (Word word : words) {
                        left = Math.min(left, word.left);
                        top = Math.min(top, word.top);
                        right = Math.max(right, word.right);
                        bottom = Math.max(bottom, word.bottom);
                    }
                }
                String text = cleanLineText(sourceLine.text);
                if (text.trim().isEmpty()) {
                    text = "";
                }
                if (text.isEmpty() && words.isEmpty()) {
                    continue;
                }
                sanitized.add(new Line(left, top, right, bottom, text, words));
            }
        }
        return new HgramOcrLayout(imageWidth, imageHeight, space, sanitized);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * Checks the layout aspect against the displayed bitmap aspect with a 2% tolerance.
     *
     * <p>For {@link #SPACE_RAW} the OCR frame may be unrotated, so both the direct and the
     * 90-degree swapped aspect are accepted; the consumer still has to map points once.</p>
     */
    public boolean matchesAspect(int width, int height) {
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return false;
        }
        final float layoutAspect = imageWidth / (float) imageHeight;
        final float displayAspect = width / (float) height;
        if (aspectMatches(layoutAspect, displayAspect)) {
            return true;
        }
        return space == SPACE_RAW && aspectMatches(1f / layoutAspect, displayAspect);
    }

    /** Returns the word under the point (padding is normalized), or {@code null}. */
    public Word hitWord(float nx, float ny, float padX, float padY) {
        if (Float.isNaN(nx) || Float.isNaN(ny)) {
            return null;
        }
        final float px = Math.max(0f, padX);
        final float py = Math.max(0f, padY);
        Word best = null;
        float bestArea = Float.MAX_VALUE;
        for (Line line : lines) {
            for (Word word : line.words) {
                if (contains(word.left, word.top, word.right, word.bottom, nx, ny, px, py)) {
                    final float area = (word.right - word.left) * (word.bottom - word.top);
                    if (best == null || area < bestArea) {
                        best = word;
                        bestArea = area;
                    }
                }
            }
        }
        return best;
    }

    /** Returns the line under the point (padding is normalized), or {@code null}. */
    public Line hitLine(float nx, float ny, float padX, float padY) {
        if (Float.isNaN(nx) || Float.isNaN(ny)) {
            return null;
        }
        final float px = Math.max(0f, padX);
        final float py = Math.max(0f, padY);
        Line best = null;
        float bestArea = Float.MAX_VALUE;
        for (Line line : lines) {
            if (contains(line.left, line.top, line.right, line.bottom, nx, ny, px, py)) {
                final float area = (line.right - line.left) * (line.bottom - line.top);
                if (best == null || area < bestArea) {
                    best = line;
                    bestArea = area;
                }
            }
        }
        return best;
    }

    /**
     * Maps a raw-space point to display space: rotate first, then mirror.
     * Orientation is normalized modulo 360; invert is 0 (none), 1 (horizontal), 2 (vertical).
     */
    public static float[] mapPoint(float x, float y, int orientation, int invert) {
        final int angle = normalizeOrientation(orientation);
        float mappedX;
        float mappedY;
        switch (angle) {
            case 90:
                mappedX = 1f - y;
                mappedY = x;
                break;
            case 180:
                mappedX = 1f - x;
                mappedY = 1f - y;
                break;
            case 270:
                mappedX = y;
                mappedY = 1f - x;
                break;
            default:
                mappedX = x;
                mappedY = y;
                break;
        }
        switch (normalizeInvert(invert)) {
            case 1:
                mappedX = 1f - mappedX;
                break;
            case 2:
                mappedY = 1f - mappedY;
                break;
            default:
                break;
        }
        return new float[]{mappedX, mappedY};
    }

    /** Inverse of {@link #mapPoint}: remove the mirror first, then rotate back. */
    public static float[] unmapPoint(float x, float y, int orientation, int invert) {
        final int angle = normalizeOrientation(orientation);
        float unmappedX = x;
        float unmappedY = y;
        switch (normalizeInvert(invert)) {
            case 1:
                unmappedX = 1f - unmappedX;
                break;
            case 2:
                unmappedY = 1f - unmappedY;
                break;
            default:
                break;
        }
        switch (angle) {
            case 90:
                return new float[]{unmappedY, 1f - unmappedX};
            case 180:
                return new float[]{1f - unmappedX, 1f - unmappedY};
            case 270:
                return new float[]{1f - unmappedY, unmappedX};
            default:
                return new float[]{unmappedX, unmappedY};
        }
    }

    private static int normalizeOrientation(int orientation) {
        int angle = orientation % 360;
        if (angle < 0) {
            angle += 360;
        }
        return angle;
    }

    private static int normalizeInvert(int invert) {
        return invert == 1 || invert == 2 ? invert : 0;
    }

    private static boolean aspectMatches(float layoutAspect, float displayAspect) {
        return Math.abs(layoutAspect / displayAspect - 1f) <= 0.02f;
    }

    private static boolean contains(float left, float top, float right, float bottom,
            float x, float y, float padX, float padY) {
        return x >= left - padX && x <= right + padX
                && y >= top - padY && y <= bottom + padY;
    }

    private static boolean isValidPixelBox(int[] box) {
        return box != null && box.length >= 4 && box[2] > box[0] && box[3] > box[1];
    }

    private static boolean isValidRect(float left, float top, float right, float bottom) {
        return right > left && bottom > top;
    }

    private static boolean isEmptyText(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static float normalize(int pixel, int size) {
        return clamp(pixel / (float) size);
    }

    private static float clamp(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }

    /** Keeps line text verbatim except for trailing/leading line breaks. */
    private static String cleanLineText(String text) {
        if (text == null) {
            return "";
        }
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == '\n' || text.charAt(start) == '\r')) {
            start++;
        }
        while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(start, end);
    }
}
