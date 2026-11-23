package xyz.nextalone.nagram.hgram;

/**
 * Pure tap routing for the ✨ button in a private dialog (VOICE-SUMM-2).
 *
 * <p>An open text summary must be closed before the voice summary gets a chance: a transcript that
 * became final while the text summary was open makes {@code canSummarize()} true, and the tap used
 * to be stolen by the voice branch, leaving the text summary stuck open.</p>
 */
public final class HgramVoiceSummaryPriority {

    public enum Action {
        /** The text summary is open: close it (local marker or persisted path). */
        CLOSE_TEXT,
        /** A text summary request is in flight: only redraw the loading look. */
        REFRESH_TEXT,
        /** Open/close the process-local voice transcript summary. */
        VOICE,
        /** Start/close the Hgram text summary. */
        TEXT,
        /** Nothing to toggle here; the caller keeps its TranslateController fallback. */
        NONE
    }

    private HgramVoiceSummaryPriority() {
    }

    public static Action decide(boolean textOpen, boolean textInFlight, boolean voiceShowing,
                                boolean voiceInFlight, boolean voiceEligible, boolean textEligible) {
        if (textOpen) {
            return Action.CLOSE_TEXT;
        }
        if (textInFlight) {
            return Action.REFRESH_TEXT;
        }
        if (voiceShowing || voiceInFlight || voiceEligible) {
            return Action.VOICE;
        }
        if (textEligible) {
            return Action.TEXT;
        }
        return Action.NONE;
    }
}
