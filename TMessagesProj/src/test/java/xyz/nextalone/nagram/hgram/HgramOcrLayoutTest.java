package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure JUnit tests for the Android-free OCR box model. */
public class HgramOcrLayoutTest {

    private static HgramOcrLayout.PixelLine pixelLine(int[] box, String text) {
        return new HgramOcrLayout.PixelLine(box, text);
    }

    private static HgramOcrLayout.PixelWord pixelWord(int[] box, String text) {
        return new HgramOcrLayout.PixelWord(box, text);
    }

    private static List<HgramOcrLayout.PixelLine> pixelLines(
            HgramOcrLayout.PixelLine... lines) {
        return Arrays.asList(lines);
    }

    private static HgramOcrLayout normalized(HgramOcrLayout.Line... lines) {
        return HgramOcrLayout.ofNormalized(100, 100, HgramOcrLayout.SPACE_DISPLAY,
                Arrays.asList(lines));
    }

    private static void assertPoint(float expectedX, float expectedY, float[] actual) {
        assertEquals(expectedX, actual[0], 1e-4f);
        assertEquals(expectedY, actual[1], 1e-4f);
    }

    private static void assertInsideUnitSquare(float[] point) {
        assertTrue(point[0] >= 0f && point[0] <= 1f);
        assertTrue(point[1] >= 0f && point[1] <= 1f);
    }

    @Test
    public void fromPixelsNormalizesAndClamps() {
        final HgramOcrLayout.PixelLine line = pixelLine(new int[]{10, 20, 110, 40}, "Hi");
        line.words.add(pixelWord(new int[]{-5, 10, 60, 220}, "Hi"));

        final HgramOcrLayout layout = HgramOcrLayout.fromPixels(
                100, 200, HgramOcrLayout.SPACE_DISPLAY, pixelLines(line));

        assertEquals(100, layout.imageWidth);
        assertEquals(200, layout.imageHeight);
        assertEquals(HgramOcrLayout.SPACE_DISPLAY, layout.space);
        assertFalse(layout.isEmpty());
        final HgramOcrLayout.Line normalizedLine = layout.lines.get(0);
        assertEquals(0.1f, normalizedLine.left, 1e-4f);
        assertEquals(0.1f, normalizedLine.top, 1e-4f);
        assertEquals(1f, normalizedLine.right, 1e-4f);
        assertEquals(0.2f, normalizedLine.bottom, 1e-4f);
        final HgramOcrLayout.Word word = normalizedLine.words.get(0);
        assertEquals(0f, word.left, 1e-4f);
        assertEquals(0.05f, word.top, 1e-4f);
        assertEquals(0.6f, word.right, 1e-4f);
        assertEquals(1f, word.bottom, 1e-4f);
    }

    @Test
    public void dropsDegenerateBoxesAndEmptyWords() {
        final HgramOcrLayout.PixelLine zeroWidth =
                pixelLine(new int[]{50, 50, 50, 60}, "zero");
        final HgramOcrLayout.PixelLine inverted =
                pixelLine(new int[]{60, 60, 50, 70}, "inverted");
        final HgramOcrLayout.PixelLine blank = pixelLine(new int[]{10, 10, 90, 30}, "   ");
        final HgramOcrLayout.PixelLine valid = pixelLine(new int[]{10, 10, 90, 30}, "keep");
        valid.words.add(pixelWord(new int[]{10, 10, 30, 30}, "keep"));
        valid.words.add(pixelWord(new int[]{40, 10, 40, 30}, "degenerate"));
        valid.words.add(pixelWord(new int[]{50, 10, 70, 30}, "   "));
        valid.words.add(pixelWord(null, "null-box"));

        final HgramOcrLayout layout = HgramOcrLayout.fromPixels(
                100, 100, HgramOcrLayout.SPACE_DISPLAY,
                pixelLines(zeroWidth, inverted, blank, valid));

        assertEquals(1, layout.lines.size());
        final HgramOcrLayout.Line line = layout.lines.get(0);
        assertEquals("keep", line.text);
        assertEquals(1, line.words.size());
        assertEquals("keep", line.words.get(0).text);
        assertTrue(HgramOcrLayout.fromPixels(100, 100, HgramOcrLayout.SPACE_DISPLAY,
                pixelLines(zeroWidth)).isEmpty());
    }

