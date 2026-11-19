package xyz.nextalone.nagram.hgram

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * GigaAM-v3 end-to-end CTC, dynamically quantized to INT8.
 *
 * CTC is a single forward pass over the whole spectrogram, unlike RNN-T, which runs its
 * decoder at every frame and can loop on noisy audio. The e2e head emits punctuation,
 * capitalization and normalized numbers directly, so no second punctuation model is needed.
 *
 * The 305 MiB graph is never bundled into the APK: it is downloaded on demand and loaded by
 * path by sherpa-onnx, so the weights are memory-mapped instead of copied into the Java heap.
 */
object HgramGigaAmRecognizer {

    private const val FOLDER = "gigaam"
    private const val MODEL_FILE = "gigaam_v3_e2e_ctc_int8.onnx"
    private const val TOKENS_FILE = "gigaam_v3_e2e_ctc_tokens.txt"
    private const val MODEL_URL = "https://huggingface.co/Smirnov75/GigaAM-v3-sherpa-onnx/resolve/main/gigaam_v3_e2e_ctc_int8.onnx?download=true"
    private const val TOKENS_URL = "https://huggingface.co/Smirnov75/GigaAM-v3-sherpa-onnx/resolve/main/gigaam_v3_e2e_ctc_tokens.txt"
    private const val MODEL_SHA256 = "0aacb41f70f0f5aaac4b45dd430337b9e16b180f22c72af04db8516e7609c3c0"
    // SHA-256 of the token file contents, not the git-blob pointer stored in the HF tree.
    private const val TOKENS_SHA256 = "f8eb9b115e2748db9c40a5897cae11dd0678cc0b40fd7e25f8c43b3bf28715e4"
    private const val DIGEST_PREFS = "hgram_gigaam"
    private const val KEY_DIGEST_OK = "gigaam_digest_ok"
    // GigaAM is already several times faster than real time on two threads. More ONNX threads
    // create per-thread native arenas without changing the 305 MiB model size, so the default
    // favours the memory peak. Set to 2 or 6 for the A/B measurement requested by the review.
    private const val THREADS_OVERRIDE = 2
    private const val SAMPLE_RATE = 16000
    private const val MIN_FREE_MEMORY_FOR_MODEL = 700L * 1024 * 1024
    private const val EXTRA_FREE_SPACE = 64L * 1024 * 1024

    const val MODEL_SIZE_BYTES = 319_869_121L
    const val TOKENS_SIZE_BYTES = 2_006L
    const val TOTAL_SIZE_BYTES = MODEL_SIZE_BYTES + TOKENS_SIZE_BYTES

    const val ERROR_NONE = 0
    const val ERROR_NO_SPACE = 1
    const val ERROR_NETWORK = 2
    const val ERROR_HASH = 3
    const val ERROR_CANCELLED = 4
    const val ERROR_IO = 5
    const val ERROR_ALREADY_DOWNLOADING = 6

    private val progressPercent = AtomicInteger(-1)
    private val lastPostedProgress = AtomicInteger(-1)

    @Volatile
    private var lastError = ERROR_NONE

    @Volatile
    private var cancelled = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var downloadThread: Thread? = null

    @JvmStatic
    fun downloadProgress(): Int = progressPercent.get()

    @JvmStatic
    fun lastError(): Int = lastError

    @JvmStatic
    fun rootDir(): File = File(ApplicationLoader.applicationContext.filesDir, "sherpa/$FOLDER")

    @JvmStatic
    fun modelFile(): File = File(rootDir(), MODEL_FILE)

    @JvmStatic
    fun tokensFile(): File = File(rootDir(), TOKENS_FILE)

    /**
     * Cheap readiness check used by the settings screen and by every transcription tap.
     * The 305 MiB hash is verified once, while downloading, so this does not hash the file.
     */
    @JvmStatic
    fun isInstalled(): Boolean {
        return modelFile().isFile && modelFile().length() == MODEL_SIZE_BYTES &&
                tokensFile().isFile && tokensFile().length() == TOKENS_SIZE_BYTES
    }

    private fun digestPreferences(): android.content.SharedPreferences? {
        val context = ApplicationLoader.applicationContext ?: return null
        return context.getSharedPreferences(DIGEST_PREFS, Context.MODE_PRIVATE)
    }

    @Volatile
    private var digestVerified: Boolean? = null

    private fun isDigestMarkedOk(): Boolean {
        digestVerified?.let { return it }
        val preferences = digestPreferences() ?: return false
        val value = preferences.getBoolean(KEY_DIGEST_OK, false)
        digestVerified = value
        return value
    }

