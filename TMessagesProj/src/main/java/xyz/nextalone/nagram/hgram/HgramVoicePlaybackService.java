package xyz.nextalone.nagram.hgram;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal foreground playback for voice/round-video/audio notification actions.
 * It never touches message read state or dialog counters.
 */
public final class HgramVoicePlaybackService extends Service {

    public static final String ACTION_TOGGLE = "xyz.nextalone.nagram.hgram.VOICE_TOGGLE";
    public static final String ACTION_TRANSCRIBE = "xyz.nextalone.nagram.hgram.VOICE_TRANSCRIBE";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_DIALOG_ID = "dialog_id";
    public static final String EXTRA_MESSAGE_ID = "message_id";
    public static final String EXTRA_TITLE = "title";

    private static final String CHANNEL_ID = "hgram_voice_playback";
    private static final int NOTIFICATION_ID = 0x0f3a0201;
    private static final long PAUSE_STOP_MS = 30_000L;
    private static final long[] VIBRATION = new long[0];
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();

    /**
     * Immutable identity of one transcription attempt. Every new attempt gets a newer
     * {@link #generation}, so a late result of a previous message can neither update the current
     * notification nor reach the current message.
     */
    private static final class Request {
        final int account;
        final long dialogId;
        final int messageId;
        final long generation;
        final HgramVoiceNotificationText.Key key;

        Request(int account, long dialogId, int messageId, long generation) {
            this.account = account;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.generation = generation;
            this.key = HgramVoiceNotificationText.key(account, dialogId, messageId);
        }
    }

    private MediaPlayer player;
    private MediaSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopOnPause = this::stopPlayback;
    private int account;
    private long dialogId;
    private int messageId;
    private String title;
    private boolean prepared;
    private boolean paused;
    private CharSequence transcriptionText;
    private volatile Request activeRequest;
    private final NotificationCenter.NotificationCenterDelegate transcriptionObserver =
            (id, observerAccount, args) -> onTranscriptionUpdate(observerAccount, args);