    @Test
    public void capsLinesAndWords() {
        final List<HgramOcrLayout.PixelLine> manyLines = new ArrayList<>();
        for (int i = 0; i < HgramOcrLayout.MAX_LINES + 5; i++) {
            final HgramOcrLayout.PixelLine line = pixelLine(new int[]{0, 0, 10, 10}, "line");
            line.words.add(pixelWord(new int[]{0, 0, 10, 10}, "w" + i));
            manyLines.add(line);
        }
        final HgramOcrLayout cappedLines = HgramOcrLayout.fromPixels(
                10, 10, HgramOcrLayout.SPACE_DISPLAY, manyLines);
        assertEquals(HgramOcrLayout.MAX_LINES, cappedLines.lines.size());

        final HgramOcrLayout.PixelLine wide = pixelLine(new int[]{0, 0, 1000, 1000}, "wide");
        for (int i = 0; i < HgramOcrLayout.MAX_WORDS + 5; i++) {
            final int x = i % 1000;
            final int y = (i / 1000) * 10;
            wide.words.add(pixelWord(new int[]{x, y, x + 1, y + 1}, "w" + i));
        }
        final HgramOcrLayout cappedWords = HgramOcrLayout.fromPixels(
                1000, 1000, HgramOcrLayout.SPACE_DISPLAY, pixelLines(wide));
        assertEquals(1, cappedWords.lines.size());
        assertEquals(HgramOcrLayout.MAX_WORDS, cappedWords.lines.get(0).words.size());
    }

    @Test
    public void mapAndUnmapRoundTripAllCombinations() {
        final int[] orientations = {0, 90, 180, 270};
        final int[] inverts = {0, 1, 2};
        final float[][] points = {{0f, 0f}, {1f, 0f}, {0f, 1f}, {1f, 1f}, {0.25f, 0.75f}};
        for (int orientation : orientations) {
            for (int invert : inverts) {
                for (float[] point : points) {
                    final float[] mapped = HgramOcrLayout.mapPoint(
                            point[0], point[1], orientation, invert);
                    assertInsideUnitSquare(mapped);
                    final float[] back = HgramOcrLayout.unmapPoint(
                            mapped[0], mapped[1], orientation, invert);
                    final String where = "orientation=" + orientation + " invert=" + invert;
                    assertEquals(where, point[0], back[0], 1e-4f);
                    assertEquals(where, point[1], back[1], 1e-4f);
                }
            }
        }
        assertPoint(0f, 1f, HgramOcrLayout.mapPoint(0f, 0f, -90, 0));
        assertPoint(0f, 1f, HgramOcrLayout.mapPoint(0f, 0f, 270, 0));
        assertPoint(0f, 1f, HgramOcrLayout.mapPoint(0f, 0f, 630, 0));
    }

    @Test
    public void exifMappingsFollowDocumentedValues() {
        // EXIF 6 / ROTATE_90: raw top-left becomes display top-right.
        assertPoint(1f, 0f, HgramOcrLayout.mapPoint(0f, 0f, 90, 0));
        assertPoint(1f, 1f, HgramOcrLayout.mapPoint(0f, 0f, 90, 2));
        // EXIF 5 / TRANSPOSE: orientation 270 + vertical mirror keeps top-left.
        assertPoint(0f, 0f, HgramOcrLayout.mapPoint(0f, 0f, 270, 2));
        assertPoint(1f, 1f, HgramOcrLayout.mapPoint(0f, 0f, 180, 0));
        assertPoint(0f, 0f, HgramOcrLayout.unmapPoint(1f, 0f, 90, 0));
    }

    @Test
    public void hitWordBeatsLineAndGapFallsBackToLine() {
        final HgramOcrLayout.Word word =
                new HgramOcrLayout.Word(0.2f, 0.2f, 0.4f, 0.3f, "word");
        final HgramOcrLayout.Line line = new HgramOcrLayout.Line(
                0.1f, 0.2f, 0.9f, 0.3f, "word in line", Collections.singletonList(word));
        final HgramOcrLayout layout = normalized(line);

        assertNotNull(layout.hitWord(0.3f, 0.25f, 0f, 0f));
        assertEquals("word", layout.hitWord(0.3f, 0.25f, 0f, 0f).text);
        assertNotNull(layout.hitLine(0.3f, 0.25f, 0f, 0f));

        assertNull(layout.hitWord(0.7f, 0.25f, 0f, 0f));
        final HgramOcrLayout.Line gapLine = layout.hitLine(0.7f, 0.25f, 0f, 0f);
        assertNotNull(gapLine);
        assertEquals("word in line", gapLine.text);
    }

