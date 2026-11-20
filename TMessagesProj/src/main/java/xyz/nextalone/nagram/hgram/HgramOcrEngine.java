package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Process-wide on-device OCR engine backed by Tesseract4Android.
 *
 * <p>Everything runs locally: the trained data is downloaded on demand by
 * {@link HgramOcrModels} and lives in the app files dir. Recognitions are serialized on a
 * single engine instance; the text itself is never logged. Recognition is synchronous, so
 * call it off the UI thread.</p>
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

    private static volatile HgramOcrEngine instance;

    private final Object lock = new Object();
    private final ApiFactory factory;
    private final File dataPath;
    private volatile Api api;
    private volatile boolean cancelled;
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

    public Result recognize(Context context, Bitmap bitmap) {
        return recognize(context, bitmap, 0, true);
    }

    private Result recognize(Context context, Bitmap bitmap, int requestId, boolean checkModels) {
        if (bitmap == null) {
            return Result.empty();
        }
        synchronized (lock) {
            if (!isTokenCurrent(requestId)) {
                return Result.failed(true);
            }
            cancelled = false;
            if (checkModels && !HgramOcrModels.isReady(context)) {
                return Result.needModels();
            }
            Api local = api;
            if (local == null) {
                try {
                    local = factory.create();
                } catch (Throwable ignored) {
                    return Result.failed(cancelled);
                }
                if (local == null) {
                    return Result.failed(cancelled);
                }
                api = local;
            }
            return recognizeLocked(local, bitmap, requestId);
        }
    }

    public Result recognize(Context context, File file) {
        return recognize(context, file, 0);
    }

    public Result recognize(Context context, File file, int requestId) {
        if (!isTokenCurrent(requestId)) {
            return Result.failed(true);
        }
        if (file == null || !file.isFile()) {
            return Result.failed(false);
        }
        if (!HgramOcrModels.isReady(context)) {
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

    /** Marks a newer request; results from any other request id are dropped. */
    public void beginRequest(int requestId) {
        requestGeneration = requestId;
        cancelled = false;
    }

    public void cancel() {
        cancelled = true;
        final Api local = api;
        if (local != null) {
            try {
                local.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Stops only the request that is still current; stale ids are ignored. */
    public void cancel(int requestId) {
        if (requestId <= 0 || requestId != requestGeneration) {
            return;
        }
        cancelled = true;
        final Api local = api;
        if (local != null) {
            try {
                // Intentionally outside synchronized(lock): stop() must interrupt native OCR.
                local.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isTokenCurrent(int requestId) {
        return requestId == 0 || requestId == requestGeneration;
    }

    public void release() {
        final Api local;
        synchronized (lock) {
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
        synchronized (lock) {
            this.api = api;
            cancelled = false;
            return recognizeLocked(api, bitmap, 0);
        }
    }

    private Result recognizeLocked(Api api, Bitmap source, int requestId) {
        Bitmap scaled = null;
        try {
            if (!isTokenCurrent(requestId)) {
                return Result.failed(true);
            }
            scaled = scale(source);
            api.init(dataPath.getAbsolutePath(), LANGUAGES);
            if (!isTokenCurrent(requestId) || cancelled) {
                return Result.failed(true);
            }
            api.setImage(scaled);
            final String raw = api.getUTF8Text();
            if (!isTokenCurrent(requestId) || cancelled) {
                return Result.failed(true);
            }
            final String text = HgramOcrText.joinLines(splitLines(raw));
            if (text.isEmpty()) {
                return Result.empty();
            }
            return Result.ok(text);
        } catch (Throwable ignored) {
            return Result.failed(cancelled || !isTokenCurrent(requestId));
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
