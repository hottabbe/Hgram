package xyz.nextalone.nagram.hgram

import android.app.ActivityManager
import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.ApplicationLoader
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * sherpa-onnx Russian transducer model, downloaded on demand from the k2-fsa asr-models release.
 * Recognition is fully offline once the model is installed.
 */
object HgramSherpaRecognizer {

    private const val FOLDER = "sherpa-onnx-zipformer-ru-2024-09-18"
    private const val ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$FOLDER.tar.bz2"
    private const val SAMPLE_RATE = 16000
    private val progressPercent = AtomicInteger(-1)

    @JvmStatic
    fun downloadProgress(): Int = progressPercent.get()

    @JvmStatic
    fun rootDir(): File = File(ApplicationLoader.applicationContext.filesDir, "sherpa/ru")

    @JvmStatic
    fun modelDir(): File = File(rootDir(), FOLDER)

    private class ModelFiles(val dir: File, val encoder: File, val decoder: File, val joiner: File)

    @Volatile
    private var cachedFiles: ModelFiles? = null

    private fun resolveModelFiles(): ModelFiles? {
        val dir = modelDir()
        val existing = cachedFiles
        if (existing != null && existing.dir.absolutePath == dir.absolutePath) {
            return existing
        }
        val encoder = findOnnx(dir, "encoder") ?: return null
        val decoder = findOnnx(dir, "decoder") ?: return null
        val joiner = findOnnx(dir, "joiner") ?: return null
        return ModelFiles(dir, encoder, decoder, joiner).also { cachedFiles = it }
    }