    public static void start(Context context, int account, long dialogId, int messageId, String title) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, HgramVoicePlaybackService.class);
        intent.setAction(ACTION_TOGGLE);
        intent.putExtra(EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_DIALOG_ID, dialogId);
        intent.putExtra(EXTRA_MESSAGE_ID, messageId);
        intent.putExtra(EXTRA_TITLE, title);
        try {
            context.startForegroundService(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static PendingIntent playbackIntent(Context context, int account, long dialogId,
                                                int messageId, String title, int requestCode) {
        Intent intent = new Intent(context, HgramVoicePlaybackService.class);
        intent.setAction(ACTION_TOGGLE);
        intent.putExtra(EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_DIALOG_ID, dialogId);
        intent.putExtra(EXTRA_MESSAGE_ID, messageId);
        intent.putExtra(EXTRA_TITLE, title);
        return PendingIntent.getService(context, requestCode, intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        session = new MediaSession(this, "HgramVoice");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stopPlayback();
            }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopPlayback();
            return START_NOT_STICKY;
        }
        // A new command always supersedes the transcription of the previous message/account:
        // otherwise a stale worker could publish into the freshly opened notification.
        cancelActiveTranscription();
        account = intent.getIntExtra(EXTRA_ACCOUNT, account);
        dialogId = intent.getLongExtra(EXTRA_DIALOG_ID, dialogId);
        messageId = intent.getIntExtra(EXTRA_MESSAGE_ID, messageId);
        String extraTitle = intent.getStringExtra(EXTRA_TITLE);
        if (extraTitle != null) {
            title = extraTitle;
        }
        if (ACTION_TRANSCRIBE.equals(intent.getAction())) {
            if (player == null) {
                // Transcription must not create a playback notification: the same id carries a
                // plain foreground notification without MediaStyle and without playback actions.
                transcriptionText = null;
                startForegroundCompat(buildTranscriptionNotification());
            }
            startTranscription();
            return START_NOT_STICKY;
        }
        startForegroundCompat(buildNotification());
        if (player == null) {
            prepareAndPlay();
        } else if (paused) {
            play();
        } else {
            pause();
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.HgramVoicePlaybackChannel), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setVibrationPattern(VIBRATION);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /** Playback-only notification: never shows the transcription, so no stale text is re-posted. */
    private Notification buildNotification() {
        Intent open = new Intent();
        open.setAction(Intent.ACTION_MAIN);
        open.setClassName(getPackageName(), "org.telegram.ui.LaunchActivity");
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent toggle = playbackIntent(this, account, dialogId, messageId, title,
                NOTIFICATION_ID);
        int icon = paused || player == null ? R.drawable.msg_round_play_m : R.drawable.msg_round_pause_m;
        String actionText = paused || player == null
                ? getString(R.string.HgramVoiceActionListen)
                : getString(R.string.HgramVoiceActionPause);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(NotificationsController.getNotificationIconResId())
                .setContentTitle(title == null ? getString(R.string.HgramVoicePlaybackChannel) : title)
                .setContentText(getString(R.string.HgramVoicePlaybackRunning))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setDeleteIntent(contentIntent)
                .addAction(icon, actionText, toggle)
                .setStyle(new MediaStyle().setShowActionsInCompactView(0))
                .build();
    }

    /** Transcription-only foreground notification: same id, no playback actions, no MediaStyle. */
    private Notification buildTranscriptionNotification() {
        Intent open = new Intent();
        open.setAction(Intent.ACTION_MAIN);
        open.setClassName(getPackageName(), "org.telegram.ui.LaunchActivity");
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        CharSequence text;
        if (!canShowText(account, dialogId)) {
            // The foreground copy follows the same privacy rule as the dialog notification.
            text = getString(R.string.HgramAntiPeekHidden);
        } else if (TextUtils.isEmpty(transcriptionText)) {
            text = getString(R.string.HgramVoiceTranscribing);
        } else {
            text = transcriptionText;
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(NotificationsController.getNotificationIconResId())
                .setContentTitle(title == null ? getString(R.string.HgramVoicePlaybackChannel) : title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setDeleteIntent(contentIntent)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private MessageObject resolveMessage() {
        return resolveMessage(account, dialogId, messageId);
    }

    private MessageObject resolveMessage(int targetAccount, long targetDialogId, int targetMessageId) {
        try {
            TLRPC.Message message = MessagesStorage.getInstance(targetAccount).getMessage(targetDialogId, targetMessageId);
            return message == null ? null : new MessageObject(targetAccount, message, false, false);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private File resolveFile(MessageObject message) {
        if (message == null || message.getDocument() == null) {
            return null;
        }
        TLRPC.Document document = message.getDocument();
        if (document.localPath != null && !document.localPath.isEmpty()) {
            File local = new File(document.localPath);
            if (local.isFile()) {
                return local;
            }
        }
        try {
            FileLoader loader = FileLoader.getInstance(account);
            File file = loader.getPathToAttach(document, null, false, true);
            if (file != null && file.isFile()) {
                return file;
            }
            file = loader.getPathToAttach(document, true);
            if (file != null && file.isFile()) {
                return file;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private void prepareAndPlay() {
        MessageObject message = resolveMessage();
        File file = resolveFile(message);
        if (file == null) {
            stopPlayback();
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                prepared = true;
                mp.start();
                paused = false;
                upload();
                updateSessionState();
            });
            player.setOnCompletionListener(mp -> stopPlayback());
            player.setOnErrorListener((mp, what, extra) -> {
                stopPlayback();
                return true;
            });
            player.prepareAsync();
        } catch (Throwable e) {
            FileLog.e(e);
            stopPlayback();
        }
    }

    private void play() {
        handler.removeCallbacks(stopOnPause);
        if (player == null) {
            prepareAndPlay();
        } else if (prepared) {
            player.start();
        }
        paused = false;
        upload();
        updateSessionState();
    }

    private void pause() {
        if (player != null && prepared) {
            player.pause();
        }
        paused = true;
        upload();
        updateSessionState();
        handler.postDelayed(stopOnPause, PAUSE_STOP_MS);
    }

    private void updateSessionState() {
        if (session == null) {
            return;
        }
        int state = player == null ? PlaybackState.STATE_NONE
                : paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP)
                .setState(state, player == null ? 0 : player.getCurrentPosition(), 1f)
                .build());
    }

    private void upload() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void startTranscription() {
        final Request request = new Request(account, dialogId, messageId,
                REQUEST_SEQUENCE.incrementAndGet());
        activeRequest = request;
        if (!canShowText(request)) {
            // Privacy first: the worker is never started and only the neutral text is published.
            publish(getString(R.string.HgramAntiPeekHidden), false, request);
            return;
        }
        MessageObject message = resolveMessage();
        if (message == null || !HgramOfflineTranscriber.hasAnyModelInstalled()
                || !HgramConfig.isOfflineTranscriptionEnabled()) {
            failTranscription(request);
            return;
        }
        final String cachedText = HgramVoiceNotificationText.cached(request.key);
        if (!TextUtils.isEmpty(cachedText)) {
            publish(HgramVoiceNotificationText.formatBigText(
                    baseText(message), cachedText, HgramVoiceNotificationText.BIG_TEXT_LIMIT), true, request);
            finishTranscription();
            return;
        }
        if (HgramOfflineTranscriber.isTranscribing(message)) {
            // A UI-initiated run for the same message would swallow our callback (start()
            // returns true without registering it), so stop it before starting a new one.
            HgramOfflineTranscriber.cancel(message);
        }
        publish(getString(R.string.HgramVoiceTranscribing), true, request);
        if (activeRequest != request) {
            return;
        }
        registerTranscriptionObserver(request);
        AndroidUtilities.runOnUIThread(() -> {
            if (activeRequest != request) {
                return;
            }
            final boolean started = HgramOfflineTranscriber.start(this, message,
                    new HgramOfflineTranscriber.ResultCallback() {
                        @Override
                        public void onPartial(String text) {
                            // Partials are never published: only the progress text is shown.
                        }

                        @Override
                        public void onFinished(String text, boolean cancelled, int error) {
                            onTranscriptionFinished(request, message, text, cancelled);
                        }
                    });
            if (!started) {
                failTranscription(request);
            }
        });
    }

    private void onTranscriptionFinished(Request request, MessageObject message,
                                         String text, boolean cancelled) {
        if (request != activeRequest) {
            return;
        }
        if (cancelled) {
            // Cancelled on purpose: drop the progress notification without text or error.
            finishTranscription();
            return;
        }
        if (!TextUtils.isEmpty(text)) {
            HgramVoiceNotificationText.cache(request.key, text);
            publish(HgramVoiceNotificationText.formatBigText(
                    baseText(message), text, HgramVoiceNotificationText.BIG_TEXT_LIMIT), true, request);
            finishTranscription();
        } else {
            failTranscription(request);
        }
    }

    private void registerTranscriptionObserver(Request request) {
        removeTranscriptionObserver(request.account);
        try {
            NotificationCenter.getInstance(request.account)
                    .addObserver(transcriptionObserver, NotificationCenter.voiceTranscriptionUpdate);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void removeTranscriptionObserver(int observerAccount) {
        try {
            NotificationCenter.getInstance(observerAccount)
                    .removeObserver(transcriptionObserver, NotificationCenter.voiceTranscriptionUpdate);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Stops the worker and drops the observer behind the current request. Uses the request's own
     * account, so switching accounts never leaves an observer behind.
     */
    private void cancelActiveTranscription() {
        final Request request = activeRequest;
        if (request == null) {
            return;
        }
        activeRequest = null;
        removeTranscriptionObserver(request.account);
        MessageObject message = resolveMessage(request.account, request.dialogId, request.messageId);
        if (message != null) {
            HgramOfflineTranscriber.cancel(message);
        }
    }

    /**
     * Drops the finished request; with no playback behind it the service has nothing left to show.
     */
    private void finishTranscription() {
        final Request request = activeRequest;
        if (request != null) {
            activeRequest = null;
            removeTranscriptionObserver(request.account);
        }
        if (player == null) {
            stopForeground(true);
            stopSelf();
        }
    }

    /**
     * Single publication point for transcription text. Privacy is re-evaluated on every call and
     * the per-dialog notification is always updated with the request generation.
     */
    private void publish(CharSequence text, boolean allowText, Request request) {
        if (request == null || request != activeRequest) {
            return;
        }
        final boolean visible = allowText && canShowText(request);
        final CharSequence effective = visible ? text : getString(R.string.HgramAntiPeekHidden);
        transcriptionText = effective;
        updateDialogNotification(request, effective, visible);
        if (player == null) {
            // The same notification id carries the transcription while no playback runs.
            startForegroundCompat(buildTranscriptionNotification());
        }
        if (!visible) {
            // The text must not reach the message or a later notification: stop the worker now.
            cancelActiveTranscription();
            finishTranscription();
        }
    }

    private static boolean canShowText(Request request) {
        return canShowText(request.account, request.dialogId);
    }

    private static boolean canShowText(int targetAccount, long targetDialogId) {
        return HgramVoiceNotificationText.canShowText(
                HgramSecretSpace.isHidden(targetAccount, targetDialogId),
                HgramConfig.isAntiPeekActive(),
                DialogObject.isEncryptedDialog(targetDialogId));
    }

    private void failTranscription(Request request) {
        if (request == null || request != activeRequest) {
            return;
        }
        publish(getString(R.string.HgramVoiceTranscriptionFailed), true, request);
        finishTranscription();
    }

    private void onTranscriptionUpdate(int observerAccount, Object... args) {
        final Request request = activeRequest;
        if (request == null || observerAccount != request.account || args == null
                || args.length == 0 || !(args[0] instanceof MessageObject)) {
            return;
        }
        MessageObject message = (MessageObject) args[0];
        if (message.currentAccount != request.account || message.messageOwner == null
                || message.getId() != request.messageId
                || message.getDialogId() != request.dialogId) {
            return;
        }
        final TLRPC.Message owner = message.messageOwner;
        String text = owner.voiceTranscription;
        if (TextUtils.isEmpty(text) && args.length > 2 && args[2] instanceof String) {
            // Some final updates carry the text in the event only; keep the message field as
            // the primary source but never drop a finished transcription to an empty preview.
            text = (String) args[2];
        }
        if (!TextUtils.isEmpty(text) && owner.voiceTranscriptionFinal) {
            HgramVoiceNotificationText.cache(request.key, text);
            publish(HgramVoiceNotificationText.formatBigText(
                    baseText(message), text, HgramVoiceNotificationText.BIG_TEXT_LIMIT), true, request);
            finishTranscription();
        } else if (!owner.voiceTranscriptionFinal
                && TextUtils.isEmpty(text)
                && !HgramOfflineTranscriber.isTranscribing(message)) {
            failTranscription(request);
        }
    }

    private static String baseText(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return "";
        }
        if (!TextUtils.isEmpty(message.messageOwner.message)) {
            return message.messageOwner.message;
        }
        if (message.messageOwner.media != null) {
            return message.messageOwner.media.captionLegacy;
        }
        return "";
    }

    private void updateDialogNotification(Request request, CharSequence big, boolean allowText) {
        try {
            NotificationsController.getInstance(request.account)
                    .updateHgramVoiceTranscription(request.account, request.dialogId,
                            request.generation, big, allowText);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void stopPlayback() {
        handler.removeCallbacks(stopOnPause);
        cancelActiveTranscription();
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignore) {
            }
            player.release();
            player = null;
        }
        prepared = false;
        paused = false;
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(stopOnPause);
        cancelActiveTranscription();
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignore) {
            }
            player = null;
        }
        if (session != null) {
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
