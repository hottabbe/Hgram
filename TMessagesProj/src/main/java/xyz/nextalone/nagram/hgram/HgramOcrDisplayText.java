package xyz.nextalone.nagram.hgram;

/** Chooses what the fallback OCR panel should show after boxes/selection were dropped. */
public final class HgramOcrDisplayText {

    private HgramOcrDisplayText() {
    }

    /** Full recognition text wins; the selected fragment is only a last resort. */
    public static String panelText(String fullText, String selectedText) {
        return fullText != null && !fullText.isEmpty() ? fullText : selectedText;
    }

    /** Boxes are authoritative; only a missing/empty/mismatched layout may open the text panel. */
    public static boolean shouldUseLayout(HgramOcrLayout layout, boolean editing, boolean video,
                                           int bitmapWidth, int bitmapHeight) {
        return layout != null && !layout.isEmpty() && !editing && !video
                && layout.matchesAspect(bitmapWidth, bitmapHeight);
    }
}