    private fun findOnnx(dir: File, prefix: String): File? {
        val files = dir.listFiles() ?: return null
        return files.firstOrNull { it.isFile && it.length() > 0 && it.name.startsWith(prefix) && it.name.contains("int8") && it.name.endsWith(".onnx") }
            ?: files.firstOrNull { it.isFile && it.length() > 0 && it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
    }

    @JvmStatic
    fun isInstalled(): Boolean = isValidModel(modelDir())

    @JvmStatic
    fun isValidModel(dir: File): Boolean {
        val tokens = File(dir, "tokens.txt")
        return findOnnx(dir, "encoder") != null && findOnnx(dir, "decoder") != null &&
                findOnnx(dir, "joiner") != null && tokens.isFile && tokens.length() > 0
    }

    @JvmStatic
    fun delete() {
        if (progressPercent.get() >= 0) return
        cachedFiles = null
        rootDir().deleteRecursively()
    }

    /** Downloads and unpacks the archive on a background thread; callbacks are delivered on the UI thread. */
    @JvmStatic
    fun download(onProgress: ((Int) -> Unit)?, onDone: ((Boolean) -> Unit)?) {
        if (!progressPercent.compareAndSet(-1, 0)) {
            if (onDone != null) AndroidUtilities.runOnUIThread { onDone(false) }
            return
        }
        Thread {
            val tmp = File(ApplicationLoader.applicationContext.cacheDir, "$FOLDER.tar.bz2.part")
            val staging = File(rootDir().parentFile, "ru.installing")
            var connection: HttpURLConnection? = null
            var ok = false
            try {
                val request = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
                connection = request
                request.instanceFollowRedirects = true
                request.connectTimeout = 15000
                request.readTimeout = 30000
                request.connect()
                if (request.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${request.responseCode}")
                }
                val total = HgramDownloads.contentLength(request)
                var done = 0L
                var lastPercent = -1
                request.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = minOf(99, (done * 100 / total).toInt())
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    progressPercent.set(percent)
                                    if (onProgress != null) AndroidUtilities.runOnUIThread { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
                request.disconnect()
                staging.deleteRecursively()
                if (!staging.mkdirs() && !staging.isDirectory) throw IOException("Cannot create staging directory")
                extract(tmp, staging)
                val model = File(staging, FOLDER)
                if (!isValidModel(model)) {
                    throw IOException("Incomplete model")
                }
                val root = rootDir()
                root.deleteRecursively()
                if (!staging.renameTo(root)) throw IOException("Cannot install model")
                cachedFiles = null
                ok = isInstalled()
            } catch (e: Exception) {
                ok = false
            } finally {
                connection?.disconnect()
                staging.deleteRecursively()
                tmp.delete()
                progressPercent.set(-1)
            }
            val success = ok
            if (onDone != null) AndroidUtilities.runOnUIThread { onDone(success) }
        }.start()
    }

    private fun extract(archive: File, target: File) {
        val targetPath = target.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !entry.isFile) throw IOException("Unsupported archive entry")
                val out = File(target, entry.name)
                if (!out.canonicalPath.startsWith(targetPath)) {
                    throw SecurityException("Archive entry outside target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
                entry = tar.nextEntry
            }
        }
    }

    /** [pcm16] is mono 16-bit little-endian PCM at 16 kHz. Returns the recognized text. */
    @JvmStatic
    fun transcribe(pcm16: ByteArray): String {
        val samples = FloatArray(pcm16.size / 2) { i ->
            val lo = pcm16[2 * i].toInt() and 0xff
            val hi = pcm16[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
        val stream = createStream() ?: return ""
        try {
            val chunk = FloatArray(CHUNK_SAMPLES)
            var offset = 0
            while (offset < samples.size) {
                val length = minOf(chunk.size, samples.size - offset)
                System.arraycopy(samples, offset, chunk, 0, length)
                stream.accept(chunk, length)
                offset += length
            }
            return stream.finish()
        } finally {
            stream.release()
            releaseRecognizer()
        }
    }

    /**
     * A streaming recognizer session.
     *
     * The caller feeds bounded PCM chunks with [accept] and closes the session with [finish],
     * so the whole recording never has to be held in memory.
     */
    class Stream internal constructor(
        private val recognizer: OfflineRecognizer,
        private val offlineStream: OfflineStream,
        private val onRelease: Runnable,
    ) : HgramSpeechStream {
        private val chunk = FloatArray(CHUNK_SAMPLES)
        private var released = false

        /** Feeds one chunk of mono PCM16 samples (16 kHz) into the recognizer. */
        override fun accept(samples: FloatArray, length: Int) {
            var offset = 0
            while (offset < length) {
                val size = minOf(chunk.size, length - offset)
                System.arraycopy(samples, offset, chunk, 0, size)
                offlineStream.acceptWaveform(chunk, size)
                offset += size
            }
        }

        /** Decodes what has been fed so far and returns the recognized text. */
        override fun finish(): String {
            recognizer.decode(offlineStream)
            return recognizer.getResult(offlineStream).text
        }

        /** Releases the native resources of this session. */
        override fun release() {
            if (released) return
            released = true
            offlineStream.release()
            onRelease.run()
        }
    }

    /**
     * Creates a streaming session for the installed model, or null when the model is missing.
     *
     * The recognizer itself is cached and shared between segments: loading the ~300 MB
     * zipformer model once per segment used to grow native memory to several gigabytes and
     * made Android kill other apps. Call [releaseRecognizer] when the whole transcription is
     * finished.
     */
    @JvmStatic
    @Synchronized
    fun createStream(): Stream? {
        if (HgramAsrCompatibility.isUnsupportedEmulator()) return null
        val files = resolveModelFiles() ?: return null
        val provider = HgramConfig.getSherpaProvider()
        try {
            if (cachedRecognizer == null && !hasEnoughMemoryForModel()) {
                FileLog.e("Hgram transcription skipped: not enough free memory for the ASR model")
                return null
            }
            val recognizer = obtainRecognizer(files.dir, files.encoder, files.decoder, files.joiner, provider) ?: return null
            val offlineStream = try {
                recognizer.createStream()
            } catch (t: Throwable) {
                FileLog.e(t)
                return null
            }
            activeStreams++
            return Stream(recognizer, offlineStream) { onStreamReleased() }
        } catch (e: OutOfMemoryError) {
            releaseRecognizer()
            throw e
        }
    }

    /**
     * The zipformer model needs several hundred megabytes of native memory. Loading it on a
     * phone that is already under memory pressure is what pushed the system into killing
     * SystemUI and other apps, so refuse instead of risking the whole device.
     */
    private fun hasEnoughMemoryForModel(): Boolean {
        return try {
            val context = ApplicationLoader.applicationContext
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            info.availMem >= MIN_FREE_MEMORY_FOR_MODEL
        } catch (e: Throwable) {
            FileLog.e(e)
            true
        }
    }

    private const val MIN_FREE_MEMORY_FOR_MODEL = 700L * 1024 * 1024

    @Volatile
    private var cachedRecognizer: OfflineRecognizer? = null
    private var cachedKey: String? = null
    private var activeStreams = 0
    private var releasePending = false
    @Volatile
    private var recognizerBuilds = 0

    @Synchronized
    private fun onStreamReleased() {
        if (activeStreams > 0) {
            activeStreams--
        }
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
    private fun obtainRecognizer(
        dir: File,
        encoder: File,
        decoder: File,
        joiner: File,
        provider: String,
    ): OfflineRecognizer? {
        val key = "$provider|${dir.absolutePath}"
        val cached = cachedRecognizer
        if (cached != null) {
            if (cachedKey == key) {
                return cached
            }
            if (activeStreams > 0) {
                // A transcription is still running; keep the loaded model until it finishes.
                releasePending = true
                return cached
            }
            releaseRecognizerNow()
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = if (provider == HgramConfig.SHERPA_PROVIDER_CPU) 1 else 2,
                provider = provider,
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        val recognizer = try {
            OfflineRecognizer(null, config)
        } catch (e: Throwable) {
            if (provider == HgramConfig.SHERPA_PROVIDER_CPU) throw e
            // A driver may reject the accelerated graph. Fall back instead of failing the
            // whole transcription; one CPU thread also keeps thermal load lower.
            org.telegram.messenger.FileLog.e(e)
            config.modelConfig.numThreads = 1
            config.modelConfig.provider = HgramConfig.SHERPA_PROVIDER_CPU
            OfflineRecognizer(null, config)
        }
        cachedRecognizer = recognizer
        cachedKey = key
        recognizerBuilds++
        return recognizer
    }

    /** Number of native recognizer graphs built in this process (for HgramSTT diagnostics). */
    @JvmStatic
    fun recognizerBuildCount(): Int = recognizerBuilds

    /** Frees the cached model. Always call it after the last segment of a transcription. */
    @JvmStatic
    @Synchronized
    fun releaseRecognizer() {
        if (activeStreams > 0) {
            releasePending = true
            return
        }
        releaseRecognizerNow()
    }

    private const val CHUNK_SAMPLES = 16000
}