    private fun setDigestMarkedOk(ok: Boolean) {
        digestVerified = ok
        digestPreferences()?.edit()?.putBoolean(KEY_DIGEST_OK, ok)?.apply()
    }

    /**
     * Hashes both files once per installation. The result is cached in memory and in prefs so
     * later transcriptions do not spend seconds re-reading 305 MiB. A corrupted file is
     * removed and reported as [ERROR_HASH], so it can never reach the native encoder.
     */
    private fun ensureDigestVerified(modelVerified: Boolean = false, tokensVerified: Boolean = false): Boolean {
        if (isDigestMarkedOk()) {
            return true
        }
        val ok = (modelVerified || verifyDigest(modelFile(), "SHA-256", MODEL_SHA256)) &&
                (tokensVerified || verifyDigest(tokensFile(), "SHA-256", TOKENS_SHA256))
        if (!ok) {
            lastError = ERROR_HASH
            modelFile().delete()
            tokensFile().delete()
            setDigestMarkedOk(false)
            return false
        }
        setDigestMarkedOk(true)
        return true
    }

    @JvmStatic
    fun delete() {
        if (progressPercent.get() >= 0) return
        releaseRecognizer()
        rootDir().deleteRecursively()
        setDigestMarkedOk(false)
    }

    /** Stops the current download; the .part files are kept so the next attempt can resume. */
    @JvmStatic
    fun cancelDownload() {
        cancelled = true
        activeConnection?.disconnect()
        downloadThread?.interrupt()
    }

    /**
     * Downloads the graph and tokens with Range resume and streaming SHA checks.
     * Callbacks are delivered on the UI thread. Cancellation and network failures keep the
     * .part files so the next attempt resumes them; only a checksum mismatch deletes them.
     */
    @JvmStatic
    fun download(onProgress: ((Int) -> Unit)?, onDone: ((Boolean) -> Unit)?) {
        if (!progressPercent.compareAndSet(-1, 0)) {
            lastError = ERROR_ALREADY_DOWNLOADING
            postDone(onDone, false)
            return
        }
        cancelled = false
        lastError = ERROR_NONE
        lastPostedProgress.set(-1)
        progressPercent.set(0)
        val thread = Thread({
            var success = false
            var modelVerified = false
            var tokensVerified = false
            var modelPart: File? = null
            var tokensPart: File? = null
            try {
                val dir = rootDir()
                if (!dir.isDirectory && !dir.mkdirs()) {
                    lastError = ERROR_IO
                    return@Thread
                }
                modelPart = File(dir, "$MODEL_FILE.part")
                tokensPart = File(dir, "$TOKENS_FILE.part")
                if (isInstalled() && ensureDigestVerified()) {
                    success = true
                    return@Thread
                }
                val freeSpace = dir.usableSpace
                val needed = (if (modelFile().length() == MODEL_SIZE_BYTES) 0 else MODEL_SIZE_BYTES) +
                        (if (tokensFile().length() == TOKENS_SIZE_BYTES) 0 else TOKENS_SIZE_BYTES)
                if (freeSpace in 1 until needed + EXTRA_FREE_SPACE) {
                    lastError = ERROR_NO_SPACE
                    return@Thread
                }
                if (modelFile().length() != MODEL_SIZE_BYTES) {
                    modelVerified = downloadFile(
                        MODEL_URL, modelFile(), modelPart, MODEL_SIZE_BYTES,
                        "SHA-256", MODEL_SHA256, 0L, onProgress
                    )
                }
                if (tokensFile().length() != TOKENS_SIZE_BYTES) {
                    tokensVerified = downloadFile(
                        TOKENS_URL, tokensFile(), tokensPart, TOKENS_SIZE_BYTES,
                        "SHA-256", TOKENS_SHA256, MODEL_SIZE_BYTES, onProgress
                    )
                }
                if (cancelled) {
                    throw CancellationException()
                }
                success = isInstalled() && ensureDigestVerified(modelVerified, tokensVerified)
                if (!success && lastError == ERROR_NONE) {
                    lastError = ERROR_IO
                }
            } catch (e: Throwable) {
                if (cancelled) {
                    lastError = ERROR_CANCELLED
                    // Keep the .part files: the next attempt resumes them with Range.
                } else if (lastError == ERROR_NONE) {
                    lastError = ERROR_NETWORK
                    FileLog.e(e)
                }
            } finally {
                progressPercent.set(-1)
                activeConnection = null
                downloadThread = null
                cancelled = false
                postDone(onDone, success)
            }
        }, "hgram-gigaam-download")
        thread.isDaemon = true
        downloadThread = thread
        thread.start()
    }

