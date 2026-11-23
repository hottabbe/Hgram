package xyz.nextalone.nagram.hgram;

/**
 * Pure state of the ✨ summarize button while a request is in flight. Kept outside the view so the
 * loading look can be asserted without a recycled cell; button geometry and visibility rules stay
 * where they were.
 */
public final class HgramSummaryButtonState {

    private HgramSummaryButtonState() {
    }

    /**
     * The loading look replaces the icon only inside an already visible button and never over an
     * open summary: a threshold-hidden button must not become visible because a request is running.
     *
     * @param drawSummarizeButton the button is visible under the unchanged decision in the cell
     * @param inFlight            the message has a request in {@code HgramSummarizer.isInFlight}
     * @param summaryOpen         the message currently shows an existing summary
     */
    public static boolean isLoading(boolean drawSummarizeButton, boolean inFlight, boolean summaryOpen) {
        return drawSummarizeButton && inFlight && !summaryOpen;
    }
}
