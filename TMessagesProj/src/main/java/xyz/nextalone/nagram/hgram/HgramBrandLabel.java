package xyz.nextalone.nagram.hgram;

/** Pure display rules for the Hgram wordmark in compact headers. */
public final class HgramBrandLabel {

    private HgramBrandLabel() {
    }

    /**
     * The header mark itself renders the leading "H", so the brand label starts at "gram".
     * Only the normalized brand value is shortened; user-defined titles are returned as-is.
     */
    public static String displayText(String title) {
        if (title == null) {
            return null;
        }
        return title.equalsIgnoreCase("Hgram") ? "gram" : title;
    }
}
