package xyz.nextalone.nagram.hgram;

/** Chooses what the fallback OCR panel should show after boxes/selection were dropped. */
public final class HgramOcrDisplayText {

    private HgramOcrDisplayText() {
    }

    /** Full recognition text wins; the selected fragment is only a last resort. */
    public static String panelText(String fullText, String selectedText) {
        return fullText != null && !fullText.isEmpty() ? fullText : selectedText;
    }
}
