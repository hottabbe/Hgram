package xyz.nextalone.nagram.hgram;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.MessageObject;

/** Pure decision helpers for the voice/audio notification actions. */
public final class HgramVoiceNotificationActions {

    private HgramVoiceNotificationActions() {
    }

    public static boolean isVoiceLike(MessageObject message) {
        return message != null && (message.isVoice() || message.isRoundVideo() || message.isMusic());
    }

    public static boolean shouldOffer(boolean voiceLike, boolean summary, boolean story) {
        return voiceLike && !summary && !story;
    }

    public static boolean canTranscribe(boolean engineInstalled, boolean hidden, boolean antiPeek) {
        return engineInstalled && !hidden && !antiPeek;
    }

    /**
     * Release gate: the "Text" action stays hidden until transcription is written back
     * into the original notification's BigText.
     */
    public static boolean isTranscriptionActionAvailable() {
        return false;
    }

    public static String engine() {
        return HgramConfig.getSttEngine();
    }

    public static PendingIntent listenIntent(Context context, int account, long dialogId,
                                             int messageId, String title, int requestCode) {
        return HgramVoicePlaybackService.playbackIntent(context, account, dialogId, messageId,
                title, requestCode);
    }

    public static PendingIntent transcribeIntent(Context context, int account, long dialogId,
                                                 int messageId, String title, int requestCode) {
        Intent intent = new Intent(context, HgramVoicePlaybackService.class);
        intent.setAction(HgramVoicePlaybackService.ACTION_TRANSCRIBE);
        intent.putExtra(HgramVoicePlaybackService.EXTRA_ACCOUNT, account);
        intent.putExtra(HgramVoicePlaybackService.EXTRA_DIALOG_ID, dialogId);
        intent.putExtra(HgramVoicePlaybackService.EXTRA_MESSAGE_ID, messageId);
        intent.putExtra(HgramVoicePlaybackService.EXTRA_TITLE, title);
        return PendingIntent.getService(context, requestCode, intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
