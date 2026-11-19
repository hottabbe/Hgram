package xyz.nextalone.nagram.hgram;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Transcription service running in the {@code :stt} process. The model lives only here, so the
 * main process PSS does not include the model weights. The protocol is deliberately tiny:
 * a file path, engine, language and request id in; partial/final text out; cancel is oneway.
 */
public class HgramSttService extends Service {
    private static final String CHANNEL_ID = "hgram_stt";
    private static final int NOTIFICATION_ID = 0x5354;
    private static final String ACTION_CANCEL = "xyz.nextalone.nagram.hgram.STT_CANCEL";
    private static final long IDLE_TIMEOUT_MS = 10_000L;
    private static final long ABANDONED_KILL_TIMEOUT_MS = 30_000L;
    private static final int KILL_TYPE_NONE = 0;
    private static final int KILL_TYPE_IDLE = 1;
    private static final int KILL_TYPE_ABANDONED = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "HgramSttService");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> cancelledRequests = ConcurrentHashMap.newKeySet();
    private static final Handler processKillHandler = new Handler(Looper.getMainLooper());
    private static final ProcessKillState processKillState = new ProcessKillState();
    private static Runnable pendingProcessKill;

    /** Process-scoped kill state; deliberately does not capture the Service instance. */
    private static final class ProcessKillState {
        long token;
        int type = KILL_TYPE_NONE;
        String requestId;
        long deadline;
        boolean killWhenIdle;
        volatile String activeRequestId;
        volatile boolean hasBindings;
    }

    /** Static runnable: no Service reference is retained across the idle/abandoned delay. */
    private static final class ProcessKillRunnable implements Runnable {
        private final long token;
        private final int type;
        private final String requestId;

        ProcessKillRunnable(long token, int type, String requestId) {
            this.token = token;
            this.type = type;
            this.requestId = requestId;
        }

        @Override
        public void run() {
            if (token != processKillState.token || type != processKillState.type) {
                return;
            }
            if (!ApplicationLoader.isHgramSttProcess()) {
                clearProcessKillState();
                return;
            }
            if (processKillState.hasBindings) {
                clearPendingKillState();
                return;
            }
            if (type == KILL_TYPE_IDLE && processKillState.activeRequestId != null) {
                // Never kill a request that is still owned by a live client. Finish it first.
                processKillState.killWhenIdle = true;
                FileLog.e("HgramSTT idle-kill deferred: request still active");
                return;
            }
            if (type == KILL_TYPE_ABANDONED && processKillState.activeRequestId != null
                    && !processKillState.activeRequestId.equals(requestId)) {
                // A newer request owns the process; its own lifecycle will decide.
                clearPendingKillState();
                return;
            }
            if (type == KILL_TYPE_ABANDONED) {
                FileLog.e("HgramSTT abandoned-kill: request abandoned");
            }
            clearProcessKillState();
            if (!ApplicationLoader.isHgramSttProcess()) {
                return;
            }
            Process.killProcess(Process.myPid());
        }
    }

    private Handler handler;
    private Messenger messenger;
    private volatile String activeRequestId;
    private String activeEngine;
    private String activeLanguage;
    private Messenger replyTo;
    private boolean hasBindings;
    private int idleGeneration;
    private Runnable idleTimeoutRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        clearProcessKillState();
        try {
            createNotificationChannel();
        } catch (Throwable t) {
            // LocaleController is not initialized in the :stt process; a missing channel must
            // not prevent the service from starting, startForeground will report a clear error.
            FileLog.e(t);
        }
        handler = new Handler(Looper.getMainLooper(), this::handleCommand);
        messenger = new Messenger(handler);
    }

    @Override
    public IBinder onBind(Intent intent) {
        hasBindings = true;
        processKillState.hasBindings = true;
        processKillState.killWhenIdle = false;
        cancelProcessKill();
        cancelIdleTimeout();
        return messenger == null ? null : messenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hasBindings = false;
        processKillState.hasBindings = false;
        final String requestId = activeRequestId;
        if (requestId != null) {
            // The client is gone while native work may still be running. This is not idle:
            // use the explicit abandoned path and give the worker a long, separate window.
            cancelledRequests.add(requestId);
            HgramOfflineTranscriber.cancelServiceRequest(requestId);
            releaseRecognizers();
            stopForegroundCompat();
            scheduleAbandonedKill(requestId, "unbind");
        } else {
            releaseRecognizers();
            stopForegroundCompat();
            scheduleIdleTimeout();
            scheduleIdleKill(IDLE_TIMEOUT_MS + 500L);
        }
        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        cancelProcessKill();
        processKillState.killWhenIdle = false;
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelRequest(intent.getStringExtra(HgramSttProtocol.EXTRA_REQUEST_ID));
            if (activeRequestId == null) {
                releaseRecognizers();
                stopForegroundCompat();
                scheduleIdleTimeout();
                scheduleIdleKill(IDLE_TIMEOUT_MS + 500L);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cancelIdleTimeout();
        // A normal DONE -> unbind -> destroy sequence clears the active request and bindings.
        // In that case the process-scoped IDLE kill must survive service teardown, otherwise
        // the :stt PID stays cached forever. Only cancel it when a live request/binding
        // still owns the process.
        if (activeRequestId != null || hasBindings) {
            cancelIdleKill();
        }
        if (activeRequestId != null) {
            cancelledRequests.add(activeRequestId);
            HgramOfflineTranscriber.cancelServiceRequest(activeRequestId);
        }
        executor.shutdownNow();
        releaseRecognizers();
        stopForegroundCompat();
        super.onDestroy();
    }

    private boolean handleCommand(Message message) {
        switch (message.what) {
            case HgramSttProtocol.MSG_START:
                handleStart(message);
                break;
            case HgramSttProtocol.MSG_CANCEL: {
                final Bundle data = message.getData();
                cancelRequest(data.getString(HgramSttProtocol.EXTRA_REQUEST_ID));
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void handleStart(Message message) {
        cancelProcessKill();
        processKillState.killWhenIdle = false;
        final Bundle data = message.getData();
        final String requestId = data.getString(HgramSttProtocol.EXTRA_REQUEST_ID);
        final String filePath = data.getString(HgramSttProtocol.EXTRA_FILE_PATH);
        final String engine = data.getString(HgramSttProtocol.EXTRA_ENGINE);
        final String language = data.getString(HgramSttProtocol.EXTRA_LANGUAGE);
        final int cacheFreeMb = data.getInt(HgramSttProtocol.EXTRA_CACHE_FREE_MB, 0);
        final Messenger reply = message.replyTo;
        if (requestId == null || filePath == null || engine == null || reply == null) {
            sendError(reply, requestId, HgramSttProtocol.ERROR_GENERAL);
            scheduleIdleTimeout();
            return;
        }
        if (activeRequestId != null) {
            sendError(reply, requestId, HgramSttProtocol.ERROR_BUSY);
            return;
        }
        if (ApplicationLoader.isHgramSttNativeInitFailed()) {
            sendError(reply, requestId, HgramSttProtocol.ERROR_GENERAL);
            scheduleIdleTimeout();
            return;
        }
        cancelIdleTimeout();
        activeRequestId = requestId;
        activeEngine = engine;
        activeLanguage = language;
        replyTo = reply;
        processKillState.activeRequestId = requestId;
        try {
            startForegroundWithNotification(requestId);
        } catch (Throwable t) {
            FileLog.e(t);
            sendError(reply, requestId, HgramSttProtocol.ERROR_GENERAL);
            finishRequest(requestId);
            return;
        }
        sendStarted(reply, requestId);
        executor.execute(() -> runRequest(requestId, filePath, engine, language, cacheFreeMb, reply));
    }

    private void runRequest(String requestId, String filePath, String engine, String language, int cacheFreeMb, Messenger reply) {
        if (!hasEnoughMemory(engine)) {
            sendError(reply, requestId, HgramSttProtocol.ERROR_LOW_MEMORY);
            finishRequest(requestId);
            return;
        }
        String text = "";
        try {
            final File file = new File(filePath);
            if (!file.isFile()) {
                sendError(reply, requestId, HgramSttProtocol.ERROR_GENERAL);
            } else {
                text = HgramOfflineTranscriber.transcribeForService(
                        file, engine, language, requestId, cacheFreeMb,
                        partial -> sendPartial(reply, requestId, partial)
                );
            }
        } catch (Throwable t) {
            FileLog.e(t);
            sendError(reply, requestId, HgramSttProtocol.ERROR_GENERAL);
        }
        final boolean cancelled = HgramOfflineTranscriber.consumeCancelled(requestId);
        if (!cancelled) {
            sendDone(reply, requestId, text, false);
        } else {
            sendDone(reply, requestId, text, true);
        }
        finishRequest(requestId);
    }

    private void cancelRequest(String requestId) {
        if (requestId == null || activeRequestId == null || !activeRequestId.equals(requestId)) {
            return;
        }
        cancelledRequests.add(requestId);
        HgramOfflineTranscriber.cancelServiceRequest(requestId);
    }

    private void finishRequest(String requestId) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finishRequestOnMain(requestId);
        } else if (handler != null) {
            handler.post(() -> finishRequestOnMain(requestId));
        }
    }

    private void finishRequestOnMain(String requestId) {
        if (activeRequestId != null && activeRequestId.equals(requestId)) {
            activeRequestId = null;
            activeEngine = null;
            activeLanguage = null;
            replyTo = null;
            processKillState.activeRequestId = null;
            cancelledRequests.remove(requestId);
            cancelRequestNotification();
            if (processKillState.killWhenIdle) {
                processKillState.killWhenIdle = false;
                releaseRecognizers();
                stopForegroundCompat();
                stopSelf();
                scheduleIdleKill(800L);
            } else {
                scheduleIdleTimeout();
            }
        }
    }

    private void scheduleIdleTimeout() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (handler != null) {
                handler.post(this::scheduleIdleTimeout);
            }
            return;
        }
        if (handler == null || activeRequestId != null || hasBindings) {
            return;
        }
        cancelIdleTimeoutOnMain();
        final int token = ++idleGeneration;
        idleTimeoutRunnable = () -> handleIdleTimeout(token);
        handler.postDelayed(idleTimeoutRunnable, IDLE_TIMEOUT_MS);
    }

    private void cancelIdleTimeout() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (handler != null) {
                handler.post(this::cancelIdleTimeout);
            }
            return;
        }
        cancelIdleTimeoutOnMain();
    }

    private void cancelIdleTimeoutOnMain() {
        idleGeneration++;
        if (handler != null && idleTimeoutRunnable != null) {
            handler.removeCallbacks(idleTimeoutRunnable);
        }
        idleTimeoutRunnable = null;
    }

    private void handleIdleTimeout(int token) {
        if (token != idleGeneration || activeRequestId != null || hasBindings) {
            // A newer request (or a new binding) owns the service now. Never cancel it from
            // an older idle callback.
            return;
        }
        releaseRecognizers();
        stopForegroundCompat();
        stopSelf();
        scheduleIdleKill(800L);
    }

    private static void scheduleIdleKill(long delayMs) {
        if (!ApplicationLoader.isHgramSttProcess()) {
            return;
        }
        cancelProcessKill();
        processKillState.type = KILL_TYPE_IDLE;
        processKillState.requestId = null;
        processKillState.deadline = SystemClock.elapsedRealtime() + delayMs;
        final long token = ++processKillState.token;
        pendingProcessKill = new ProcessKillRunnable(token, KILL_TYPE_IDLE, null);
        processKillHandler.postDelayed(pendingProcessKill, delayMs);
    }

    private static void scheduleAbandonedKill(String requestId, String reason) {
        if (!ApplicationLoader.isHgramSttProcess()) {
            return;
        }
        cancelProcessKill();
        processKillState.type = KILL_TYPE_ABANDONED;
        processKillState.requestId = requestId;
        processKillState.deadline = SystemClock.elapsedRealtime() + ABANDONED_KILL_TIMEOUT_MS;
        processKillState.killWhenIdle = false;
        final long token = ++processKillState.token;
        pendingProcessKill = new ProcessKillRunnable(token, KILL_TYPE_ABANDONED, requestId);
        processKillHandler.postDelayed(pendingProcessKill, ABANDONED_KILL_TIMEOUT_MS);
        FileLog.e("HgramSTT abandoned-kill scheduled: " + reason);
    }

    private static void cancelIdleKill() {
        if (processKillState.type == KILL_TYPE_IDLE) {
            cancelProcessKill();
        }
    }

    private static void cancelProcessKill() {
        processKillState.token++;
        processKillState.type = KILL_TYPE_NONE;
        processKillState.requestId = null;
        processKillState.deadline = 0L;
        if (pendingProcessKill != null) {
            processKillHandler.removeCallbacks(pendingProcessKill);
            pendingProcessKill = null;
        }
    }

    private static void clearPendingKillState() {
        processKillState.type = KILL_TYPE_NONE;
        processKillState.requestId = null;
        processKillState.deadline = 0L;
        if (pendingProcessKill != null) {
            processKillHandler.removeCallbacks(pendingProcessKill);
            pendingProcessKill = null;
        }
    }

    private static void clearProcessKillState() {
        cancelProcessKill();
        processKillState.killWhenIdle = false;
        processKillState.activeRequestId = null;
        processKillState.hasBindings = false;
    }

    private boolean hasEnoughMemory(String engine) {
        if (!HgramConfig.STT_ENGINE_GIGAAM.equals(engine)) {
            return true;
        }
        try {
            final ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return true;
            }
            final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            // Measured M8 peak for the 305 MiB model was 488-525 MB, i.e. roughly 220 MB
            // above the model file. The previous 2x model threshold (~610 MB) rejected
            // devices that could transcribe; keep the measured headroom instead.
            final long required = HgramGigaAmRecognizer.MODEL_SIZE_BYTES + 220L * 1024L * 1024L;
            return info.availMem >= required;
        } catch (Throwable t) {
            return true;
        }
    }

    private void sendStarted(Messenger reply, String requestId) {
        final Message message = Message.obtain(null, HgramSttProtocol.MSG_STARTED);
        final Bundle data = new Bundle();
        data.putString(HgramSttProtocol.EXTRA_REQUEST_ID, requestId);
        message.setData(data);
        send(reply, message);
    }

    private void sendPartial(Messenger reply, String requestId, String text) {
        final Message message = Message.obtain(null, HgramSttProtocol.MSG_PARTIAL);
        final Bundle data = new Bundle();
        data.putString(HgramSttProtocol.EXTRA_REQUEST_ID, requestId);
        data.putString(HgramSttProtocol.EXTRA_TEXT, text);
        message.setData(data);
        send(reply, message);
    }

    private void sendDone(Messenger reply, String requestId, String text, boolean cancelled) {
        final Message message = Message.obtain(null, HgramSttProtocol.MSG_DONE);
        final Bundle data = new Bundle();
        data.putString(HgramSttProtocol.EXTRA_REQUEST_ID, requestId);
        data.putString(HgramSttProtocol.EXTRA_TEXT, text);
        data.putBoolean(HgramSttProtocol.EXTRA_CANCELLED, cancelled);
        message.setData(data);
        send(reply, message);
    }

    private void sendError(Messenger reply, String requestId, int error) {
        if (reply == null) {
            return;
        }
        final Message message = Message.obtain(null, HgramSttProtocol.MSG_ERROR);
        final Bundle data = new Bundle();
        data.putString(HgramSttProtocol.EXTRA_REQUEST_ID, requestId);
        data.putInt(HgramSttProtocol.EXTRA_ERROR, error);
        message.setData(data);
        send(reply, message);
    }

    private void send(Messenger reply, Message message) {
        if (reply == null) {
            return;
        }
        try {
            reply.send(message);
        } catch (RemoteException e) {
            FileLog.e(e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        final String title = serviceString(R.string.HgramSttServiceTitle, "Speech recognition");
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                title,
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(title);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private void startForegroundWithNotification(String requestId) {
        final Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.notification)
                .setContentTitle(serviceString(R.string.HgramSttServiceTitle, "Speech recognition"))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE);
        final Intent cancel = new Intent(this, HgramSttService.class)
                .setAction(ACTION_CANCEL)
                .putExtra(HgramSttProtocol.EXTRA_REQUEST_ID, requestId);
        final PendingIntent pendingCancel = PendingIntent.getService(
                this,
                requestId.hashCode(),
                cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.addAction(R.drawable.ic_ab_reply2, serviceString(R.string.HgramSttServiceCancel, "Cancel"), pendingCancel);
        final Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private String serviceString(int resId, String fallback) {
        try {
            return getString(resId);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable t) {
            // Foreground removal is best-effort; a failed call must not prevent kill/stopSelf.
            FileLog.e(t);
        }
    }

    private void cancelRequestNotification() {
        stopForegroundCompat();
    }

    private void releaseRecognizers() {
        try {
            HgramGigaAmRecognizer.releaseRecognizer();
        } catch (Throwable ignored) {
        }
        try {
            HgramSherpaRecognizer.releaseRecognizer();
        } catch (Throwable ignored) {
        }
    }
}
