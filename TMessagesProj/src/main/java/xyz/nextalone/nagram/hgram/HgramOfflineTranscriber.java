package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Debug;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranscribeButton;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Offline voice transcription with Vosk. Models are downloaded on demand by {@link HgramVoskModels}. */
public final class HgramOfflineTranscriber {

    private static final int OUTPUT_SAMPLE_RATE = 16_000;
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Set<String> CANCELLED = ConcurrentHashMap.newKeySet();
    private static final Map<String, PendingVoiceLoad> PENDING = new ConcurrentHashMap<>();
    private static volatile Thread activeThread;
    private static volatile String activeKey;

    /** Receives partial transcripts from the isolated :stt process. */
    public interface PartialListener {
        void onPartial(String text);
    }

    private HgramOfflineTranscriber() {
    }

    public static boolean isAvailable(Context context) {
        return context != null;
    }

    public static boolean hasAnyModelInstalled() {
        return HgramVoskModels.isInstalled(HgramVoskModels.LANG_RU) || HgramVoskModels.isInstalled(HgramVoskModels.LANG_EN)
                || HgramSherpaRecognizer.isInstalled() || HgramGigaAmRecognizer.isInstalled();
    }

    public static boolean isMessageDownloaded(MessageObject message) {
        return findDownloadedFile(message) != null;
    }

