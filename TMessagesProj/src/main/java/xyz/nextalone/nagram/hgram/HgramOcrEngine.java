package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide on-device OCR engine backed by Tesseract4Android.
 *
 * <p>Everything runs locally: the trained data is downloaded on demand by
 * {@link HgramOcrModels} and lives in the app files dir. Recognitions are serialized on a
 * single engine instance; the text itself is never logged. Recognition is synchronous, so
 * call it off the UI thread.</p>
 *
 * <p>Requests are identified by engine-issued {@link RequestToken}s. A token is unique for the
 * process, can be claimed by at most one recognition, and a cancelled token can never start or
 * be revived by a later call.</p>
 */
public final class HgramOcrEngine {

    public enum Status { OK, EMPTY, NEED_MODELS, FAILED }

    public static final class Result {
        public final Status status;
        public final String text;
        public final boolean cancelled;

        Result(Status status, String text, boolean cancelled) {
            this.status = status;
            this.text = text;
            this.cancelled = cancelled;
        }

        static Result ok(String text) {
            return new Result(Status.OK, text, false);
        }

        static Result empty() {
            return new Result(Status.EMPTY, "", false);
        }

        static Result needModels() {
            return new Result(Status.NEED_MODELS, "", false);
        }

        static Result failed(boolean cancelled) {
            return new Result(Status.FAILED, "", cancelled);
        }
    }

    /** Opaque engine-issued request identity. Only the engine can construct it. */
    public static final class RequestToken {
        public final long id;

        private RequestToken(long id) {
            this.id = id;
        }

        public long getId() {
            return id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RequestToken && ((RequestToken) other).id == id;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }

        @Override
        public String toString() {
            return "RequestToken{" + id + "}";
        }
    }

    public static final String LANGUAGES = "rus+eng";
    public static final String DATA_DIR = "hgram_tessdata";
    public static final int MAX_LONG_SIDE = HgramOcrText.MAX_LONG_SIDE;

    /** Minimal engine surface so unit tests never touch native Tesseract. */
    interface Api {
        void init(String datapath, String language) throws Exception;

        void setImage(Bitmap bitmap);

        String getUTF8Text();

        void stop();

        void clear();
    }

    interface ApiFactory {
        Api create();
    }

    private static final class RequestState {
        boolean claimed;
        boolean cancelled;
        boolean finished;
        boolean finishing;
        boolean running;
    }

    private static final AtomicLong TOKEN_SEQUENCE = new AtomicLong();
    private static volatile HgramOcrEngine instance;