    @Test
    public void hitHonorsPaddingAndRejectsOutside() {
        final HgramOcrLayout.Word word =
                new HgramOcrLayout.Word(0.2f, 0.2f, 0.4f, 0.3f, "word");
        final HgramOcrLayout.Line line = new HgramOcrLayout.Line(
                0.1f, 0.2f, 0.9f, 0.3f, "line", Collections.singletonList(word));
        final HgramOcrLayout layout = normalized(line);

        assertNull(layout.hitWord(0.45f, 0.25f, 0f, 0f));
        assertNotNull(layout.hitWord(0.45f, 0.25f, 0.06f, 0f));
        assertNull(layout.hitLine(0.05f, 0.25f, 0f, 0f));
        assertNotNull(layout.hitLine(0.05f, 0.25f, 0.06f, 0f));
        assertNull(layout.hitWord(0.5f, 0.5f, 0f, 0f));
        assertNull(layout.hitLine(0.5f, 0.5f, 0f, 0f));
        assertNull(layout.hitWord(Float.NaN, 0.25f, 0f, 0f));
        assertNull(layout.hitLine(0.25f, Float.NaN, 0f, 0f));
    }

    @Test
    public void matchesAspectUsesTwoPercentTolerance() {
        final HgramOcrLayout display = HgramOcrLayout.ofNormalized(
                1600, 1200, HgramOcrLayout.SPACE_DISPLAY,
                Collections.<HgramOcrLayout.Line>emptyList());
        assertTrue(display.matchesAspect(1920, 1440));
        assertTrue(display.matchesAspect(1010, 750));
        assertFalse(display.matchesAspect(1100, 750));
        assertFalse(display.matchesAspect(0, 750));
        assertFalse(display.matchesAspect(1920, 0));

        final HgramOcrLayout raw = HgramOcrLayout.ofNormalized(
                1600, 900, HgramOcrLayout.SPACE_RAW,
                Collections.<HgramOcrLayout.Line>emptyList());
        assertTrue(raw.matchesAspect(1600, 900));
        assertTrue("raw may be displayed after a 90 degree rotation",
                raw.matchesAspect(900, 1600));
        assertFalse(raw.matchesAspect(1000, 1000));
    }

    @Test
    public void rtlLineTextIsNotReorderedOrJoined() {
        final HgramOcrLayout.Word left =
                new HgramOcrLayout.Word(0.1f, 0.1f, 0.3f, 0.2f, "עולם");
        final HgramOcrLayout.Word right =
                new HgramOcrLayout.Word(0.7f, 0.1f, 0.9f, 0.2f, "שלום");
        final HgramOcrLayout.Line line = new HgramOcrLayout.Line(
                0.1f, 0.1f, 0.9f, 0.2f, "שלום עולם\r\n", Arrays.asList(left, right));
        final HgramOcrLayout layout = normalized(line);

        assertEquals("שלום עולם", layout.lines.get(0).text);
        assertNotEquals("line text must not be rebuilt by joining words",
                "עולם שלום", layout.lines.get(0).text);
        assertEquals("עולם", layout.lines.get(0).words.get(0).text);
    }

    @Test
    public void emptyLayoutAndCollectionsAreUnmodifiable() {
        final HgramOcrLayout empty = HgramOcrLayout.fromPixels(
                10, 10, HgramOcrLayout.SPACE_DISPLAY,
                Collections.<HgramOcrLayout.PixelLine>emptyList());
        assertTrue(empty.isEmpty());
        assertNull(empty.hitWord(0.5f, 0.5f, 0f, 0f));
        assertNull(empty.hitLine(0.5f, 0.5f, 0f, 0f));
        assertFalse(empty.matchesAspect(0, 0));

        final HgramOcrLayout.Word word =
                new HgramOcrLayout.Word(0.1f, 0.1f, 0.2f, 0.2f, "word");
        final HgramOcrLayout.Line line = new HgramOcrLayout.Line(
                0.1f, 0.1f, 0.3f, 0.3f, "word", Collections.singletonList(word));
        final HgramOcrLayout layout = normalized(line);
        try {
            layout.lines.add(line);
            fail("lines must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            layout.lines.get(0).words.add(word);
            fail("words must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
