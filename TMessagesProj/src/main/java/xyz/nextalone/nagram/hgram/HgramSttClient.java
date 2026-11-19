package xyz.nextalone.nagram.hgram;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;

/**
 * Main-process side of the isolated transcription service. It binds to {@link HgramSttService},
 * keeps a FIFO queue of requests and forwards partial/final results back to the caller on the
 * main thread. The service lives in {@code :stt}, so loading the model does not increase the
 * main process PSS.
 */
public final class HgramSttClient {
    public interface Callback {
        void onPartial(String text);

        void onFinished(String text, boolean cancelled, int error);
    }

    public static final class Request {
        public final String requestId;
        public final String filePath;
        public final int account;
        public final String engine;
        public final String language;
        public final int cacheFreeMb;
        public final Callback callback;
        boolean cancelled;
        int busyRetries;

        public Request(String requestId, String filePath, int account, String engine, String language, int cacheFreeMb, Callback callback) {
            this.requestId = requestId;
            this.filePath = filePath;
            this.account = account;
            this.engine = engine;
            this.language = language;
            this.cacheFreeMb = cacheFreeMb;
            this.callback = callback;
        }
    }

    private static final long CONNECT_TIMEOUT_MS = 5_000L;
    private static final long START_TIMEOUT_MS = 5_000L;
    private static final long BUSY_RETRY_DELAY_MS = 400L;
    private static final long PROCESS_FAILURE_COOLDOWN_MS = 2_000L;
    private static final int MAX_BUSY_RETRIES = 3;
    private static final HgramSttClient INSTANCE = new HgramSttClient();
    private static long lastProcessFailureAt;