    private final Object lock = new Object();
    private final Object engineLock = new Object();
    private final ApiFactory factory;
    private final File dataPath;
    private final Map<Long, RequestState> requests = new HashMap<>();
    private final ExecutorService stopExecutor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "hgram-ocr-stop");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Api api;
    private volatile boolean legacyCancelled;
    private volatile int requestGeneration;

    HgramOcrEngine(ApiFactory factory, File filesDir) {
        this.factory = factory;
        this.dataPath = filesDir == null ? new File(DATA_DIR) : new File(filesDir, DATA_DIR);
    }

    static HgramOcrEngine forTests(ApiFactory factory, File filesDir) {
        return new HgramOcrEngine(factory, filesDir);
    }

    public static HgramOcrEngine get(Context context) {
        HgramOcrEngine local = instance;
        if (local == null) {
            synchronized (HgramOcrEngine.class) {
                local = instance;
                if (local == null) {
                    final Context app = context == null ? null : context.getApplicationContext();
                    final Context source = app != null ? app : context;
                    final File filesDir = source == null ? null : source.getFilesDir();
                    local = new HgramOcrEngine(new TessApiFactory(), filesDir);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Issues a new unique token. The caller owns it until {@link #finish(RequestToken)}. */
    public RequestToken newRequest() {
        long id;
        do {
            id = TOKEN_SEQUENCE.incrementAndGet();
        } while (id <= 0L);
        final RequestToken token = new RequestToken(id);
        synchronized (lock) {
            requests.put(id, new RequestState());
        }
        return token;
    }

    /** True while the token has not been cancelled or finished. */
    public boolean isRequestActive(RequestToken token) {
        if (token == null) {
            return false;
        }
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            return state != null && !state.cancelled && !state.finished;
        }
    }

    /**
     * Permanently invalidates the token. If that token is currently being recognized, the native
     * stop is requested through a daemon control worker instead of the calling (UI) thread.
     */
    public void cancel(RequestToken token) {
        if (token == null) {
            return;
        }
        Api stopApi = null;
        boolean requestStop = false;
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            if (state == null || state.finished) {
                return;
            }
            if (!state.cancelled) {
                state.cancelled = true;
                if (state.running) {
                    requestStop = true;
                    stopApi = api;
                }
            }
        }
        if (requestStop) {
            postStop(token, stopApi);
        }
    }

    /**
     * Releases the token; later recognize/cancel calls with it are no-ops.  If native work is
     * still running, the state stays registered until the control worker has stopped it, so the
     * engine queue cannot start another request before the old native phase is stopped.
     */
    public void finish(RequestToken token) {
        if (token == null) {
            return;
        }
        Api stopApi = null;
        boolean requestStop = false;
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            if (state == null || state.finishing) {
                return;
            }
            state.finished = true;
            if (state.running && api != null) {
                state.finishing = true;
                requestStop = true;
                stopApi = api;
            } else {
                requests.remove(token.id);
            }
        }
        if (requestStop) {
            postStopAndRelease(token, stopApi);
        }
    }

    public Result recognize(Context context, Bitmap bitmap) {
        return recognize(context, bitmap, 0, true);
    }

    public Result recognize(Context context, Bitmap bitmap, RequestToken token) {
        if (token == null || !claim(token)) {
            return Result.failed(true);
        }
        try {
            return recognizeClaimed(context, bitmap, token);
        } finally {
            releaseRunning(token);
        }
    }

    public Result recognize(Context context, File file) {
        return recognize(context, file, 0);
    }

    public Result recognize(Context context, File file, RequestToken token) {
        if (token == null || !claim(token)) {
            return Result.failed(true);
        }
        Bitmap decoded = null;
        try {
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            if (file == null || !file.isFile()) {
                return Result.failed(false);
            }
            if (!modelsReady(context)) {
                return Result.needModels();
            }
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return Result.failed(false);
            }
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_LONG_SIDE);
            try {
                decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (Throwable ignored) {
                return Result.failed(false);
            }
            if (decoded == null) {
                return Result.failed(false);
            }
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            return recognizeClaimed(context, decoded, token);
        } finally {
            if (decoded != null && !decoded.isRecycled()) {
                decoded.recycle();
            }
            releaseRunning(token);
        }
    }

    /** Legacy numeric request id; kept for old callers and unit tests. */
    public Result recognize(Context context, File file, int requestId) {
        if (!isLegacyCurrent(requestId)) {
            return Result.failed(true);
        }
        if (file == null || !file.isFile()) {
            return Result.failed(false);
        }
        if (!modelsReady(context)) {
            return Result.needModels();
        }
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return Result.failed(false);
        }
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_LONG_SIDE);
        final Bitmap decoded;
        try {
            decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable ignored) {
            return Result.failed(false);
        }
        if (decoded == null) {
            return Result.failed(false);
        }
        try {
            return recognize(context, decoded, requestId, false);
        } finally {
            if (!decoded.isRecycled()) {
                decoded.recycle();
            }
        }
    }

    private Result recognize(Context context, Bitmap bitmap, int requestId, boolean checkModels) {
        if (bitmap == null) {
            return Result.empty();
        }
        synchronized (engineLock) {
            synchronized (lock) {
                if (!isLegacyCurrent(requestId)) {
                    return Result.failed(true);
                }
                legacyCancelled = false;
            }
            if (checkModels && !modelsReady(context)) {
                return Result.needModels();
            }
            Api local = api;
            if (local == null) {
                try {
                    local = factory.create();
                } catch (Throwable ignored) {
                    return Result.failed(legacyCancelled);
                }
                if (local == null) {
                    return Result.failed(legacyCancelled);
                }
                api = local;
            }
            return recognizeLocked(local, bitmap, requestId);
        }
    }

    /** Marks a newer legacy request; kept for old callers. */
    public void beginRequest(int requestId) {
        synchronized (lock) {
            requestGeneration = requestId;
            legacyCancelled = false;
        }
    }

    /** Cancels the current legacy request. */
    public void cancel() {
        legacyCancelled = true;
        postStop(api);
    }

    /** Stops only the legacy request that is still current; stale ids are ignored. */
    public void cancel(int requestId) {
        if (requestId <= 0 || requestId != requestGeneration) {
            return;
        }
        legacyCancelled = true;
        postStop(api);
    }

    private boolean isLegacyCurrent(int requestId) {
        return requestId == 0 || requestId == requestGeneration;
    }

    private boolean legacyRunning(int requestId) {
        return isLegacyCurrent(requestId) && !legacyCancelled;
    }

    public void release() {
        final Api local;
        synchronized (lock) {
            for (RequestState state : requests.values()) {
                state.cancelled = true;
                state.finished = true;
            }
            requests.clear();
            local = api;
            api = null;
        }
        if (local != null) {
            try {
                local.clear();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Package-private entry point: recognizes with the supplied engine surface. */
    Result recognize(Api api, Bitmap bitmap) {
        if (bitmap == null) {
            return Result.empty();
        }
        synchronized (engineLock) {
            this.api = api;
            synchronized (lock) {
                legacyCancelled = false;
            }
            return recognizeLocked(api, bitmap, 0);
        }
    }

    /** Package-private token entry point for tests. */
    Result recognize(Api api, Bitmap bitmap, RequestToken token) {
        if (token == null || !claim(token)) {
            return Result.failed(true);
        }
        synchronized (engineLock) {
            synchronized (lock) {
                this.api = api;
            }
            if (!startRunning(token)) {
                return Result.failed(true);
            }
            try {
                return recognizeLocked(api, bitmap, token);
            } finally {
                releaseRunning(token);
            }
        }
    }

    private Result recognizeClaimed(Context context, Bitmap bitmap, RequestToken token) {
        if (bitmap == null) {
            return Result.empty();
        }
        synchronized (engineLock) {
            if (!startRunning(token)) {
                return Result.failed(true);
            }
            try {
                if (!modelsReady(context)) {
                    return Result.needModels();
                }
                if (!isTokenUsable(token)) {
                    return Result.failed(true);
                }
                Api local = api;
                if (local == null) {
                    try {
                        local = factory.create();
                    } catch (Throwable ignored) {
                        return Result.failed(true);
                    }
                    if (local == null) {
                        return Result.failed(true);
                    }
                    synchronized (lock) {
                        api = local;
                    }
                }
                if (!isTokenUsable(token)) {
                    return Result.failed(true);
                }
                return recognizeLocked(local, bitmap, token);
            } finally {
                releaseRunning(token);
            }
        }
    }

    private Result recognizeLocked(Api api, Bitmap source, RequestToken token) {
        Bitmap scaled = null;
        try {
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            scaled = scale(source);
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            api.init(dataPath.getAbsolutePath(), LANGUAGES);
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            api.setImage(scaled);
            final String raw = api.getUTF8Text();
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            final String text = HgramOcrText.joinLines(splitLines(raw));
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            if (text.isEmpty()) {
                return Result.empty();
            }
            return Result.ok(text);
        } catch (Throwable ignored) {
            return Result.failed(!isTokenUsable(token));
        } finally {
            try {
                api.stop();
            } catch (Throwable ignored) {
            }
            try {
                api.clear();
            } catch (Throwable ignored) {
            }
            if (scaled != null && scaled != source && !scaled.isRecycled()) {
                scaled.recycle();
            }
            releaseRunning(token);
        }
    }

    private Result recognizeLocked(Api api, Bitmap source, int requestId) {
        Bitmap scaled = null;
        try {
            if (!legacyRunning(requestId)) {
                return Result.failed(true);
            }
            scaled = scale(source);
            if (!legacyRunning(requestId)) {
                return Result.failed(true);
            }
            api.init(dataPath.getAbsolutePath(), LANGUAGES);
            if (!legacyRunning(requestId)) {
                return Result.failed(true);
            }
            api.setImage(scaled);
            final String raw = api.getUTF8Text();
            if (!legacyRunning(requestId)) {
                return Result.failed(true);
            }
            final String text = HgramOcrText.joinLines(splitLines(raw));
            if (!legacyRunning(requestId)) {
                return Result.failed(true);
            }
            if (text.isEmpty()) {
                return Result.empty();
            }
            return Result.ok(text);
        } catch (Throwable ignored) {
            return Result.failed(!legacyRunning(requestId));
        } finally {
            try {
                api.stop();
            } catch (Throwable ignored) {
            }
            try {
                api.clear();
            } catch (Throwable ignored) {
            }
            if (scaled != null && scaled != source && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }

    private boolean claim(RequestToken token) {
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            if (state == null || state.cancelled || state.finished || state.claimed) {
                return false;
            }
            state.claimed = true;
            return true;
        }
    }

    /** Marks this claim as the request that is actually touching the native engine. */
    private boolean startRunning(RequestToken token) {
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            if (state == null || state.cancelled || state.finished || !state.claimed) {
                return false;
            }
            state.running = true;
            return true;
        }
    }

    private void releaseRunning(RequestToken token) {
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            if (state != null) {
                state.running = false;
            }
        }
    }

    private boolean isTokenUsable(RequestToken token) {
        if (token == null) {
            return false;
        }
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            return state != null
                    && state.claimed
                    && !state.cancelled
                    && !state.finished;
        }
    }

    private static boolean modelsReady(Context context) {
        if (HgramOcrModels.isReady(context)) {
            return true;
        }
        return HgramOcrModels.ensureReady(context);
    }

    private void postStop(final Api local) {
        if (local == null) {
            return;
        }
        try {
            stopExecutor.execute(() -> {
                try {
                    local.stop();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Asks the control worker to stop only while the token still owns the native phase. */
    private void postStop(final RequestToken token, final Api local) {
        if (token == null || local == null) {
            return;
        }
        try {
            stopExecutor.execute(() -> {
                synchronized (lock) {
                    final RequestState state = requests.get(token.id);
                    if (state == null || !state.running) {
                        return;
                    }
                }
                try {
                    local.stop();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Stops the native call first and only then removes the finished token state. */
    private void postStopAndRelease(final RequestToken token, final Api local) {
        if (token == null || local == null) {
            return;
        }
        try {
            stopExecutor.execute(() -> {
                try {
                    local.stop();
                } catch (Throwable ignored) {
                }
                synchronized (lock) {
                    requests.remove(token.id);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Number of registered request states; used to prove state survives native stop. */
    int requestCountForTests() {
        synchronized (lock) {
            return requests.size();
        }
    }

    private static Bitmap scale(Bitmap source) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        final int longSide = HgramOcrText.scaledLongSide(width, height, MAX_LONG_SIDE);
        final int shortSide = HgramOcrText.scaledShortSide(width, height, MAX_LONG_SIDE);
        if (longSide <= 0 || shortSide <= 0) {
            return source;
        }
        final int targetWidth = width >= height ? longSide : shortSide;
        final int targetHeight = width >= height ? shortSide : longSide;
        if (targetWidth == width && targetHeight == height) {
            return source;
        }
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
    }

    static int sampleSize(int width, int height, int maxLongSide) {
        final int longest = Math.max(width, height);
        if (longest <= 0 || maxLongSide <= 0) {
            return 1;
        }
        int sample = 1;
        while (longest / sample > maxLongSide) {
            sample <<= 1;
        }
        return sample;
    }

    private static List<String> splitLines(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        final String[] parts = raw.split("\n", -1);
        final List<String> lines = new ArrayList<>(parts.length);
        Collections.addAll(lines, parts);
        return lines;
    }

    private static final class TessApiFactory implements ApiFactory {
        @Override
        public Api create() {
            return new TessApi();
        }
    }

    private static final class TessApi implements Api {
        private final TessBaseAPI api = new TessBaseAPI();

        @Override
        public void init(String datapath, String language) throws Exception {
            if (!api.init(datapath, language, TessBaseAPI.OEM_LSTM_ONLY)) {
                throw new IOException("tesseract init failed");
            }
        }

        @Override
        public void setImage(Bitmap bitmap) {
            api.setImage(bitmap);
        }

        @Override
        public String getUTF8Text() {
            return api.getUTF8Text();
        }

        @Override
        public void stop() {
            api.stop();
        }

        @Override
        public void clear() {
            api.clear();
        }
    }
}
