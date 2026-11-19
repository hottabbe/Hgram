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

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.io.File;

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
    private String transcription;
    private boolean transcribing;

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
        account = intent.getIntExtra(EXTRA_ACCOUNT, account);
        dialogId = intent.getLongExtra(EXTRA_DIALOG_ID, dialogId);
        messageId = intent.getIntExtra(EXTRA_MESSAGE_ID, messageId);
        String extraTitle = intent.getStringExtra(EXTRA_TITLE);
        if (extraTitle != null) {
            title = extraTitle;
        }
        if (ACTION_TRANSCRIBE.equals(intent.getAction())) {
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
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
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
                .addAction(icon, actionText, toggle);
        if (transcription != null) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(transcription));
        } else {
            builder.setStyle(new MediaStyle().setShowActionsInCompactView(0));
        }
        return builder.build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private MessageObject resolveMessage() {
        try {
            TLRPC.Message message = MessagesStorage.getInstance(account).getMessage(dialogId, messageId);
            return message == null ? null : new MessageObject(account, message, false, false);
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
        if (HgramSecretSpace.isHidden(account, dialogId) || HgramConfig.isAntiPeekActive()) {
            transcription = getString(R.string.HgramAntiPeekHidden);
            transcribing = false;
            startForegroundCompat(buildNotification());
            return;
        }
        MessageObject message = resolveMessage();
        if (message == null || !HgramOfflineTranscriber.hasAnyModelInstalled()
                || !HgramConfig.isOfflineTranscriptionEnabled()) {
            stopPlayback();
            return;
        }
        transcribing = true;
        startForegroundCompat(buildNotification());
        final String key = HgramConfig.getSttEngine();
        AndroidUtilities.runOnUIThread(() -> {
            HgramOfflineTranscriber.start(this, message);
            transcription = message.messageOwner == null ? null : message.messageOwner.voiceTranscription;
            transcribing = false;
            upload();
        });
    }

    private void stopPlayback() {
        handler.removeCallbacks(stopOnPause);
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
