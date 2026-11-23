package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
        /** Normalized boxes when the engine could collect them; {@code null} otherwise. */
        public final HgramOcrLayout layout;

        Result(Status status, String text, boolean cancelled) {
            this(status, text, cancelled, null);
        }

        Result(Status status, String text, boolean cancelled, HgramOcrLayout layout) {
            this.status = status;
            this.text = text;
            this.cancelled = cancelled;
            this.layout = layout;
        }

        static Result ok(String text) {
            return new Result(Status.OK, text, false);
        }

        static Result ok(String text, HgramOcrLayout layout) {
            return new Result(Status.OK, text, false, layout);
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

    /**
     * Optional engine surface: reads normalized boxes after recognition and before the native
     * API is released. Implementations must return {@code null} instead of failing the OCR.
     */
    interface BoxApi extends Api {
        HgramOcrLayout readPixelLayout();
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

    /** Identity of the request currently allowed to own the native API phase. */
    private static final class NativeOwner {
        final long tokenId;
        final int legacyRequestId;

        NativeOwner(long tokenId, int legacyRequestId) {
            this.tokenId = tokenId;
            this.legacyRequestId = legacyRequestId;
        }
    }

    private static final AtomicLong TOKEN_SEQUENCE = new AtomicLong();
    private static volatile HgramOcrEngine instance;

    private final Object lock = new Object();
    private final Object engineLock = new Object();
    private final Object nativeStopLock = new Object();
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
    private NativeOwner nativeOwner;
    private Boolean modelsReadyForTests;

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
        NativeOwner owner = null;
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            if (state == null || state.finished) {
                return;
            }
            if (!state.cancelled) {
                state.cancelled = true;
                if (state.running) {
                    owner = nativeOwner;
                    stopApi = api;
                }
            }
        }
        if (owner != null) {
            postStop(owner, stopApi);
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
        NativeOwner owner = null;
        synchronized (lock) {
            final RequestState state = requests.get(token.id);
            if (state == null || state.finishing) {
                return;
            }
            state.finished = true;
            if (state.running && api != null && nativeOwner != null) {
                state.finishing = true;
                owner = nativeOwner;
                stopApi = api;
            } else {
                requests.remove(token.id);
            }
        }
        if (owner != null) {
            postStopAndRelease(token, owner, stopApi);
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
            // Bounds and inSampleSize always describe the stored pixels; EXIF only tells us
            // how to rotate the decoded frame. PNG and files without EXIF stay untouched.
            final int exifOrientation = readJpegOrientation(file, bounds.outMimeType);
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
            final Bitmap oriented = applyExifOrientation(decoded, exifOrientation);
            if (oriented != null && oriented != decoded) {
                decoded.recycle();
                decoded = oriented;
            }
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            // recognizeClaimed scales to MAX_LONG_SIDE, so it sees the already-oriented bitmap.
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
            final NativeOwner owner = startLegacyRunning(requestId);
            if (owner == null) {
                return Result.failed(true);
            }
            return recognizeLocked(local, bitmap, requestId, owner);
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
        final NativeOwner owner;
        final Api local;
        synchronized (lock) {
            owner = nativeOwner;
            local = api;
        }
        if (owner != null && owner.tokenId == 0L) {
            postStop(owner, local);
        }
    }

    /** Stops only the legacy request that is still current; stale ids are ignored. */
    public void cancel(int requestId) {
        if (requestId <= 0 || requestId != requestGeneration) {
            return;
        }
        legacyCancelled = true;
        final NativeOwner owner;
        final Api local;
        synchronized (lock) {
            owner = nativeOwner;
            local = api;
        }
        if (owner != null && owner.tokenId == 0L && owner.legacyRequestId == requestId) {
            postStop(owner, local);
        }
    }

    private boolean isLegacyCurrent(int requestId) {
        return requestId == 0 || requestId == requestGeneration;
    }

    private boolean legacyRunning(int requestId) {
        return isLegacyCurrent(requestId) && !legacyCancelled;
    }

    public void release() {
        synchronized (lock) {
            legacyCancelled = true;
            for (RequestState state : requests.values()) {
                state.cancelled = true;
                state.finished = true;
            }
            requests.clear();
        }
        synchronized (engineLock) {
            final Api local;
            synchronized (nativeStopLock) {
                synchronized (lock) {
                    local = api;
                    api = null;
                    nativeOwner = null;
                }
                if (local != null) {
                    try {
                        local.clear();
                    } catch (Throwable ignored) {
                    }
                }
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
            final NativeOwner owner = startLegacyRunning(0);
            if (owner == null) {
                return Result.failed(true);
            }
            return recognizeLocked(api, bitmap, 0, owner);
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
            final NativeOwner owner = startRunning(token);
            if (owner == null) {
                return Result.failed(true);
            }
            try {
                return recognizeLocked(api, bitmap, token, owner);
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
            final NativeOwner owner = startRunning(token);
            if (owner == null) {
                return Result.failed(true);
            }
            try {
                return recognizeLocked(local, bitmap, token, owner);
            } finally {
                releaseRunning(token);
            }
        }
    }

    private Result recognizeLocked(Api api, Bitmap source, RequestToken token, NativeOwner owner) {
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
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            final HgramOcrLayout layout = readLayout(api);
            if (!isTokenUsable(token)) {
                return Result.failed(true);
            }
            return Result.ok(text, layout);
        } catch (Throwable ignored) {
            return Result.failed(!isTokenUsable(token));
        } finally {
            releaseNative(owner, api);
            if (scaled != null && scaled != source && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }

    private Result recognizeLocked(Api api, Bitmap source, int requestId, NativeOwner owner) {
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
            if (!legacyRunning(requestId)) {
                return Result.failed(true);
            }
            final HgramOcrLayout layout = readLayout(api);
            if (!legacyRunning(requestId)) {
                return Result.failed(true);
            }
            return Result.ok(text, layout);
        } catch (Throwable ignored) {
            return Result.failed(!legacyRunning(requestId));
        } finally {
            releaseNative(owner, api);
            if (scaled != null && scaled != source && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }

    /** Box collection is optional and must never fail an otherwise successful recognition. */
    private static HgramOcrLayout readLayout(Api api) {
        if (!(api instanceof BoxApi)) {
            return null;
        }
        try {
            return ((BoxApi) api).readPixelLayout();
        } catch (Throwable ignored) {
            return null;
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
    private NativeOwner startRunning(RequestToken token) {
        synchronized (nativeStopLock) {
            synchronized (lock) {
                final RequestState state = requests.get(token.id);
                if (state == null || state.cancelled || state.finished || !state.claimed) {
                    return null;
                }
                state.running = true;
                nativeOwner = new NativeOwner(token.id, 0);
                return nativeOwner;
            }
        }
    }

    /** Starts a legacy phase with the same native ownership barrier as tokens. */
    private NativeOwner startLegacyRunning(int requestId) {
        synchronized (nativeStopLock) {
            synchronized (lock) {
                if (!legacyRunning(requestId)) {
                    return null;
                }
                nativeOwner = new NativeOwner(0L, requestId);
                return nativeOwner;
            }
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

    /** Final native cleanup; serialized with control-worker stops and release(). */
    private void releaseNative(NativeOwner owner, Api api) {
        if (owner == null || api == null) {
            return;
        }
        synchronized (nativeStopLock) {
            final boolean owns;
            synchronized (lock) {
                owns = nativeOwner == owner;
                if (owns) {
                    nativeOwner = null;
                }
            }
            if (!owns) {
                return;
            }
            try {
                api.stop();
            } catch (Throwable ignored) {
            }
            try {
                api.clear();
            } catch (Throwable ignored) {
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

    /** Test seam: bypasses the traineddata hash check for file-path recognition tests. */
    void setModelsReadyForTests(boolean ready) {
        modelsReadyForTests = ready;
    }

    private boolean modelsReady(Context context) {
        if (modelsReadyForTests != null) {
            return modelsReadyForTests;
        }
        if (HgramOcrModels.isReady(context)) {
            return true;
        }
        return HgramOcrModels.ensureReady(context);
    }

    /** Stops only the captured native owner; a replacement owner is never touched. */
    private void postStop(final NativeOwner owner, final Api local) {
        if (owner == null || local == null) {
            return;
        }
        try {
            stopExecutor.execute(() -> {
                synchronized (nativeStopLock) {
                    synchronized (lock) {
                        if (nativeOwner != owner) {
                            return;
                        }
                    }
                    try {
                        local.stop();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Stops the captured owner first and only then removes the finished token's own state. */
    private void postStopAndRelease(final RequestToken token, final NativeOwner owner,
            final Api local) {
        if (token == null || owner == null || local == null) {
            return;
        }
        try {
            stopExecutor.execute(() -> {
                synchronized (nativeStopLock) {
                    boolean shouldStop;
                    synchronized (lock) {
                        shouldStop = nativeOwner == owner;
                    }
                    if (shouldStop) {
                        try {
                            local.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                    synchronized (lock) {
                        final RequestState state = requests.get(token.id);
                        if (state != null) {
                            state.running = false;
                            requests.remove(token.id);
                        }
                    }
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

    /** Test seam to delay queued stop work and exercise the stale-stop race. */
    void submitControlTaskForTests(Runnable task) {
        stopExecutor.execute(task);
    }

    /** Reads EXIF orientation only for JPEG; everything else is treated as normal. */
    static int readJpegOrientation(File file, String mimeType) {
        if (file == null || !isJpeg(file, mimeType)) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
        try {
            final int orientation = new ExifInterface(file.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return orientation >= ExifInterface.ORIENTATION_NORMAL
                    && orientation <= ExifInterface.ORIENTATION_ROTATE_270
                    ? orientation : ExifInterface.ORIENTATION_NORMAL;
        } catch (Throwable ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static boolean isJpeg(File file, String mimeType) {
        if ("image/jpeg".equals(mimeType) || "image/jpg".equals(mimeType)) {
            return true;
        }
        if (mimeType != null) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            return in.read() == 0xFF && in.read() == 0xD8;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * EXIF transform for all mirrored/rotated values. Returns {@code null} for normal and
     * unknown orientations so callers can keep the decoded bitmap untouched.
     */
    static Matrix exifMatrix(int orientation) {
        final Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                return null;
        }
        return matrix;
    }

    /** Returns the source itself when no transform is needed or the transform fails. */
    static Bitmap applyExifOrientation(Bitmap source, int orientation) {
        if (source == null || source.isRecycled()) {
            return source;
        }
        final Matrix matrix = exifMatrix(orientation);
        if (matrix == null) {
            return source;
        }
        try {
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        } catch (Throwable ignored) {
            return source;
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

    private static final class TessApi implements BoxApi {
        private final TessBaseAPI api = new TessBaseAPI();
        private int imageWidth;
        private int imageHeight;

        @Override
        public void init(String datapath, String language) throws Exception {
            if (!api.init(datapath, language, TessBaseAPI.OEM_LSTM_ONLY)) {
                throw new IOException("tesseract init failed");
            }
        }

        @Override
        public void setImage(Bitmap bitmap) {
            imageWidth = bitmap == null ? 0 : bitmap.getWidth();
            imageHeight = bitmap == null ? 0 : bitmap.getHeight();
            api.setImage(bitmap);
        }

        @Override
        public String getUTF8Text() {
            return api.getUTF8Text();
        }

        @Override
        public HgramOcrLayout readPixelLayout() {
            final int width = imageWidth;
            final int height = imageHeight;
            if (width <= 0 || height <= 0) {
                return null;
            }
            final ResultIterator iterator = api.getResultIterator();
            if (iterator == null) {
                return null;
            }
            final List<HgramOcrLayout.PixelLine> pixelLines = new ArrayList<>();
            int wordsSeen = 0;
            HgramOcrLayout.PixelLine current = null;
            try {
                iterator.begin();
                do {
                    final int[] wordBox = iterator.getBoundingBox(PageIteratorLevel.RIL_WORD);
                    final String wordText = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD);
                    if (current == null) {
                        current = new HgramOcrLayout.PixelLine(
                                iterator.getBoundingBox(PageIteratorLevel.RIL_TEXTLINE),
                                iterator.getUTF8Text(PageIteratorLevel.RIL_TEXTLINE));
                    }
                    if (wordsSeen < HgramOcrLayout.MAX_WORDS
                            && wordBox != null && wordText != null
                            && !wordText.trim().isEmpty()) {
                        current.words.add(new HgramOcrLayout.PixelWord(wordBox, wordText));
                        wordsSeen++;
                    }
                    if (iterator.isAtFinalElement(PageIteratorLevel.RIL_TEXTLINE,
                            PageIteratorLevel.RIL_WORD)) {
                        if (hasContent(current)) {
                            pixelLines.add(current);
                        }
                        current = null;
                        if (pixelLines.size() >= HgramOcrLayout.MAX_LINES) {
                            break;
                        }
                    }
                } while (iterator.next(PageIteratorLevel.RIL_WORD));
                if (current != null && hasContent(current)
                        && pixelLines.size() < HgramOcrLayout.MAX_LINES) {
                    pixelLines.add(current);
                }
                return HgramOcrLayout.fromPixels(width, height,
                        HgramOcrLayout.SPACE_DISPLAY, pixelLines);
            } catch (Throwable ignored) {
                return null;
            } finally {
                try {
                    iterator.delete();
                } catch (Throwable ignored) {
                }
            }
        }

        private static boolean hasContent(HgramOcrLayout.PixelLine line) {
            return line != null && (!line.words.isEmpty()
                    || (line.text != null && !line.text.trim().isEmpty()));
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
