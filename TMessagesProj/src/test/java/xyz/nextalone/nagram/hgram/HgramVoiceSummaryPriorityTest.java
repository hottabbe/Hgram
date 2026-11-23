package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HgramVoiceSummaryPriorityTest {

    @Test
    public void textCaptionSummaryClosesWhenVoiceTranscriptBecomesEligible() {
        // The transcript turned final while the text summary was open: the tap closes the text
        // summary instead of opening the voice one.
        assertEquals(HgramVoiceSummaryPriority.Action.CLOSE_TEXT,
                HgramVoiceSummaryPriority.decide(true, false, false, false, true, true));

        // After the close the same tap route opens the voice summary.
        assertEquals(HgramVoiceSummaryPriority.Action.VOICE,
                HgramVoiceSummaryPriority.decide(false, false, false, false, true, true));

        // A text request in flight only redraws the loading look.
        assertEquals(HgramVoiceSummaryPriority.Action.REFRESH_TEXT,
                HgramVoiceSummaryPriority.decide(false, true, false, false, true, true));

        // No voice state at all: the Hgram text summary is the only candidate.
        assertEquals(HgramVoiceSummaryPriority.Action.TEXT,
                HgramVoiceSummaryPriority.decide(false, false, false, false, false, true));

        // Nothing open, loading or eligible.
        assertEquals(HgramVoiceSummaryPriority.Action.NONE,
                HgramVoiceSummaryPriority.decide(false, false, false, false, false, false));
    }

    @Test
    public void voiceShowingOrInFlightKeepsVoiceRoute() {
        assertEquals(HgramVoiceSummaryPriority.Action.VOICE,
                HgramVoiceSummaryPriority.decide(false, false, false, true, false, true));
        assertEquals(HgramVoiceSummaryPriority.Action.VOICE,
                HgramVoiceSummaryPriority.decide(false, false, true, false, false, false));
    }
}
