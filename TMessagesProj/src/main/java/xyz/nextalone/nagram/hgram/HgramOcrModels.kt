package xyz.nextalone.nagram.hgram

import android.content.Context
import org.telegram.messenger.AndroidUtilities
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
 * On-demand tessdata_fast models for [HgramOcrEngine].
 *
 * The two traineddata files are no longer bundled into the APK. They are downloaded into
 * `<filesDir>/hgram_tessdata/tessdata/`, resumed with HTTP Range and verified by SHA-256.
 * Only the final status codes are exposed; recognition text never passes through here.
 */
object HgramOcrModels {

    const val ROOT_DIR = "hgram_tessdata"
    const val MODELS_DIR = "tessdata"

    const val LANG_RU = "rus"
    const val LANG_EN = "eng"

    const val URL_RU = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/rus.traineddata"
    const val SIZE_RU = 3_861_738L
    const val SHA256_RU = "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"

    const val URL_EN = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata"
    const val SIZE_EN = 4_113_088L
    const val SHA256_EN = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"

    const val TOTAL_SIZE_BYTES = SIZE_RU + SIZE_EN

    const val ERROR_NONE = 0
    const val ERROR_NO_SPACE = 1
    const val ERROR_NETWORK = 2
    const val ERROR_HASH = 3
    const val ERROR_CANCELLED = 4
    const val ERROR_IO = 5
    const val ERROR_ALREADY_DOWNLOADING = 6