    private final Handler handler = new Handler(Looper.getMainLooper(), this::handleMessage);
    private final Messenger replyMessenger = new Messenger(handler);
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private final Runnable connectTimeout;
    private final Runnable startTimeout;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (request == null) {
                unbind();
                return;
            }
            serviceMessenger = new Messenger(binder);
            startSent = true;
            handler.removeCallbacks(connectTimeout);
            sendStart(request);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceMessenger = null;
            handleProcessLost();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            serviceMessenger = null;
            handleProcessLost();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            serviceMessenger = null;
            handleProcessLost();
        }
    };

    private Context appContext;
    private Messenger serviceMessenger;
    private boolean bound;
    private boolean startSent;
    private Request request;

    private HgramSttClient() {
        connectTimeout = () -> {
            if (request != null && !startSent) {
                fail(HgramSttProtocol.ERROR_PROCESS);
            }
        };
        startTimeout = () -> {
            if (request != null && startSent) {
                handleProcessLost();
            }
        };
    }

    public static boolean start(Context context, Request request) {
        return INSTANCE.startInternal(context, request);
    }

    public static void cancel(String requestId) {
        INSTANCE.cancelInternal(requestId);
    }

    private boolean startInternal(Context context, Request newRequest) {
        if (context == null || newRequest == null || newRequest.callback == null) {
            return false;
        }
        final Context app = context.getApplicationContext();
        if (app == null) {
            return false;
        }
        appContext = app;
        if (SystemClock.elapsedRealtime() - lastProcessFailureAt < PROCESS_FAILURE_COOLDOWN_MS) {
            // The isolated process just died. Do not immediately restart it in a loop; surface
            // the failure on the next user tap and let the UI reset its button.
            handler.post(() -> newRequest.callback.onFinished("", false, HgramSttProtocol.ERROR_PROCESS));
            return true;
        }
        // Keep FIFO order even when the callback of a just-finished request starts another
        // transcription synchronously: it must wait behind requests that were queued earlier.
        if (request != null) {
            pending.addLast(newRequest);
            return true;
        }
        if (!pending.isEmpty()) {
            pending.addLast(newRequest);
            startNext();
            return true;
        }
        startActive(newRequest);
        return true;
    }

    private void startActive(Request newRequest) {
        request = newRequest;
        startSent = false;
        final Intent intent = new Intent(appContext, HgramSttService.class);
        try {
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            bound = false;
            fail(HgramSttProtocol.ERROR_PROCESS);
            return;
        }
        if (!bound) {
            fail(HgramSttProtocol.ERROR_PROCESS);
            return;
        }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void cancelInternal(String requestId) {
        if (requestId == null) {
            return;
        }
        final Request current = request;
        if (current != null && Objects.equals(current.requestId, requestId)) {
            current.cancelled = true;
            if (!startSent || serviceMessenger == null) {
                finish("", true, HgramSttProtocol.ERROR_NONE);
                return;
            }
            final Message message = Message.obtain(null, HgramSttProtocol.MSG_CANCEL);
            final Bundle data = new Bundle();
            data.putString(HgramSttProtocol.EXTRA_REQUEST_ID, requestId);
            message.setData(data);
            try {
                serviceMessenger.send(message);
            } catch (RemoteException e) {
                fail(HgramSttProtocol.ERROR_PROCESS);
            }
            return;
        }
        // The request has not started yet: drop it from the queue and report cancellation
        // without waking the service or showing an error toast.
        final Iterator<Request> iterator = pending.iterator();
        while (iterator.hasNext()) {
            final Request queued = iterator.next();
            if (Objects.equals(queued.requestId, requestId)) {
                iterator.remove();
                queued.cancelled = true;
                queued.callback.onFinished("", true, HgramSttProtocol.ERROR_NONE);
                if (request == null) {
                    startNext();
                }
                return;
            }
        }
    }

    private void sendStart(Request current) {
        final Message message = Message.obtain(null, HgramSttProtocol.MSG_START);
        message.replyTo = replyMessenger;
        final Bundle data = new Bundle();
        data.putString(HgramSttProtocol.EXTRA_REQUEST_ID, current.requestId);
        data.putString(HgramSttProtocol.EXTRA_FILE_PATH, current.filePath);
        data.putInt(HgramSttProtocol.EXTRA_ACCOUNT, current.account);
        data.putString(HgramSttProtocol.EXTRA_ENGINE, current.engine);
        data.putString(HgramSttProtocol.EXTRA_LANGUAGE, current.language);
        data.putInt(HgramSttProtocol.EXTRA_CACHE_FREE_MB, current.cacheFreeMb);
        message.setData(data);
        try {
            serviceMessenger.send(message);
            handler.removeCallbacks(startTimeout);
            handler.postDelayed(startTimeout, START_TIMEOUT_MS);
        } catch (RemoteException e) {
            fail(HgramSttProtocol.ERROR_PROCESS);
        }
    }

    private boolean handleMessage(Message message) {
        final Bundle data = message.getData();
        final String requestId = data.getString(HgramSttProtocol.EXTRA_REQUEST_ID);
        final Request current = request;
        if (current == null || !Objects.equals(current.requestId, requestId)) {
            return true;
        }
        handler.removeCallbacks(startTimeout);
        switch (message.what) {
            case HgramSttProtocol.MSG_STARTED:
                break;
            case HgramSttProtocol.MSG_PARTIAL: {
                if (!current.cancelled) {
                    current.callback.onPartial(data.getString(HgramSttProtocol.EXTRA_TEXT, ""));
                }
                break;
            }
            case HgramSttProtocol.MSG_DONE:
                finish(
                        data.getString(HgramSttProtocol.EXTRA_TEXT, ""),
                        data.getBoolean(HgramSttProtocol.EXTRA_CANCELLED, false),
                        HgramSttProtocol.ERROR_NONE
                );
                break;
            case HgramSttProtocol.MSG_ERROR: {
                final int error = data.getInt(HgramSttProtocol.EXTRA_ERROR, HgramSttProtocol.ERROR_GENERAL);
                if (error == HgramSttProtocol.ERROR_BUSY) {
                    retryBusy(current);
                } else if (error == HgramSttProtocol.ERROR_PROCESS) {
                    handleProcessLost();
                } else {
                    finish("", false, error);
                }
                break;
            }
            default:
                break;
        }
        return true;
    }

    /**
     * A busy service means that a previous request is still winding down. Put the request back
     * at the head of the queue and retry a few times instead of surfacing a spurious failure.
     */
    private void retryBusy(Request current) {
        if (current.cancelled) {
            finish("", true, HgramSttProtocol.ERROR_NONE);
            return;
        }
        current.busyRetries++;
        if (current.busyRetries > MAX_BUSY_RETRIES) {
            finish("", false, HgramSttProtocol.ERROR_GENERAL);
            return;
        }
        request = null;
        startSent = false;
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(startTimeout);
        unbind();
        pending.addFirst(current);
        handler.postDelayed(this::startNext, BUSY_RETRY_DELAY_MS);
    }

    private void fail(int error) {
        if (error == HgramSttProtocol.ERROR_PROCESS) {
            handleProcessLost();
        } else {
            finish("", false, error);
        }
    }

    private void handleProcessLost() {
        lastProcessFailureAt = SystemClock.elapsedRealtime();
        Request queued;
        while ((queued = pending.pollFirst()) != null) {
            queued.cancelled = true;
            try {
                queued.callback.onFinished("", false, HgramSttProtocol.ERROR_PROCESS);
            } catch (Throwable ignored) {
            }
        }
        finishInternal("", false, HgramSttProtocol.ERROR_PROCESS, false);
    }

    private void startNext() {
        if (request != null) {
            return;
        }
        Request next;
        while ((next = pending.pollFirst()) != null) {
            if (next.cancelled) {
                next.callback.onFinished("", true, HgramSttProtocol.ERROR_NONE);
                continue;
            }
            startActive(next);
            return;
        }
    }

    private void finish(String text, boolean cancelled, int error) {
        finishInternal(text, cancelled, error, true);
    }

    private void finishInternal(String text, boolean cancelled, int error, boolean allowNext) {
        final Request finishedRequest = request;
        request = null;
        startSent = false;
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(startTimeout);
        unbind();
        if (finishedRequest != null) {
            finishedRequest.callback.onFinished(text, cancelled, error);
        }
        if (allowNext) {
            startNext();
        }
    }

    private void unbind() {
        final Context context = appContext;
        final boolean wasBound = bound;
        bound = false;
        serviceMessenger = null;
        if (wasBound && context != null) {
            try {
                context.unbindService(connection);
            } catch (Throwable ignored) {
            }
        }
    }
}