    private static File findDownloadedFile(MessageObject message) {
        if (message == null || message.getDocument() == null) {
            return null;
        }
        try {
            TLRPC.Document document = message.getDocument();
            if (document.localPath != null && !document.localPath.isEmpty()) {
                File local = new File(document.localPath);
                if (local.isFile()) {
                    return local;
                }
            }
            FileLoader loader = FileLoader.getInstance(message.currentAccount);
            File file = loader.getPathToAttach(document, null, false, true);
            if (file != null && file.isFile()) {
                return file;
            }
            file = loader.getPathToAttach(document, true);
            if (file != null && file.isFile()) {
                return file;
            }
            file = loader.getPathToAttach(document, false);
            if (file != null && file.isFile()) {
                return file;
            }
            if (message.messageOwner != null && message.messageOwner.attachPath != null) {
                file = new File(message.messageOwner.attachPath);
                if (file.isFile()) {
                    return file;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean isTranscribing(MessageObject message) {
        if (message == null) {
            return false;
        }
        final String key = key(message);
        return ACTIVE.contains(key) || PENDING.containsKey(key) || key.equals(activeKey);
    }

    /**
     * Cancels a running transcription or a waiting voice-file download. The UI is reset
     * without an error toast; the worker exits at the next segment boundary.
     */
    public static void cancel(MessageObject message) {
        if (message == null) {
            return;
        }
        final String key = key(message);
        CANCELLED.add(key);
        final PendingVoiceLoad pending = PENDING.remove(key);
        if (pending != null) {
            pending.cleanup();
        }
        final boolean workerActive = activeThread != null && key.equals(activeKey);
        if (!workerActive) {
            // No worker will observe this key: drop it immediately so cancelled downloads
            // for messages the user never returns to do not accumulate forever.
            CANCELLED.remove(key);
        }
        final TLRPC.Document document = message.getDocument();
        if (document != null) {
            try {
                FileLoader.getInstance(message.currentAccount).cancelLoadFile(document);
            } catch (Throwable ignored) {
            }
        }
        final Thread thread = activeThread;
        if (thread != null && key.equals(activeKey)) {
            thread.interrupt();
        }
        HgramSttClient.cancel(key);
        ACTIVE.remove(key);
        AndroidUtilities.runOnUIThread(() -> {
            TranscribeButton.endOfflineTranscription(message);
            TranscribeButton.notifyOfflineTranscriptionFinished(message);
        });
    }

    public static boolean start(Context context, MessageObject message) {
        if (!HgramConfig.isOfflineTranscriptionEnabled() || message == null || message.messageOwner == null || !isAvailable(context)) {
            return false;
        }
        String key = key(message);
        if (!ACTIVE.add(key)) {
            return true;
        }
        CANCELLED.remove(key);
        File file = findDownloadedFile(message);
        final String engine = HgramConfig.getSttEngine();
        final boolean gigaAm = HgramConfig.STT_ENGINE_GIGAAM.equals(engine);
        boolean sherpa = HgramConfig.STT_ENGINE_SHERPA.equals(engine);
        String lang;
        if (gigaAm) {
            lang = HgramGigaAmRecognizer.isInstalled() ? "gigaam" : null;
        } else if (sherpa) {
            lang = HgramSherpaRecognizer.isInstalled() ? "sherpa" : null;
        } else {
            lang = pickInstalledLanguage();
        }
        if (lang == null) {
            ACTIVE.remove(key);
            return false;
        }
        TranscribeButton.beginOfflineTranscription(message);
        if (file == null) {
            // The message is not downloaded yet. Download it with normal priority, keep the
            // transcribe button in its loading state and continue automatically when the file
            // appears. A failure resets the UI with an explicit network error.
            if (!startPendingDownload(context, message, key, lang, gigaAm, sherpa)) {
                ACTIVE.remove(key);
                TranscribeButton.endOfflineTranscription(message);
                return false;
            }
            return true;
        }
        startTranscription(context, message, file, key, lang, gigaAm, sherpa);
        return true;
    }

    private static boolean startPendingDownload(Context context, MessageObject message, String key, String lang, boolean gigaAm, boolean sherpa) {
        final TLRPC.Document document = message.getDocument();
        if (document == null) {
            return false;
        }
        final String fileName = FileLoader.getAttachFileName(document);
        if (TextUtils.isEmpty(fileName)) {
            return false;
        }
        final PendingVoiceLoad pending = new PendingVoiceLoad(context, message, key, fileName, lang, gigaAm, sherpa);
        PENDING.put(key, pending);
        try {
            final int account = message.currentAccount;
            NotificationCenter.getInstance(account).addObserver(pending, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(account).addObserver(pending, NotificationCenter.fileLoadProgressChanged);
            NotificationCenter.getInstance(account).addObserver(pending, NotificationCenter.fileLoadFailed);
            final FileLoader loader = FileLoader.getInstance(account);
            loader.loadFile(document, message, FileLoader.PRIORITY_NORMAL, 0);
            final File downloaded = findDownloadedFile(message);
            if (downloaded != null) {
                pending.cleanup();
                startTranscription(context, message, downloaded, key, lang, gigaAm, sherpa);
                return true;
            }
            if (!loader.isLoadingFile(fileName)) {
                pending.cleanup();
                return false;
            }
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            pending.cleanup();
            return false;
        }
    }

    private static void startTranscription(Context context, MessageObject message, File file, String key, String lang, boolean gigaAm, boolean sherpa) {
        Utilities.globalQueue.postRunnable(() -> {
            final int cacheFreeMb = clearMainImageCaches();
            AndroidUtilities.runOnUIThread(() -> startTranscriptionRequest(context, message, file, key, lang, gigaAm, sherpa, cacheFreeMb));
        });
    }

    private static void startTranscriptionRequest(Context context, MessageObject message, File file, String key, String lang, boolean gigaAm, boolean sherpa, int cacheFreeMb) {
        if (!ACTIVE.contains(key)) {
            return;
        }
        final String engine = gigaAm
                ? HgramConfig.STT_ENGINE_GIGAAM
                : sherpa ? HgramConfig.STT_ENGINE_SHERPA : HgramConfig.STT_ENGINE_VOSK;
        final HgramSttClient.Callback callback = new HgramSttClient.Callback() {
            @Override
            public void onPartial(String text) {
                if (TextUtils.isEmpty(text) || message.messageOwner == null) {
                    return;
                }
                message.messageOwner.voiceTranscription = text;
                message.messageOwner.voiceTranscriptionOpen = true;
                message.messageOwner.voiceTranscriptionFinal = false;
                NotificationCenter.getInstance(message.currentAccount)
                        .postNotificationName(NotificationCenter.voiceTranscriptionUpdate, message);
            }

            @Override
            public void onFinished(String text, boolean cancelled, int error) {
                ACTIVE.remove(key);
                AndroidUtilities.runOnUIThread(() -> {
                    TranscribeButton.endOfflineTranscription(message);
                    if (cancelled) {
                        TranscribeButton.notifyOfflineTranscriptionFinished(message);
                        return;
                    }
                    if (!TextUtils.isEmpty(text)) {
                        message.messageOwner.voiceTranscriptionOpen = true;
                        message.messageOwner.voiceTranscriptionFinal = true;
                        TranscribeButton.openVideoTranscription(message);
                        TranscribeButton.finishTranscription(message, 0, text);
                    } else {
                        TranscribeButton.notifyOfflineTranscriptionFinished(message);
                        final int stringId = error == HgramSttProtocol.ERROR_LOW_MEMORY
                                ? R.string.HgramSttLowMemory
                                : error == HgramSttProtocol.ERROR_PROCESS
                                ? R.string.HgramSttServiceStopped
                                : R.string.HgramOfflineTranscriptionFailed;
                        Toast.makeText(context, LocaleController.getString(stringId), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };
        final HgramSttClient.Request request = new HgramSttClient.Request(
                key, file.getAbsolutePath(), message.currentAccount, engine, lang, cacheFreeMb, callback
        );
        final boolean started = HgramSttClient.start(context, request);
        if (!started) {
            ACTIVE.remove(key);
            AndroidUtilities.runOnUIThread(() -> {
                TranscribeButton.endOfflineTranscription(message);
                TranscribeButton.notifyOfflineTranscriptionFinished(message);
                Toast.makeText(context, LocaleController.getString(R.string.HgramOfflineTranscriptionFailed), Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * Runs transcription inside the isolated :stt process. The caller must not hold a
     * MessageObject here: only the file and plain strings cross the process boundary.
     */
    public static String transcribeForService(File file, String engine, String lang, String key, int cacheFreeMb, PartialListener listener) throws Exception {
        activeThread = Thread.currentThread();
        activeKey = key;
        try {
            if (CANCELLED.contains(key)) {
                return "";
            }
            if (HgramConfig.STT_ENGINE_GIGAAM.equals(engine)) {
                return transcribeWithSherpa(file, null, true, key, cacheFreeMb, listener);
            }
            if (HgramConfig.STT_ENGINE_SHERPA.equals(engine)) {
                return transcribeWithSherpa(file, null, false, key, cacheFreeMb, listener);
            }
            return transcribeWithVosk(file, lang, null, listener);
        } finally {
            if (activeThread == Thread.currentThread()) {
                activeThread = null;
                activeKey = null;
            }
        }
    }

    public static void cancelServiceRequest(String key) {
        CANCELLED.add(key);
        final Thread thread = activeThread;
        if (thread != null && key != null && key.equals(activeKey)) {
            thread.interrupt();
        }
    }

    public static boolean consumeCancelled(String key) {
        return key != null && CANCELLED.remove(key);
    }

    private static final class PendingVoiceLoad implements NotificationCenter.NotificationCenterDelegate {
        private final Context context;
        private final MessageObject message;
        private final String key;
        private final String fileName;
        private final String lang;
        private final boolean gigaAm;
        private final boolean sherpa;
        private final int account;
        private boolean finished;

        PendingVoiceLoad(Context context, MessageObject message, String key, String fileName, String lang, boolean gigaAm, boolean sherpa) {
            this.context = context;
            this.message = message;
            this.key = key;
            this.fileName = fileName;
            this.lang = lang;
            this.gigaAm = gigaAm;
            this.sherpa = sherpa;
            this.account = message.currentAccount;
        }

        void cleanup() {
            if (finished) {
                return;
            }
            finished = true;
            PENDING.remove(key, this);
            try {
                NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
            } catch (Throwable ignored) {
            }
            try {
                NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
            } catch (Throwable ignored) {
            }
            try {
                NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
            } catch (Throwable ignored) {
            }
        }

        private void fail() {
            ACTIVE.remove(key);
            CANCELLED.remove(key);
            AndroidUtilities.runOnUIThread(() -> {
                TranscribeButton.endOfflineTranscription(message);
                TranscribeButton.notifyOfflineTranscriptionFinished(message);
                Toast.makeText(context, LocaleController.getString(R.string.HgramVoiceDownloadFailed), Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (finished) {
                return;
            }
            if (id == NotificationCenter.fileLoaded) {
                if (args.length == 0 || !fileName.equals(args[0])) {
                    return;
                }
                cleanup();
                final File file = findDownloadedFile(message);
                if (file == null) {
                    fail();
                } else {
                    startTranscription(context, message, file, key, lang, gigaAm, sherpa);
                }
            } else if (id == NotificationCenter.fileLoadFailed) {
                if (args.length == 0 || !fileName.equals(args[0])) {
                    return;
                }
                cleanup();
                fail();
            } else if (id == NotificationCenter.fileLoadProgressChanged) {
                if (args.length >= 3 && BuildVars.LOGS_ENABLED && fileName.equals(args[0])) {
                    FileLog.d("HgramSTT voice download: " + args[1] + "/" + args[2]);
                }
            }
        }
    }

    /** Russian for Russian UI, English otherwise; falls back to whichever model is installed. */
    private static String pickInstalledLanguage() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        String preferred = locale != null && "ru".equalsIgnoreCase(locale.getLanguage()) ? HgramVoskModels.LANG_RU : HgramVoskModels.LANG_EN;
        String other = HgramVoskModels.LANG_RU.equals(preferred) ? HgramVoskModels.LANG_EN : HgramVoskModels.LANG_RU;
        if (HgramVoskModels.isInstalled(preferred)) {
            return preferred;
        }
        if (HgramVoskModels.isInstalled(other)) {
            return other;
        }
        return null;
    }

    static void appendResult(StringBuilder text, String json) throws org.json.JSONException {
        String segment = new JSONObject(json).optString("text", "").trim();
        if (!segment.isEmpty()) {
            if (text.length() > 0) text.append(' ');
            text.append(segment);
        }
    }

    /** Streams mono PCM16 of the recording into the sink without buffering the whole file. */
    private static void decodeToPcm16Mono(File file, HgramPcmStreamer.Sink sink) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = format;
                    break;
                }
            }
            if (audioTrack < 0 || inputFormat == null) {
                throw new IllegalArgumentException("No audio track in media");
            }
            extractor.selectTrack(audioTrack);
            codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            codec.configure(inputFormat, null, null, 0);
            codec.start();
            boolean inputEnded = false;
            boolean outputEnded = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            long phase = 0;
            final byte[] pcmBytes = new byte[2];
            final long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            final long decodeDeadline = android.os.SystemClock.elapsedRealtime()
                    + 10_000L + Math.max(0L, durationUs / 1000L) * 3L;
            while (!outputEnded) {
                if (android.os.SystemClock.elapsedRealtime() > decodeDeadline) {
                    throw new IllegalStateException("Audio decoding timed out");
                }
                if (!inputEnded) {
                    int inputIndex = codec.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int size = extractor.readSampleData(inputBuffer, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEnded = true;
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (outputIndex >= 0) {
                    ByteBuffer decoded = codec.getOutputBuffer(outputIndex);
                    if (decoded != null && info.size > 0) {
                        decoded.position(info.offset);
                        decoded.limit(info.offset + info.size);
                        decoded.order(ByteOrder.LITTLE_ENDIAN);
                        int bytesPerSample = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                        int frames = info.size / Math.max(1, channels * bytesPerSample);
                        for (int frame = 0; frame < frames; frame++) {
                            float mono = 0;
                            for (int channel = 0; channel < channels; channel++) {
                                mono += encoding == AudioFormat.ENCODING_PCM_FLOAT ? decoded.getFloat() : decoded.getShort() / 32768f;
                            }
                            mono /= Math.max(1, channels);
                            phase += OUTPUT_SAMPLE_RATE;
                            while (sampleRate > 0 && phase >= sampleRate) {
                                phase -= sampleRate;
                                short pcm = (short) (Math.max(-1f, Math.min(1f, mono)) * 32767f);
                                pcmBytes[0] = (byte) (pcm & 0xff);
                                pcmBytes[1] = (byte) ((pcm >>> 8) & 0xff);
                                if (!sink.accept(pcmBytes, 2)) {
                                    return;
                                }
                            }
                        }
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
        }
    }

    /** Streams the recording through the sherpa/GigaAM recognizer, keeping only one chunk in memory. */
    private static String transcribeWithSherpa(File file, MessageObject message, boolean gigaAm, String key, int cacheFreeMb, PartialListener listener) throws Exception {
        final int maxSamples = OUTPUT_SAMPLE_RATE * 7;
        final int minSamples = OUTPUT_SAMPLE_RATE * 4;
        final int silenceSamples = OUTPUT_SAMPLE_RATE / 3;
        float[] chunkSamples = new float[maxSamples];
        int[] state = {0, 0}; // samples in the current segment, consecutive quiet samples
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        final long[] decoded = {0};
        final long[] inferMs = {0};
        final int[] segments = {0};
        final int[] droppedSegments = {0};
        final boolean[] aborted = {false};
        final long[] peakNative = {Debug.getNativeHeapAllocatedSize()};
        final int[] peakPss = {HgramGigaAmRecognizer.currentPssMb()};
        final int buildsBefore = gigaAm ? HgramGigaAmRecognizer.recognizerBuildCount() : HgramSherpaRecognizer.recognizerBuildCount();
        StringBuilder result = new StringBuilder();
        HgramPcmStreamer streamer = new HgramPcmStreamer(OUTPUT_SAMPLE_RATE);
        HgramPcmStreamer.Sink sink = (bytes, length) -> {
            for (int i = 0; i + 1 < length; i += 2) {
                short pcm = (short) ((bytes[i] & 0xff) | (bytes[i + 1] << 8));
                float sample = pcm / 32768f;
                chunkSamples[state[0]++] = sample;
                state[1] = Math.abs(sample) < 0.012f ? state[1] + 1 : 0;
                decoded[0]++;
                if ((decoded[0] & 0xfff) == 0) {
                    // Check cancellation more often than once per second so a tap is observed
                    // promptly even when the decoder is producing 0.5 s chunks.
                    peakNative[0] = Math.max(peakNative[0], Debug.getNativeHeapAllocatedSize());
                    long audioMs = decoded[0] / 16;
                    if (CANCELLED.contains(key) || Thread.currentThread().isInterrupted()
                            || android.os.SystemClock.elapsedRealtime() - startedAt > 10_000 + audioMs * 3) {
                        aborted[0] = true;
                        return false;
                    }
                }
                if (state[0] >= maxSamples || state[0] >= minSamples && state[1] >= silenceSamples) {
                    if (CANCELLED.contains(key)) {
                        aborted[0] = true;
                        return false;
                    }
                    int useful = Math.max(1, state[0] - state[1]);
                    final long segmentStartedAt = android.os.SystemClock.elapsedRealtime();
                    if (!appendSherpaSegment(result, chunkSamples, useful, message, gigaAm, listener)) {
                        droppedSegments[0]++;
                    }
                    inferMs[0] += android.os.SystemClock.elapsedRealtime() - segmentStartedAt;
                    segments[0]++;
                    peakNative[0] = Math.max(peakNative[0], Debug.getNativeHeapAllocatedSize());
                    peakPss[0] = Math.max(peakPss[0], HgramGigaAmRecognizer.currentPssMb());
                    state[0] = state[1] = 0;
                }
            }
            return true;
        };
        try {
            decodeToPcm16Mono(file, sink);
            if (!aborted[0]) {
                streamer.flush(sink);
                if (!aborted[0] && state[0] > 0) {
                    final long segmentStartedAt = android.os.SystemClock.elapsedRealtime();
                    if (!appendSherpaSegment(result, chunkSamples, state[0], message, gigaAm, listener)) {
                        droppedSegments[0]++;
                    }
                    inferMs[0] += android.os.SystemClock.elapsedRealtime() - segmentStartedAt;
                    segments[0]++;
                    peakNative[0] = Math.max(peakNative[0], Debug.getNativeHeapAllocatedSize());
                    peakPss[0] = Math.max(peakPss[0], HgramGigaAmRecognizer.currentPssMb());
                }
            } else {
                org.telegram.messenger.FileLog.e("Hgram transcription aborted: decoder exceeded its time budget");
            }
            final long totalMs = android.os.SystemClock.elapsedRealtime() - startedAt;
            final int builds = (gigaAm ? HgramGigaAmRecognizer.recognizerBuildCount() : HgramSherpaRecognizer.recognizerBuildCount()) - buildsBefore;
            final long audioMs = decoded[0] / 16L;
            final String stats = "HgramSTT engine=" + (gigaAm ? "gigaam" : "sherpa")
                    + " totalMs=" + totalMs
                    + " decodeMs=" + Math.max(0, totalMs - inferMs[0])
                    + " inferMs=" + inferMs[0]
                    + " segments=" + segments[0]
                    + " samples=" + decoded[0]
                    + " recognizerBuilds=" + builds
                    + (gigaAm ? " threads=" + HgramGigaAmRecognizer.threadCount() : "")
                    + " droppedSegments=" + droppedSegments[0]
                    + " javaHeapMb=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
                    + " nativeHeapMb=" + Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                    + " nativeHeapAfterModelMb=" + (gigaAm ? HgramGigaAmRecognizer.modelNativeHeapMb() : 0)
                    + " pssAfterModelMb=" + (gigaAm ? HgramGigaAmRecognizer.modelPssMb() : 0)
                    + " nativeHeapPeakMb=" + peakNative[0] / (1024 * 1024)
                    + " pssPeakMb=" + peakPss[0]
                    + " pssMb=" + HgramGigaAmRecognizer.currentPssMb()
                    + " cacheFreeMb=" + cacheFreeMb
                    + " cancelled=" + CANCELLED.contains(key)
                    + " audioMs=" + audioMs
                    + " ts=" + System.currentTimeMillis();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d(stats);
            }
            appendHgramSttStats(stats);
            return result.toString().trim();
        } finally {
            // The acoustic model is several hundred megabytes: keep it only while this
            // recording is being processed and return the memory to the system afterwards.
            if (gigaAm) {
                HgramGigaAmRecognizer.releaseRecognizer();
            } else {
                HgramSherpaRecognizer.releaseRecognizer();
            }
        }
    }

    private static final long STT_STATS_MAX_BYTES = 256 * 1024;

    /** Drops main-process bitmap caches before the model is mapped; the gain is passed to :stt. */
    private static int clearMainImageCaches() {
        if (ApplicationLoader.isHgramSttProcess()) {
            // ImageLoader belongs to the main process; :stt must not create bitmaps or the
            // account-scoped caches behind it.
            return 0;
        }
        final long before = Debug.getNativeHeapAllocatedSize();
        try {
            ImageLoader.getInstance().clearMemory();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        final long after = Debug.getNativeHeapAllocatedSize();
        final int freedMb = (int) (Math.max(0, before - after) / (1024 * 1024));
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("HgramSTT mainCacheFreeMb=" + freedMb);
        }
        return freedMb;
    }

    private static void appendHgramSttStats(String line) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File file = new File(dir, "hgram_stt_stats.txt");
            if (file.exists() && file.length() > STT_STATS_MAX_BYTES) {
                File rotated = new File(dir, "hgram_stt_stats.1.txt");
                if (rotated.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    rotated.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(rotated);
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // Diagnostics must never affect transcription.
        }
    }

    private static boolean appendSherpaSegment(StringBuilder result, float[] samples, int length, MessageObject message, boolean gigaAm, PartialListener listener) {
        HgramSpeechStream stream = gigaAm ? HgramGigaAmRecognizer.createStream() : HgramSherpaRecognizer.createStream();
        if (stream == null) {
            FileLog.e("HgramSTT segment dropped: stream=null");
            return false;
        }
        try {
            stream.accept(samples, length);
            String part = stream.finish().trim();
            if (!part.isEmpty()) {
                if (result.length() > 0) result.append(' ');
                result.append(part);
                String partial = result.toString();
                if (message != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (message.messageOwner != null) {
                            message.messageOwner.voiceTranscription = partial;
                            message.messageOwner.voiceTranscriptionOpen = true;
                            message.messageOwner.voiceTranscriptionFinal = false;
                            org.telegram.messenger.NotificationCenter.getInstance(message.currentAccount)
                                    .postNotificationName(org.telegram.messenger.NotificationCenter.voiceTranscriptionUpdate, message);
                        }
                    });
                }
                if (listener != null) {
                    listener.onPartial(partial);
                }
            }
        } finally {
            stream.release();
        }
        return true;
    }

    /** Streams the recording through the Vosk recognizer, keeping only one chunk in memory. */
    private static String transcribeWithVosk(File file, String lang, MessageObject message, PartialListener listener) throws Exception {
        try (Model model = new Model(HgramVoskModels.modelDir(lang).getAbsolutePath());
             Recognizer recognizer = new Recognizer(model, OUTPUT_SAMPLE_RATE)) {
            StringBuilder text = new StringBuilder();
            HgramPcmStreamer streamer = new HgramPcmStreamer(OUTPUT_SAMPLE_RATE);
            decodeToPcm16Mono(file, (chunk, length) -> {
                if (recognizer.acceptWaveForm(chunk, length)) {
                    appendResult(text, recognizer.getResult());
                    publishPartialTranscription(message, text, listener);
                }
                return true;
            });
            streamer.flush((chunk, length) -> {
                if (recognizer.acceptWaveForm(chunk, length)) {
                    appendResult(text, recognizer.getResult());
                    publishPartialTranscription(message, text, listener);
                }
                return true;
            });
            appendResult(text, recognizer.getFinalResult());
            publishPartialTranscription(message, text, listener);
            return text.toString();
        }
    }

    private static void publishPartialTranscription(MessageObject message, StringBuilder text, PartialListener listener) {
        String partial = text.toString().trim();
        if (partial.isEmpty()) return;
        if (message != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (message.messageOwner == null) return;
                message.messageOwner.voiceTranscription = partial;
                message.messageOwner.voiceTranscriptionOpen = true;
                message.messageOwner.voiceTranscriptionFinal = false;
                org.telegram.messenger.NotificationCenter.getInstance(message.currentAccount)
                        .postNotificationName(org.telegram.messenger.NotificationCenter.voiceTranscriptionUpdate, message);
            });
        }
        if (listener != null) {
            listener.onPartial(partial);
        }
    }

    private static String key(MessageObject message) {
        return message.currentAccount + ":" + message.getDialogId() + ":" + message.getId();
    }
}
