package xyz.nextalone.nagram.hgram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure word-range selection over one OCR layout. No Android types, no bitmap. */
public final class HgramOcrSelection {

    public static final class WordRef {
        public final int index;
        public final int lineIndex;
        public final int wordIndex;
        public final HgramOcrLayout.Line line;
        public final HgramOcrLayout.Word word;

        WordRef(int index, int lineIndex, int wordIndex,
                HgramOcrLayout.Line line, HgramOcrLayout.Word word) {
            this.index = index;
            this.lineIndex = lineIndex;
            this.wordIndex = wordIndex;
            this.line = line;
            this.word = word;
        }
    }

    private final List<WordRef> words = new ArrayList<>();
    private int anchor = -1;
    private int focus = -1;

    public HgramOcrSelection(HgramOcrLayout layout) {
        if (layout != null) {
            for (int lineIndex = 0; lineIndex < layout.lines.size(); lineIndex++) {
                final HgramOcrLayout.Line line = layout.lines.get(lineIndex);
                for (int wordIndex = 0; wordIndex < line.words.size(); wordIndex++) {
                    words.add(new WordRef(words.size(), lineIndex, wordIndex, line, line.words.get(wordIndex)));
                }
            }
        }
    }

    public int wordCount() {
        return words.size();
    }

    public boolean hasSelection() {
        return anchor >= 0 && focus >= 0 && !words.isEmpty();
    }

    public int startIndex() {
        return hasSelection() ? Math.min(anchor, focus) : -1;
    }

    public int endIndex() {
        return hasSelection() ? Math.max(anchor, focus) : -1;
    }

    public int anchor() {
        return anchor;
    }

    public int focus() {
        return focus;
    }

    public WordRef wordAt(int index) {
        return index >= 0 && index < words.size() ? words.get(index) : null;
    }

    public WordRef startWord() {
        final int start = startIndex();
        return start < 0 ? null : words.get(start);
    }

    public WordRef endWord() {
        final int end = endIndex();
        return end < 0 ? null : words.get(end);
    }

    public List<WordRef> selectedWords() {
        if (!hasSelection()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(words.subList(startIndex(), endIndex() + 1)));
    }

    public int indexOf(HgramOcrLayout.Word word) {
        if (word == null) return -1;
        for (WordRef ref : words) {
            if (ref.word == word) return ref.index;
        }
        return -1;
    }

    public int firstIndexOfLine(HgramOcrLayout.Line line) {
        if (line == null) return -1;
        for (WordRef ref : words) {
            if (ref.line == line) return ref.index;
        }
        return -1;
    }

    public int lastIndexOfLine(HgramOcrLayout.Line line) {
        if (line == null) return -1;
        int last = -1;
        for (WordRef ref : words) {
            if (ref.line == line) last = ref.index;
        }
        return last;
    }

    public void selectWord(HgramOcrLayout.Word word) {
        final int index = indexOf(word);
        if (index >= 0) {
            anchor = focus = index;
        }
    }

    public void selectLine(HgramOcrLayout.Line line) {
        final int first = firstIndexOfLine(line);
        final int last = lastIndexOfLine(line);
        if (first >= 0 && last >= 0) {
            anchor = first;
            focus = last;
        }
    }

    public void extendTo(HgramOcrLayout.Word word) {
        final int index = indexOf(word);
        if (index >= 0) focus = index;
    }

    public void extendToLine(HgramOcrLayout.Line line) {
        if (!hasSelection()) return;
        final int first = firstIndexOfLine(line);
        final int last = lastIndexOfLine(line);
        if (first < 0 || last < 0) return;
        // Keep the anchor side; dragging through a line chooses its near edge.
        final int current = focus;
        if (current <= first) {
            focus = first;
        } else if (current >= last) {
            focus = last;
        }
    }

    public void extendToNearest(HgramOcrLayout.Line line, float nx) {
        final int index = nearestWordIndex(line, nx);
        if (index >= 0) focus = index;
    }

    public void extendAnchorToNearest(HgramOcrLayout.Line line, float nx) {
        final int index = nearestWordIndex(line, nx);
        if (index >= 0) anchor = index;
    }

    private int nearestWordIndex(HgramOcrLayout.Line line, float nx) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (WordRef ref : words) {
            if (ref.line != line) continue;
            final float center = (ref.word.left + ref.word.right) * 0.5f;
            final float distance = Math.abs(center - nx);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = ref.index;
            }
        }
        return best;
    }

    public void extendAnchorTo(HgramOcrLayout.Word word) {
        final int index = indexOf(word);
        if (index >= 0) anchor = index;
    }

    public void extendAnchorToLine(HgramOcrLayout.Line line) {
        if (!hasSelection()) return;
        final int first = firstIndexOfLine(line);
        final int last = lastIndexOfLine(line);
        if (first < 0 || last < 0) return;
        if (anchor <= first) {
            anchor = first;
        } else if (anchor >= last) {
            anchor = last;
        }
    }

    public void clear() {
        anchor = focus = -1;
    }

    /** Words joined by spaces; a line break replaces the space between different lines. */
    public String text() {
        if (!hasSelection()) return "";
        final StringBuilder out = new StringBuilder();
        int previousLine = -1;
        for (WordRef ref : selectedWords()) {
            if (out.length() > 0) {
                out.append(ref.lineIndex == previousLine ? ' ' : '\n');
            }
            out.append(ref.word.text);
            previousLine = ref.lineIndex;
        }
        return out.toString();
    }
}
