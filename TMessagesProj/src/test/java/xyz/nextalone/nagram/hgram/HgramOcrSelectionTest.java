package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HgramOcrSelectionTest {

    private static HgramOcrLayout layout(String... lines) {
        final List<HgramOcrLayout.Line> source = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            final String[] words = lines[i].split(" ");
            final List<HgramOcrLayout.Word> boxes = new ArrayList<>();
            for (int w = 0; w < words.length; w++) {
                boxes.add(new HgramOcrLayout.Word(0.1f + w * 0.2f, i * 0.2f,
                        0.25f + w * 0.2f, i * 0.2f + 0.1f, words[w]));
            }
            source.add(new HgramOcrLayout.Line(0.1f, i * 0.2f, 0.9f,
                    i * 0.2f + 0.1f, lines[i], boxes));
        }
        return HgramOcrLayout.ofNormalized(100, 100, HgramOcrLayout.SPACE_DISPLAY, source);
    }

    @Test
    public void wordRangeJoinsWordsWithSpacesAndLinesWithNewlines() {
        final HgramOcrLayout layout = layout("one two", "three four");
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        assertEquals(4, selection.wordCount());
        selection.selectWord(layout.lines.get(0).words.get(0));
        selection.extendTo(layout.lines.get(1).words.get(1));
        assertEquals(0, selection.startIndex());
        assertEquals(3, selection.endIndex());
        assertEquals("one two\nthree four", selection.text());
    }

    @Test
    public void reverseDragKeepsDocumentOrder() {
        final HgramOcrLayout layout = layout("one two", "three four");
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        selection.selectWord(layout.lines.get(1).words.get(1));
        selection.extendAnchorTo(layout.lines.get(0).words.get(0));
        assertEquals(0, selection.startIndex());
        assertEquals(3, selection.endIndex());
        assertEquals("one two\nthree four", selection.text());
    }

    @Test
    public void lineSelectionCoversWholeLineAndKeepsRtlOrder() {
        final HgramOcrLayout layout = layout("שלום עולם", "next line");
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        selection.selectLine(layout.lines.get(0));
        assertEquals("שלום עולם", selection.text());
        assertTrue(selection.hasSelection());
        assertEquals(2, selection.selectedWords().size());

        selection.clear();
        assertFalse(selection.hasSelection());
        assertEquals("", selection.text());
    }

    @Test
    public void singleWordSelectionAndRepeatedTapBounds() {
        final HgramOcrLayout layout = layout("single", "word");
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        selection.selectWord(layout.lines.get(0).words.get(0));
        assertEquals("single", selection.text());
        assertEquals(selection.startIndex(), selection.endIndex());
        selection.clear();
        assertEquals(-1, selection.startIndex());
        assertEquals(-1, selection.endIndex());
    }

    @Test
    public void focusShrinkKeepsSelectionAndStopsAtAnchor() {
        final HgramOcrLayout layout = layout("echo foxtrot");
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        selection.selectWord(layout.lines.get(0).words.get(0));
        selection.extendTo(layout.lines.get(0).words.get(1));
        assertEquals("echo foxtrot", selection.text());

        selection.extendTo(layout.lines.get(0).words.get(0));
        assertTrue(selection.hasSelection());
        assertEquals("echo", selection.text());
        assertEquals(0, selection.startIndex());
        assertEquals(0, selection.endIndex());
    }

    @Test
    public void anchorShrinkKeepsSelectionAndStopsAtFocus() {
        final HgramOcrLayout layout = layout("echo foxtrot");
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        selection.selectWord(layout.lines.get(0).words.get(1));
        selection.extendAnchorTo(layout.lines.get(0).words.get(0));
        assertEquals("echo foxtrot", selection.text());

        selection.extendAnchorTo(layout.lines.get(0).words.get(1));
        assertTrue(selection.hasSelection());
        assertEquals("foxtrot", selection.text());
        assertEquals(1, selection.startIndex());
        assertEquals(1, selection.endIndex());
    }

    @Test
    public void crossingEdgesSwapAndKeepContinuousSelection() {
        final HgramOcrLayout layout = layout("one two three");
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        selection.selectWord(layout.lines.get(0).words.get(1));
        selection.extendTo(layout.lines.get(0).words.get(2));
        assertEquals(1, selection.startIndex());
        assertEquals(2, selection.endIndex());
        assertEquals("two three", selection.text());

        // The same logical focus crosses left of the anchor and keeps the word under the finger.
        selection.extendTo(layout.lines.get(0).words.get(0));
        assertEquals(0, selection.startIndex());
        assertEquals(1, selection.endIndex());
        assertEquals("one two", selection.text());

        // Continue the same focus back to the anchor: shrink to one word, selection stays.
        selection.extendTo(layout.lines.get(0).words.get(1));
        assertEquals(1, selection.startIndex());
        assertEquals(1, selection.endIndex());
        assertEquals("two", selection.text());

        // The same logical anchor crosses right of the focus, then left of it.
        selection.extendAnchorTo(layout.lines.get(0).words.get(2));
        assertEquals(1, selection.startIndex());
        assertEquals(2, selection.endIndex());
        assertEquals("two three", selection.text());
        selection.extendAnchorTo(layout.lines.get(0).words.get(0));
        assertEquals(0, selection.startIndex());
        assertEquals(1, selection.endIndex());
        assertEquals("one two", selection.text());
    }

    @Test
    public void singleWordDragCannotClearSelection() {
        final HgramOcrLayout layout = layout("solo");
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        selection.selectWord(layout.lines.get(0).words.get(0));
        selection.extendTo(layout.lines.get(0).words.get(0));
        selection.extendAnchorTo(layout.lines.get(0).words.get(0));
        assertTrue(selection.hasSelection());
        assertEquals("solo", selection.text());

        selection.extendToNearest(layout.lines.get(0), 0.5f);
        assertTrue(selection.hasSelection());
        assertEquals("solo", selection.text());
    }

    @Test
    public void gapDragSelectsNearestWordInLine() {
        final HgramOcrLayout layout = layout("one two three");
        final HgramOcrLayout.Line line = layout.lines.get(0);
        final HgramOcrSelection selection = new HgramOcrSelection(layout);
        selection.selectWord(line.words.get(0));

        selection.extendToNearest(line, 0.29f);
        assertEquals(1, selection.focus());
        assertEquals("one two", selection.text());

        selection.extendAnchorToNearest(line, 0.51f);
        assertEquals(2, selection.anchor());
        assertEquals(1, selection.startIndex());
        assertEquals(2, selection.endIndex());
        assertEquals("two three", selection.text());
    }
}