    private fun downloadFile(
        url: String,
        target: File,
        part: File,
        expectedSize: Long,
        digestName: String,
        digestHex: String,
        progressBase: Long,
        onProgress: ((Int) -> Unit)?,
    ): Boolean {
        if (target.isFile && target.length() == expectedSize) {
            publishProgress(((progressBase + expectedSize) * 100 / TOTAL_SIZE_BYTES).toInt(), onProgress)
            return isDigestMarkedOk()
        }
        if (target.isFile) {
            target.delete()
        }
        var offset = if (part.isFile) part.length() else 0L
        if (offset > expectedSize) {
            part.delete()
            offset = 0L
        }
        if (offset == expectedSize) {
            if (!verifyDigest(part, digestName, digestHex)) {
                part.delete()
                lastError = ERROR_HASH
                throw IOException("Checksum mismatch")
            }
            if (cancelled) {
                throw CancellationException()
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Cannot replace ${target.name}")
            }
            if (!part.renameTo(target)) {
                throw IOException("Cannot install ${target.name}")
            }
            publishProgress(((progressBase + expectedSize) * 100 / TOTAL_SIZE_BYTES).toInt(), onProgress)
            return true
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                setRequestProperty("Range", "bytes=$offset-")
            }
        }
        activeConnection = connection
        try {
            val response = connection.responseCode
            if (response !in 200..299) {
                throw IOException("HTTP $response")
            }
            if (offset > 0 && response == HttpURLConnection.HTTP_OK) {
                // The server ignored Range; restart rather than append a second copy.
                part.delete()
                offset = 0L
            }
            var downloaded = offset
            connection.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw CancellationException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded > expectedSize) {
                            throw IOException("Model is larger than expected")
                        }
                        publishProgress(((progressBase + downloaded) * 100 / TOTAL_SIZE_BYTES).toInt(), onProgress)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (downloaded != expectedSize) {
                throw IOException("Incomplete file: $downloaded of $expectedSize")
            }
            if (!verifyDigest(part, digestName, digestHex)) {
                part.delete()
                lastError = ERROR_HASH
                throw IOException("Checksum mismatch")
            }
            if (cancelled) {
                throw CancellationException()
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Cannot replace ${target.name}")
            }
            if (!part.renameTo(target)) {
                throw IOException("Cannot install ${target.name}")
            }
            publishProgress(((progressBase + expectedSize) * 100 / TOTAL_SIZE_BYTES).toInt(), onProgress)
            return true
        } finally {
            connection.disconnect()
            if (activeConnection === connection) {
                activeConnection = null
            }
        }
    }

    private fun publishProgress(percent: Int, onProgress: ((Int) -> Unit)?) {
        val value = percent.coerceIn(0, 100)
        progressPercent.set(value)
        if (onProgress == null) return
        if (lastPostedProgress.getAndSet(value) == value) return
        AndroidUtilities.runOnUIThread { onProgress(value) }
    }

    private fun postDone(onDone: ((Boolean) -> Unit)?, success: Boolean) {
        if (onDone != null) {
            AndroidUtilities.runOnUIThread { onDone(success) }
        }
    }

    private fun verifyDigest(file: File, algorithm: String, expected: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance(algorithm)
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) {
                        throw CancellationException()
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expected, ignoreCase = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            FileLog.e(e)
            false
        }
    }

    /** Creates a session for one speech segment; null when the model is missing or memory is low. */
    @JvmStatic
    @Synchronized
    fun createStream(): HgramSpeechStream? {
        if (HgramAsrCompatibility.isUnsupportedEmulator()) return null
        if (!isInstalled() || !ensureDigestVerified()) return null
        return try {
            if (cachedRecognizer == null && !hasEnoughMemoryForModel()) {
                FileLog.e("Hgram GigaAM skipped: not enough free memory for the ASR model")
                return null
            }
            val recognizer = obtainRecognizer() ?: return null
            val offlineStream = try {
                recognizer.createStream()
            } catch (t: Throwable) {
                // The counter is incremented only after a successful native allocation, so a
                // failed createStream() cannot leave activeStreams above zero. Keep the cached
                // graph for the next segment; releaseRecognizer() frees it at the end of the run.
                FileLog.e(t)
                return null
            }
            activeStreams++
            Stream(recognizer, offlineStream) { onStreamReleased() }
        } catch (e: OutOfMemoryError) {
            releaseRecognizer()
            throw e
        }
    }

    private fun hasEnoughMemoryForModel(): Boolean {
        return try {
            val manager = ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.availMem >= MIN_FREE_MEMORY_FOR_MODEL
        } catch (e: Throwable) {
            FileLog.e(e)
            true
        }
    }

    @Volatile
    private var cachedRecognizer: OfflineRecognizer? = null
    private var cachedKey: String? = null
    private var activeStreams = 0
    private var releasePending = false
    @Volatile
    private var recognizerBuilds = 0
    @Volatile
    private var modelNativeHeapBytes = 0L
    @Volatile
    private var modelPssKb = 0

    @Synchronized
    private fun onStreamReleased() {
        if (activeStreams > 0) {
            activeStreams--
        }
        // The transcription pipeline creates one stream per 4-7 second segment. Releasing the
        // 305 MiB graph after every segment would force a fresh ONNX session (1-4 s) and blow
        // the transcription time budget, so free it only after releaseRecognizer() asks for it.
        if (activeStreams == 0 && releasePending) {
            releasePending = false
            releaseRecognizerNow()
        }
    }

    @Synchronized
    private fun releaseRecognizerNow() {
        cachedRecognizer?.release()
        cachedRecognizer = null
        cachedKey = null
    }

    @Synchronized
    private fun obtainRecognizer(): OfflineRecognizer? {
        val dir = rootDir()
        val key = dir.absolutePath
        val cached = cachedRecognizer
        if (cached != null) {
            if (cachedKey == key) {
                return cached
            }
            if (activeStreams > 0) {
                return cached
            }
            releaseRecognizerNow()
        }
        // GigaAM uses 64 mel bins and its own end-to-end CTC head. INT8 is fastest on CPU,
        // which also avoids driver-specific NNAPI/XNNPACK graph compilation at run time.
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 64),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile().absolutePath),
                tokens = tokensFile().absolutePath,
                numThreads = threadCount(),
                provider = HgramConfig.SHERPA_PROVIDER_CPU,
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        val recognizer = try {
            OfflineRecognizer(null, config)
        } catch (e: Throwable) {
            FileLog.e(e)
            return null
        }
        cachedRecognizer = recognizer
        cachedKey = key
        recognizerBuilds++
        // Taken before the first segment/stream exists: this isolates the model and its
        // per-thread arenas from the transient decode buffers.
        modelNativeHeapBytes = Debug.getNativeHeapAllocatedSize()
        modelPssKb = currentPssKb()
        return recognizer
    }

    /** Number of native recognizer graphs built in this process (for HgramSTT diagnostics). */
    @JvmStatic
    fun recognizerBuildCount(): Int = recognizerBuilds

    /** Native heap right after the model graph was built (MiB), for HgramSTT diagnostics. */
    @JvmStatic
    fun modelNativeHeapMb(): Long = modelNativeHeapBytes / (1024 * 1024)

    /** Process PSS right after the model graph was built (MiB), for HgramSTT diagnostics. */
    @JvmStatic
    fun modelPssMb(): Int = modelPssKb / 1024

    @JvmStatic
    fun currentPssMb(): Int = currentPssKb() / 1024

    private fun currentPssKb(): Int {
        return try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss
        } catch (e: Throwable) {
            0
        }
    }

    /**
     * Threads used by the GigaAM CTC graph. THREADS_OVERRIDE is the single switch for the
     * 2-vs-6 thread A/B: change it and rebuild; 0 falls back to the device core count.
     */
    @JvmStatic
    fun threadCount(): Int {
        if (THREADS_OVERRIDE in 2..6) {
            return THREADS_OVERRIDE
        }
        return Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    }

    /** Releases the native graph after the last segment; the next run loads it again. */
    @JvmStatic
    @Synchronized
    fun releaseRecognizer() {
        if (activeStreams == 0) {
            releasePending = false
            releaseRecognizerNow()
        } else {
            releasePending = true
        }
    }

    /**
     * A CTC session. Segments are bounded by the transcription pipeline, so the encoder sees
     * a short spectrogram and never accumulates activations for a ten-minute recording.
     */
    private class Stream internal constructor(
        private val recognizer: OfflineRecognizer,
        private val offlineStream: OfflineStream,
        private val onRelease: Runnable,
    ) : HgramSpeechStream {
        private val chunk = FloatArray(SAMPLE_RATE)
        private var released = false

        override fun accept(samples: FloatArray, length: Int) {
            var offset = 0
            while (offset < length) {
                val size = minOf(chunk.size, length - offset)
                System.arraycopy(samples, offset, chunk, 0, size)
                offlineStream.acceptWaveform(chunk, size)
                offset += size
            }
        }

        override fun finish(): String {
            recognizer.decode(offlineStream)
            return recognizer.getResult(offlineStream).text
        }

        override fun release() {
            if (released) return
            released = true
            offlineStream.release()
            onRelease.run()
        }
    }
}