    /** One traineddata file: id is the Tesseract language name, fileName lives under tessdata/. */
    data class ModelSpec(
        val id: String,
        val fileName: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    @JvmField
    val MODELS: List<ModelSpec> = listOf(
        ModelSpec(LANG_RU, "$LANG_RU.traineddata", URL_RU, SIZE_RU, SHA256_RU),
        ModelSpec(LANG_EN, "$LANG_EN.traineddata", URL_EN, SIZE_EN, SHA256_EN),
    )

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val EXTRA_FREE_SPACE = 64L * 1024 * 1024
    private const val USER_AGENT = "Nagram-OCR/1.0"
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Test seam: lets a unit test serve bytes without opening a socket. */
    internal fun interface ConnectionFactory {
        fun open(url: String): HttpURLConnection
    }

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

    @Volatile
    internal var connectionFactory: ConnectionFactory = ConnectionFactory { url ->
        URL(url).openConnection() as HttpURLConnection
    }

    /** -1 when idle, 0..100 while a download is running. */
    @JvmStatic
    fun progress(): Int = progressPercent.get()

    @JvmStatic
    fun lastError(): Int = lastError

    @JvmStatic
    fun isReady(context: Context?): Boolean {
        val filesDir = context?.filesDir ?: return false
        return isReady(File(filesDir, ROOT_DIR), MODELS)
    }

    /**
     * Pure readiness check: every spec must exist with the exact size and SHA-256.
     * The 8 MiB total is hashed on every call; no contents are logged.
     */
    @JvmStatic
    fun isReady(root: File?, specs: List<ModelSpec>?): Boolean {
        if (root == null || specs == null || specs.isEmpty()) {
            return false
        }
        val models = File(root, MODELS_DIR)
        for (spec in specs) {
            val file = File(models, spec.fileName)
            if (!file.isFile || file.length() != spec.size) {
                return false
            }
            val valid = try {
                verifyDigest(file, spec.sha256)
            } catch (e: Throwable) {
                false
            }
            if (!valid) {
                return false
            }
        }
        return true
    }

    /**
     * Downloads both files on one background thread. Callbacks arrive on the UI thread.
     * Network failures and cancellation keep `.part` for the next Range resume; a checksum
     * mismatch deletes it.
     */
    @JvmStatic
    fun download(context: Context, onProgress: ((Int) -> Unit)?, onDone: ((Boolean) -> Unit)?) {
        download(File(context.filesDir, ROOT_DIR), MODELS, onProgress, onDone)
    }

    internal fun download(
        root: File,
        specs: List<ModelSpec>,
        onProgress: ((Int) -> Unit)?,
        onDone: ((Boolean) -> Unit)?,
    ) {
        if (!progressPercent.compareAndSet(-1, 0)) {
            lastError = ERROR_ALREADY_DOWNLOADING
            postDone(onDone, false)
            return
        }
        cancelled = false
        lastError = ERROR_NONE
        lastPostedProgress.set(-1)

        val thread = Thread({
            var success = false
            try {
                val models = File(root, MODELS_DIR)
                if (!models.isDirectory && !models.mkdirs() && !models.isDirectory) {
                    lastError = ERROR_IO
                    return@Thread
                }
                if (isReady(root, specs)) {
                    if (cancelled) {
                        throw CancellationException()
                    }
                    publishProgress(100, onProgress)
                    success = true
                    return@Thread
                }
                val total = specs.sumOf { it.size }
                val missing = specs.sumOf { spec ->
                    val target = File(models, spec.fileName)
                    if (target.isFile && target.length() == spec.size) 0L else spec.size
                }
                val usable = root.usableSpace
                if (usable > 0 && usable < missing + EXTRA_FREE_SPACE) {
                    lastError = ERROR_NO_SPACE
                    return@Thread
                }
                var base = 0L
                for (spec in specs) {
                    if (cancelled) {
                        throw CancellationException()
                    }
                    val target = File(models, spec.fileName)
                    val part = File(models, spec.fileName + ".part")
                    if (target.isFile && target.length() == spec.size && verifyDigest(target, spec.sha256)) {
                        publishProgress(percent(base + spec.size, total), onProgress)
                    } else {
                        downloadFileTo(spec, target, part, base, total, onProgress)
                    }
                    base += spec.size
                }
                if (cancelled) {
                    throw CancellationException()
                }
                success = isReady(root, specs)
                if (cancelled) {
                    throw CancellationException()
                }
                if (!success && lastError == ERROR_NONE) {
                    lastError = ERROR_IO
                }
            } catch (e: CancellationException) {
                lastError = ERROR_CANCELLED
                // Keep .part files: the next attempt resumes them with Range.
            } catch (e: Throwable) {
                if (cancelled) {
                    lastError = ERROR_CANCELLED
                } else if (lastError == ERROR_NONE) {
                    lastError = ERROR_NETWORK
                }
            } finally {
                progressPercent.set(-1)
                activeConnection = null
                downloadThread = null
                cancelled = false
                postDone(onDone, success)
            }
        }, "hgram-tessdata-download")
        thread.isDaemon = true
        downloadThread = thread
        thread.start()
    }

    /** Stops the download; `.part` files stay on disk for the next resume. */
    @JvmStatic
    fun cancel() {
        cancelled = true
        activeConnection?.disconnect()
        downloadThread?.interrupt()
    }

    /** Deletes the downloaded models recursively; ignored while a download is running. */
    @JvmStatic
    fun delete(context: Context) {
        if (progressPercent.get() >= 0) {
            return
        }
        File(File(context.filesDir, ROOT_DIR), MODELS_DIR).deleteRecursively()
        lastError = ERROR_NONE
    }

    /**
     * Downloads one spec with Range resume, verifies SHA-256 and renames `.part` into place.
     * Throws [IOException] on HTTP/disk/size problems and [CancellationException] on cancel.
     */
    internal fun downloadFileTo(
        spec: ModelSpec,
        target: File,
        part: File,
        progressBase: Long,
        total: Long,
        onProgress: ((Int) -> Unit)?,
    ): Boolean {
        if (target.isFile && target.length() == spec.size && verifyDigest(target, spec.sha256)) {
            publishProgress(percent(progressBase + spec.size, total), onProgress)
            return true
        }
        if (target.isFile && !target.delete()) {
            throw IOException("Cannot replace ${target.name}")
        }
        var offset = if (part.isFile) part.length() else 0L
        if (offset > spec.size) {
            part.delete()
            offset = 0L
        }
        if (offset == spec.size) {
            if (!verifyDigest(part, spec.sha256)) {
                part.delete()
                lastError = ERROR_HASH
                throw IOException("Checksum mismatch")
            }
            if (cancelled) {
                throw CancellationException()
            }
            install(part, target)
            publishProgress(percent(progressBase + spec.size, total), onProgress)
            return true
        }

        val connection = connectionFactory.open(spec.url)
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) {
            connection.setRequestProperty("Range", "bytes=$offset-")
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
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled) {
                            throw CancellationException()
                        }
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded > spec.size) {
                            throw IOException("Model is larger than expected")
                        }
                        publishProgress(percent(progressBase + downloaded, total), onProgress)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (downloaded != spec.size) {
                throw IOException("Incomplete file: $downloaded of ${spec.size}")
            }
            if (!verifyDigest(part, spec.sha256)) {
                part.delete()
                lastError = ERROR_HASH
                throw IOException("Checksum mismatch")
            }
            if (cancelled) {
                throw CancellationException()
            }
            install(part, target)
            publishProgress(percent(progressBase + spec.size, total), onProgress)
            return true
        } finally {
            connection.disconnect()
            if (activeConnection === connection) {
                activeConnection = null
            }
        }
    }

    internal fun resetForTests() {
        progressPercent.set(-1)
        lastPostedProgress.set(-1)
        lastError = ERROR_NONE
        cancelled = false
        activeConnection = null
        downloadThread = null
    }

    internal fun downloadThreadForTests(): Thread? = downloadThread

    internal fun activeConnectionForTests(): HttpURLConnection? = activeConnection

    private fun install(part: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw IOException("Cannot replace ${target.name}")
        }
        if (!part.renameTo(target)) {
            throw IOException("Cannot install ${target.name}")
        }
    }

    private fun publishProgress(percent: Int, onProgress: ((Int) -> Unit)?) {
        val value = percent.coerceIn(0, 100)
        progressPercent.set(value)
        if (onProgress == null) {
            return
        }
        if (lastPostedProgress.getAndSet(value) == value) {
            return
        }
        AndroidUtilities.runOnUIThread { onProgress(value) }
    }

    private fun postDone(onDone: ((Boolean) -> Unit)?, success: Boolean) {
        if (onDone != null) {
            AndroidUtilities.runOnUIThread { onDone(success) }
        }
    }

    private fun percent(done: Long, total: Long): Int {
        if (total <= 0) {
            return 0
        }
        return (done.coerceAtLeast(0) * 100 / total).toInt().coerceIn(0, 100)
    }

    private fun verifyDigest(file: File, expected: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (cancelled && progressPercent.get() >= 0) {
                        throw CancellationException()
                    }
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    digest.update(buffer, 0, read)
                }
            }
            toHex(digest.digest()).equals(expected, ignoreCase = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (value in bytes) {
            val byte = value.toInt()
            out.append(HEX_DIGITS[(byte shr 4) and 0x0F])
            out.append(HEX_DIGITS[byte and 0x0F])
        }
        return out.toString()
    }
}
