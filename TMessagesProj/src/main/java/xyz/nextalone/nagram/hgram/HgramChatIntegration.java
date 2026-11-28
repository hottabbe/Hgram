package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.DialogsActivity;

/** Minimal upstream-to-Hgram integration points; feature logic stays in the Hgram classes. */
public final class HgramChatIntegration {

    private HgramChatIntegration() {
    }

    public static boolean canShowSummary(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (HgramVoiceSummarizer.isVoiceSummaryEnabled()
                && (HgramVoiceSummarizer.canSummarize(message)
                || HgramVoiceSummarizer.isShowing(message))) {
            return true;
        }
        if (message.messageOwner.summarizedOpen && message.messageOwner.summaryText != null) {
            return true;
        }
        return HgramConfig.isSummarizerEnabled()
                && HgramSummarizer.canSummarize(message, null, true);
    }

    /** Returns true when the Hgram summary path consumed the tap. */
    public static boolean didPressSummarize(ChatMessageCell cell, MessageObject message) {
        if (cell == null || message == null) {
            return false;
        }
        final Runnable refresh = () -> {
            message.updateTranslation(true);
            cell.invalidate();
        };
        if (HgramVoiceSummarizer.isVoiceSummaryEnabled()
                && (HgramVoiceSummarizer.isShowing(message)
                || HgramVoiceSummarizer.canSummarize(message)
                || HgramVoiceSummarizer.isInFlight(message))) {
            return HgramVoiceSummarizer.toggle(message, refresh);
        }
        if (HgramConfig.isSummarizerEnabled()
                && HgramSummarizer.canSummarize(message, null, true)) {
            HgramSummarizer.invalidateStaleSummary(message, null);
            return HgramSummarizer.toggle(message, null, refresh);
        }
        return false;
    }

    public static void onDialogsResume(DialogsActivity activity) {
        HgramSecretSpace.warmUp();
    }
}
