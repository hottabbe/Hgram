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

/**
 * On-demand tessdata_fast models for [HgramOcrEngine].
 *
 * The two traineddata files are no longer bundled into the APK. They are downloaded into
 * `<filesDir>/hgram_tessdata/tessdata/`, resumed with HTTP Range and verified by SHA-256.
 * Only the final status codes are exposed; recognition text never passes through here.
 *
 * One download job owns the slot at a time.  A job id identifies its handles and callbacks;
 * the slot is released only after the worker has cleared every handle in `finally`, and
 * [delete] serializes with a new start.
 */
object HgramOcrModels {

    const val ROOT_DIR = "hgram_tessdata"
    const val MODELS_DIR = "tessdata"

    const val LANG_RU = "rus"
    const val LANG_EN = "eng"

    private const val PINNED_COMMIT = "87416418657359cb625c412a48b6e1d6d41c29bd"
    private const val URL_BASE =
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$PINNED_COMMIT"

    const val URL_RU = "$URL_BASE/rus.traineddata"
    const val SIZE_RU = 3_861_738L
    const val SHA256_RU = "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"

    const val URL_EN = "$URL_BASE/eng.traineddata"
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
    private const val DELETE_JOIN_TIMEOUT_MS = 60_000L
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")

    /** Test seam: lets a unit test serve bytes without opening a socket. */
    internal fun interface ConnectionFactory {
        fun open(url: String): HttpURLConnection
    }

    private val lock = Any()

    private var nextJobId = 0L
    private var activeJobId = 0L
    private var activeConnection: HttpURLConnection? = null
    private var activeThread: Thread? = null
    private var currentProgress = -1
    private var lastPostedProgress = -1
    private var lastError = ERROR_NONE
    private var deleting = false
    private var deleteThread: Thread? = null
    private val cancelledJobs = HashSet<Long>()

    private var readyCachePath: String? = null
    private var readyCacheValue = false
    private var revision = 0L
    private var verifyThread: Thread? = null
    private val verifyCallbacks = ArrayList<((Boolean) -> Unit)?>()

    /** Test seam: replaces the UI-thread post so plain JVM tests can run callbacks inline. */
    internal var postRunner: (Runnable) -> Unit = { runnable -> AndroidUtilities.runOnUIThread(runnable) }

    /** Test seam: runs on the worker before the final state cleanup is committed. */
    internal var beforeJobCleanup: ((Long) -> Unit)? = null

    @Volatile
    internal var connectionFactory: ConnectionFactory = ConnectionFactory { url ->
        URL(url).openConnection() as HttpURLConnection
    }

    /** -1 when idle, 0..100 while a download is running. */
    @JvmStatic
    fun progress(): Int = synchronized(lock) { currentProgress }

    @JvmStatic
    fun activeJobId(): Long = synchronized(lock) { activeJobId }

    @JvmStatic
    fun lastError(): Int = synchronized(lock) { lastError }

    @JvmStatic
    fun isJobActive(jobId: Long): Boolean = synchronized(lock) {
        jobId != 0L && activeJobId == jobId
    }

    /**
     * Cached readiness only: no file system access and no SHA-256.  The cache is primed by a
     * successful download or by [ensureReady]/[verifyReady] and invalidated by download/delete.
     */
    @JvmStatic
    fun isReady(context: Context?): Boolean {
        val filesDir = context?.filesDir ?: return false
        val root = File(filesDir, ROOT_DIR)
        synchronized(lock) {
            return readyCacheValue && readyCachePath == root.absolutePath
        }
    }

    /**
     * Verified readiness on a background thread, single-flight.  The callback is delivered on
     * the UI thread and never runs while the internal lock is held.
     */
    @JvmStatic
    fun verifyReady(context: Context?, callback: ((Boolean) -> Unit)?) {
        val filesDir = context?.filesDir
        if (filesDir == null) {
            post { callback?.invoke(false) }
            return
        }
        val root = File(filesDir, ROOT_DIR)
        var thread: Thread? = null
        synchronized(lock) {
            verifyCallbacks.add(callback)
            if (verifyThread == null) {
                val startedRevision = revision
                thread = Thread({
                    runVerify(root, startedRevision)
                }, "hgram-tessdata-verify").also {
                    it.isDaemon = true
                    verifyThread = it
                }
            }
        }
        thread?.start()
    }

    /**
     * Synchronous verified readiness for the OCR worker.  Hashes the installed files and primes
     * the cache; callers must never invoke it on the UI thread.
     */
    @JvmStatic
    fun ensureReady(context: Context?): Boolean {
        val filesDir = context?.filesDir ?: return false
        val root = File(filesDir, ROOT_DIR)
        val startedRevision = synchronized(lock) { revision }
        val ready = try {
            isReady(root, MODELS)
        } catch (e: Throwable) {
            false
        }
        synchronized(lock) {
            if (revision == startedRevision) {
                readyCachePath = root.absolutePath
                readyCacheValue = ready
            }
        }
        return ready
    }

    /** Pure readiness check: every spec must exist with the exact size and SHA-256. */
    @JvmStatic
    fun isReady(root: File?, specs: List<ModelSpec>?): Boolean {
        return isReadyInternal(root, specs, 0L)
    }

    /**
     * Downloads both files on one background thread.  Returns the owning job id (>0), or 0 when
     * another job owns the slot or a delete is in progress.  Callbacks receive the same job id
     * and arrive on the UI thread.
     */
    @JvmStatic
    fun download(
        context: Context,
        onProgress: ((Long, Int) -> Unit)?,
        onDone: ((Long, Boolean) -> Unit)?,
    ): Long {
        return download(File(context.filesDir, ROOT_DIR), MODELS, onProgress, onDone)
    }

    /** Compatibility overload: old callers receive values without a job id. */
    @JvmStatic
    fun download(
        context: Context,
        onProgress: ((Int) -> Unit)?,
        onDone: ((Boolean) -> Unit)?,
    ) {
        val progressCallback: ((Long, Int) -> Unit)? =
            onProgress?.let { callback -> { _: Long, value: Int -> callback(value) } }
        val doneCallback: ((Long, Boolean) -> Unit)? =
            onDone?.let { callback -> { _: Long, ok: Boolean -> callback(ok) } }
        download(context, progressCallback, doneCallback)
    }

    internal fun download(
        root: File,
        specs: List<ModelSpec>,
        onProgress: ((Long, Int) -> Unit)?,
        onDone: ((Long, Boolean) -> Unit)?,
    ): Long {
        var jobId = 0L
        var thread: Thread? = null
        synchronized(lock) {
            if (deleting || activeJobId != 0L) {
                jobId = 0L
            } else {
                jobId = allocateJobIdLocked()
                activeJobId = jobId
                currentProgress = 0
                lastPostedProgress = -1
                lastError = ERROR_NONE
                cancelledJobs.clear()
                invalidateReadyCacheLocked()
                val created = Thread({
                    runJob(jobId, root, specs, onProgress, onDone)
                }, "hgram-tessdata-download")
                created.isDaemon = true
                thread = created
                activeThread = created
            }
        }
        if (jobId == 0L) {
            postDone(0L, onDone, false)
            return 0L
        }
        try {
            thread?.start()
        } catch (throwable: Throwable) {
            synchronized(lock) {
                if (activeJobId == jobId) {
                    activeJobId = 0L
                    activeThread = null
                    activeConnection = null
                    currentProgress = -1
                    cancelledJobs.remove(jobId)
                }
            }
            postDone(jobId, onDone, false)
            return 0L
        }
        return jobId
    }

    /** Stops the given job; ids that do not own the slot are ignored. */
    @JvmStatic
    fun cancel(jobId: Long) {
        if (jobId <= 0L) {
            return
        }
        var connection: HttpURLConnection? = null
        var thread: Thread? = null
        synchronized(lock) {
            if (activeJobId != jobId) {
                return
            }
            cancelledJobs.add(jobId)
            connection = activeConnection
            thread = activeThread
        }
        try {
            connection?.disconnect()
        } catch (ignored: Throwable) {
        }
        thread?.interrupt()
    }

    /** Stops the currently active download; `.part` files stay on disk for the next resume. */
    @JvmStatic
    fun cancel() {
        cancel(activeJobId())
    }

    /**
     * Deletes the downloaded models recursively.  A running job is cancelled first and its
     * teardown is awaited in the background; new starts are refused until the delete ends.
     */
    @JvmStatic
    fun delete(context: Context, onDone: ((Boolean) -> Unit)?) {
        delete(File(context.filesDir, ROOT_DIR), onDone)
    }

    /** Compatibility overload. */
    @JvmStatic
    fun delete(context: Context) {
        delete(context, null)
    }

    internal fun delete(root: File, onDone: ((Boolean) -> Unit)?) {
        var reject = false
        var connection: HttpURLConnection? = null
        var worker: Thread? = null
        synchronized(lock) {
            if (deleting) {
                reject = true
            } else {
                deleting = true
                invalidateReadyCacheLocked()
                currentProgress = -1
                lastPostedProgress = -1
                val jobToCancel = activeJobId
                if (jobToCancel != 0L) {
                    cancelledJobs.add(jobToCancel)
                }
                connection = activeConnection
                worker = activeThread
            }
        }
        if (reject) {
            postDeleteDone(onDone, false)
            return
        }
        val cancelledConnection = connection
        val cancelledWorker = worker
        try {
            cancelledConnection?.disconnect()
        } catch (ignored: Throwable) {
        }
        cancelledWorker?.interrupt()
        val thread = Thread({
            var success = false
            try {
                if (cancelledWorker != null && cancelledWorker !== Thread.currentThread()) {
                    cancelledWorker.join(DELETE_JOIN_TIMEOUT_MS)
                    if (cancelledWorker.isAlive) {
                        // Never race a live writer with recursive removal.
                        success = false
                    } else {
                        success = deleteModels(root)
                    }
                } else {
                    success = deleteModels(root)
                }
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(lock) {
                    deleting = false
                    deleteThread = null
                    revision++
                    readyCachePath = null
                    readyCacheValue = false
                    if (!success) {
                        if (lastError == ERROR_NONE) {
                            lastError = ERROR_IO
                        }
                    } else {
                        lastError = ERROR_NONE
                    }
                }
                postDeleteDone(onDone, success)
            }
        }, "hgram-tessdata-delete")
        thread.isDaemon = true
        synchronized(lock) {
            deleteThread = thread
        }
        thread.start()
    }

    /** True when an unfinished `.part`/`.attempt`/`.merged` exists for any model. */
    @JvmStatic
    fun hasPartialFiles(context: Context?): Boolean {
        val filesDir = context?.filesDir ?: return false
        val models = File(File(filesDir, ROOT_DIR), MODELS_DIR)
        for (spec in MODELS) {
            for (suffix in PART_SUFFIXES) {
                if (File(models, spec.fileName + suffix).isFile) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Downloads one spec with a validated Range resume, verifies SHA-256 and installs the file.
     * The response body is always written to a separate `.attempt`; the retained `.part` is
     * merged only after the 206 range and body length are proven consistent.
     */
    internal fun downloadFileTo(
        spec: ModelSpec,
        target: File,
        part: File,
        progressBase: Long,
        total: Long,
        onProgress: ((Long, Int) -> Unit)?,
    ): Boolean {
        return downloadFileTo(spec, target, part, progressBase, total, onProgress, 0L)
    }

    internal fun downloadFileTo(
        spec: ModelSpec,
        target: File,
        part: File,
        progressBase: Long,
        total: Long,
        onProgress: ((Long, Int) -> Unit)?,
        jobId: Long,
    ): Boolean {
        checkJob(jobId)
        if (target.isFile && target.length() == spec.size && verifyDigest(target, spec.sha256, jobId)) {
            publishProgress(jobId, percent(progressBase + spec.size, total), onProgress)
            return true
        }
        if (target.exists() && !target.delete()) {
            throw fsFailure(jobId, "Cannot replace ${target.name}")
        }
        var offset = if (part.isFile) part.length() else 0L
        if (offset > spec.size) {
            deleteOrFail(jobId, part)
            offset = 0L
        }
        val attempt = File(part.parentFile, spec.fileName + ".attempt")
        val merged = File(part.parentFile, spec.fileName + ".merged")
        deleteQuietly(attempt)
        deleteQuietly(merged)
        if (offset == spec.size) {
            if (!verifyDigest(part, spec.sha256, jobId)) {
                deleteQuietly(part)
                setJobError(jobId, ERROR_HASH)
                throw IOException("Checksum mismatch")
            }
            checkJob(jobId)
            install(part, target, spec, jobId)
            publishProgress(jobId, percent(progressBase + spec.size, total), onProgress)
            return true
        }

        var restartedFor416 = false
        var attemptBase = -1L
        var attemptExpected = 0L
        while (true) {
            checkJob(jobId)
            attemptBase = -1L
            attemptExpected = 0L
            val connection = openConnection(jobId, spec.url, offset)
            try {
                val response = connection.responseCode
                if (offset > 0 && response == HTTP_RANGE_NOT_SATISFIABLE) {
                    if (restartedFor416) {
                        throw protocolFailure(jobId, "HTTP 416 after restart")
                    }
                    restartedFor416 = true
                    deleteOrFail(jobId, part)
                    offset = 0L
                    continue
                }
                if (offset > 0) {
                    when (response) {
                        HttpURLConnection.HTTP_PARTIAL -> {
                            val range = parseContentRange(connection.getHeaderField("Content-Range"))
                                ?: throw protocolFailure(jobId, "Missing Content-Range")
                            if (range[0] != offset ||
                                range[1] < range[0] ||
                                range[2] != spec.size ||
                                range[1] >= range[2]
                            ) {
                                throw protocolFailure(jobId, "Invalid Content-Range")
                            }
                            val expected = range[1] - range[0] + 1
                            validateContentLength(jobId, connection, expected)
                            attemptBase = offset
                            attemptExpected = expected
                            readBody(jobId, connection, attempt, expected)
                            mergeAndInstall(
                                spec, target, part, attempt, merged, offset, expected,
                                progressBase, total, onProgress, jobId,
                            )
                            return true
                        }
                        HttpURLConnection.HTTP_OK -> {
                            val declared = declaredContentLength(jobId, connection)
                            if (declared != null && declared != spec.size) {
                                throw protocolFailure(jobId, "Invalid full Content-Length")
                            }
                            attemptBase = 0L
                            attemptExpected = spec.size
                            readBody(jobId, connection, attempt, spec.size)
                            installAttempt(spec, target, part, attempt, progressBase, total, onProgress, jobId)
                            return true
                        }
                        else -> throw protocolFailure(jobId, "HTTP $response")
                    }
                } else {
                    when (response) {
                        HttpURLConnection.HTTP_OK -> {
                            val declared = declaredContentLength(jobId, connection)
                            if (declared != null && declared != spec.size) {
                                throw protocolFailure(jobId, "Invalid full Content-Length")
                            }
                            attemptBase = 0L
                            attemptExpected = spec.size
                            readBody(jobId, connection, attempt, spec.size)
                            installAttempt(spec, target, part, attempt, progressBase, total, onProgress, jobId)
                            return true
                        }
                        HttpURLConnection.HTTP_PARTIAL -> {
                            val range = parseContentRange(connection.getHeaderField("Content-Range"))
                                ?: throw protocolFailure(jobId, "Missing Content-Range")
                            if (range[0] != 0L ||
                                range[2] != spec.size ||
                                range[1] != spec.size - 1
                            ) {
                                throw protocolFailure(jobId, "Invalid Content-Range")
                            }
                            validateContentLength(jobId, connection, spec.size)
                            attemptBase = 0L
                            attemptExpected = spec.size
                            readBody(jobId, connection, attempt, spec.size)
                            installAttempt(spec, target, part, attempt, progressBase, total, onProgress, jobId)
                            return true
                        }
                        else -> throw protocolFailure(jobId, "HTTP $response")
                    }
                }
            } catch (throwable: Throwable) {
                if (!preserveResumable(spec, part, attempt, merged, attemptBase,
                        attemptExpected, throwable)
                ) {
                    deleteQuietly(attempt)
                    deleteQuietly(merged)
                }
                throw throwable
            } finally {
                try {
                    connection.disconnect()
                } catch (ignored: Throwable) {
                }
                clearActiveConnection(jobId, connection)
            }
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            currentProgress = -1
            lastPostedProgress = -1
            lastError = ERROR_NONE
            activeJobId = 0L
            activeConnection = null
            activeThread = null
            cancelledJobs.clear()
            deleting = false
            deleteThread = null
            readyCachePath = null
            readyCacheValue = false
            revision = 0L
            verifyThread = null
            verifyCallbacks.clear()
            postRunner = { runnable -> AndroidUtilities.runOnUIThread(runnable) }
            beforeJobCleanup = null
        }
    }

    internal fun downloadThreadForTests(): Thread? = synchronized(lock) { activeThread }

    internal fun activeConnectionForTests(): HttpURLConnection? = synchronized(lock) {
        activeConnection
    }

    internal fun deleteThreadForTests(): Thread? = synchronized(lock) { deleteThread }

    internal fun isDeletingForTests(): Boolean = synchronized(lock) { deleting }

    private fun runJob(
        jobId: Long,
        root: File,
        specs: List<ModelSpec>,
        onProgress: ((Long, Int) -> Unit)?,
        onDone: ((Long, Boolean) -> Unit)?,
    ) {
        var success = false
        var cancelledNow = false
        try {
            checkJob(jobId)
            val models = File(root, MODELS_DIR)
            if (!models.isDirectory && !models.mkdirs() && !models.isDirectory) {
                throw fsFailure(jobId, "Cannot create models directory")
            }
            if (isReadyInternal(root, specs, jobId)) {
                success = true
                publishProgress(jobId, 100, onProgress)
                return
            }
            val total = specs.sumOf { it.size }
            val missing = specs.sumOf { spec ->
                val target = File(models, spec.fileName)
                if (target.isFile && target.length() == spec.size) 0L else spec.size
            }
            val usable = root.usableSpace
            if (usable > 0 && usable < missing + EXTRA_FREE_SPACE) {
                setJobError(jobId, ERROR_NO_SPACE)
                return
            }
            var base = 0L
            for (spec in specs) {
                checkJob(jobId)
                val target = File(models, spec.fileName)
                val part = File(models, spec.fileName + ".part")
                if (target.isFile && target.length() == spec.size &&
                    verifyDigest(target, spec.sha256, jobId)
                ) {
                    publishProgress(jobId, percent(base + spec.size, total), onProgress)
                } else {
                    downloadFileTo(spec, target, part, base, total, onProgress, jobId)
                }
                base += spec.size
            }
            checkJob(jobId)
            success = isReadyInternal(root, specs, jobId)
            if (!success) {
                throw protocolFailure(jobId, "Models are incomplete")
            }
        } catch (e: CancellationException) {
            cancelledNow = true
        } catch (e: Throwable) {
            cancelledNow = isCancelled(jobId)
        } finally {
            beforeJobCleanup?.invoke(jobId)
            synchronized(lock) {
                if (cancelledJobs.contains(jobId)) {
                    cancelledNow = true
                    success = false
                }
                if (activeJobId == jobId) {
                    currentProgress = -1
                    activeConnection = null
                    activeThread = null
                    activeJobId = 0L
                }
                cancelledJobs.remove(jobId)
                revision++
                if (success) {
                    readyCachePath = root.absolutePath
                    readyCacheValue = true
                } else {
                    readyCachePath = null
                    readyCacheValue = false
                    if (cancelledNow) {
                        lastError = ERROR_CANCELLED
                    } else if (lastError == ERROR_NONE) {
                        lastError = ERROR_NETWORK
                    }
                }
            }
            postDone(jobId, onDone, success)
        }
    }

    private fun runVerify(root: File, startedRevision: Long) {
        val ready = try {
            isReadyInternal(root, MODELS, 0L)
        } catch (e: Throwable) {
            false
        }
        var callbacks: List<((Boolean) -> Unit)?> = emptyList()
        var stillValid = false
        synchronized(lock) {
            if (revision == startedRevision) {
                stillValid = true
                readyCachePath = root.absolutePath
                readyCacheValue = ready
            }
            callbacks = ArrayList(verifyCallbacks)
            verifyCallbacks.clear()
            verifyThread = null
        }
        val result = ready && stillValid
        for (callback in callbacks) {
            callback?.let { post { it(result) } }
        }
    }

    private fun isReadyInternal(root: File?, specs: List<ModelSpec>?, jobId: Long): Boolean {
        if (root == null || specs == null || specs.isEmpty()) {
            return false
        }
        val models = File(root, MODELS_DIR)
        for (spec in specs) {
            checkJob(jobId)
            val file = File(models, spec.fileName)
            if (!file.isFile || file.length() != spec.size) {
                return false
            }
            if (!verifyDigest(file, spec.sha256, jobId)) {
                return false
            }
        }
        return true
    }

    private fun readBody(
        jobId: Long,
        connection: HttpURLConnection,
        attempt: File,
        expected: Long,
    ) {
        val output = try {
            FileOutputStream(attempt, false)
        } catch (e: IOException) {
            throw fsFailure(jobId, "Cannot open ${attempt.name}")
        }
        val input = try {
            connection.inputStream
        } catch (e: IOException) {
            try {
                output.close()
            } catch (ignored: Throwable) {
            }
            throw TransportFailure("Cannot open body", e)
        }
        try {
            input.use { source ->
                output.use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    while (written < expected) {
                        checkJob(jobId)
                        val want = minOf(buffer.size.toLong(), expected - written).toInt()
                        val read = try {
                            source.read(buffer, 0, want)
                        } catch (e: IOException) {
                            throw TransportFailure("Cannot read body", e)
                        }
                        if (read < 0) {
                            throw TransportFailure("Truncated body", null)
                        }
                        try {
                            sink.write(buffer, 0, read)
                        } catch (e: IOException) {
                            throw fsFailure(jobId, "Cannot write ${attempt.name}")
                        }
                        written += read
                    }
                    try {
                        sink.flush()
                        sink.fd.sync()
                    } catch (e: IOException) {
                        throw fsFailure(jobId, "Cannot flush ${attempt.name}")
                    }
                    checkJob(jobId)
                    if (source.read() != -1) {
                        throw protocolFailure(jobId, "Overlong body")
                    }
                }
            }
        } finally {
            try {
                input.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * Keeps genuinely received contiguous bytes after cancel/transport failure so the next
     * request can resume with a smaller Range.  Protocol/range/hash failures are not retained.
     */
    private fun preserveResumable(
        spec: ModelSpec,
        part: File,
        attempt: File,
        merged: File,
        baseOffset: Long,
        expected: Long,
        throwable: Throwable,
    ): Boolean {
        // A validated response is required: an invalid/overlong transfer must never advance .part.
        if (throwable !is CancellationException && throwable !is TransportFailure) {
            return false
        }
        if (!attempt.isFile || baseOffset < 0L || expected <= 0L) {
            return false
        }
        val attemptLength = attempt.length()
        // Exactly `expected` bytes are only accepted after readBody's overlong check; a cancel
        // that lands in that window must not persist the rejected tail.
        if (attemptLength <= 0L || attemptLength >= expected ||
            baseOffset + attemptLength > spec.size
        ) {
            return false
        }
        return try {
            if (baseOffset == 0L) {
                if (part.exists() && !part.delete()) {
                    return false
                }
                if (attempt.renameTo(part)) {
                    return true
                }
                val copied = copyFile(attempt, part) && part.length() == attemptLength
                if (copied) {
                    deleteQuietly(attempt)
                }
                copied
            } else {
                if (!part.isFile || part.length() != baseOffset) {
                    return false
                }
                deleteQuietly(merged)
                val output = FileOutputStream(merged, false)
                try {
                    if (!copyPrefix(part, output, baseOffset) ||
                        !copyPrefix(attempt, output, attemptLength)
                    ) {
                        return false
                    }
                    output.flush()
                    output.fd.sync()
                } finally {
                    try {
                        output.close()
                    } catch (ignored: Throwable) {
                    }
                }
                if (merged.length() != baseOffset + attemptLength) {
                    return false
                }
                if (!part.delete()) {
                    return false
                }
                if (!merged.renameTo(part)) {
                    val copied = copyFile(merged, part) &&
                        part.length() == baseOffset + attemptLength
                    if (!copied) {
                        return false
                    }
                }
                deleteQuietly(attempt)
                true
            }
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun copyFile(source: File, target: File): Boolean {
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun copyPrefix(source: File, output: FileOutputStream, expected: Long): Boolean {
        return try {
            FileInputStream(source).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (copied < expected) {
                    val want = minOf(buffer.size.toLong(), expected - copied).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) {
                        return false
                    }
                    output.write(buffer, 0, read)
                    copied += read
                }
            }
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun mergeAndInstall(
        spec: ModelSpec,
        target: File,
        part: File,
        attempt: File,
        merged: File,
        offset: Long,
        expected: Long,
        progressBase: Long,
        total: Long,
        onProgress: ((Long, Int) -> Unit)?,
        jobId: Long,
    ) {
        if (!part.isFile || part.length() != offset) {
            throw fsFailure(jobId, "Retained prefix changed")
        }
        if (!attempt.isFile || attempt.length() != expected) {
            throw protocolFailure(jobId, "Invalid attempt length")
        }
        deleteQuietly(merged)
        val output = try {
            FileOutputStream(merged, false)
        } catch (e: IOException) {
            throw fsFailure(jobId, "Cannot open ${merged.name}")
        }
        try {
            try {
                copyExactly(jobId, part, output, offset)
                copyExactly(jobId, attempt, output, expected)
                output.flush()
                output.fd.sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                setJobError(jobId, ERROR_IO)
                throw e
            } catch (e: Throwable) {
                throw fsFailure(jobId, "Cannot merge model")
            }
        } finally {
            try {
                output.close()
            } catch (ignored: Throwable) {
            }
        }
        if (merged.length() != spec.size) {
            throw protocolFailure(jobId, "Incomplete range")
        }
        if (!verifyDigest(merged, spec.sha256, jobId)) {
            deleteQuietly(part)
            deleteQuietly(attempt)
            deleteQuietly(merged)
            setJobError(jobId, ERROR_HASH)
            throw IOException("Checksum mismatch")
        }
        checkJob(jobId)
        install(merged, target, spec, jobId)
        deleteQuietly(part)
        deleteQuietly(attempt)
        publishProgress(jobId, percent(progressBase + spec.size, total), onProgress)
    }

    private fun installAttempt(
        spec: ModelSpec,
        target: File,
        part: File,
        attempt: File,
        progressBase: Long,
        total: Long,
        onProgress: ((Long, Int) -> Unit)?,
        jobId: Long,
    ) {
        if (!attempt.isFile || attempt.length() != spec.size) {
            throw protocolFailure(jobId, "Incomplete full response")
        }
        if (!verifyDigest(attempt, spec.sha256, jobId)) {
            deleteQuietly(attempt)
            deleteQuietly(part)
            setJobError(jobId, ERROR_HASH)
            throw IOException("Checksum mismatch")
        }
        checkJob(jobId)
        install(attempt, target, spec, jobId)
        deleteQuietly(part)
        publishProgress(jobId, percent(progressBase + spec.size, total), onProgress)
    }

    private fun copyExactly(jobId: Long, source: File, output: FileOutputStream, expected: Long) {
        val input = try {
            FileInputStream(source)
        } catch (e: IOException) {
            throw fsFailure(jobId, "Cannot read ${source.name}")
        }
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            var copied = 0L
            while (copied < expected) {
                checkJob(jobId)
                val want = minOf(buffer.size.toLong(), expected - copied).toInt()
                val read = input.read(buffer, 0, want)
                if (read < 0) {
                    throw fsFailure(jobId, "Short ${source.name}")
                }
                try {
                    output.write(buffer, 0, read)
                } catch (e: IOException) {
                    throw fsFailure(jobId, "Cannot write merged model")
                }
                copied += read
            }
        } finally {
            try {
                input.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun openConnection(jobId: Long, url: String, offset: Long): HttpURLConnection {
        checkJob(jobId)
        val connection = connectionFactory.open(url)
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) {
            connection.setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            synchronized(lock) {
                if (jobId != 0L) {
                    if (activeJobId != jobId || cancelledJobs.contains(jobId)) {
                        throw CancellationException()
                    }
                }
                activeConnection = connection
            }
        } catch (throwable: Throwable) {
            try {
                connection.disconnect()
            } catch (ignored: Throwable) {
            }
            throw throwable
        }
        return connection
    }

    private fun clearActiveConnection(jobId: Long, connection: HttpURLConnection) {
        synchronized(lock) {
            if (jobId == 0L || activeJobId == jobId) {
                if (activeConnection === connection) {
                    activeConnection = null
                }
            }
        }
    }

    private fun install(source: File, target: File, spec: ModelSpec, jobId: Long) {
        if (target.exists() && !target.delete()) {
            throw fsFailure(jobId, "Cannot replace ${target.name}")
        }
        if (!source.renameTo(target)) {
            throw fsFailure(jobId, "Cannot install ${target.name}")
        }
        if (!target.isFile || target.length() != spec.size) {
            throw fsFailure(jobId, "Installed ${target.name} has wrong size")
        }
    }

    private fun deleteModels(root: File): Boolean {
        val models = File(root, MODELS_DIR)
        return try {
            if (!models.exists()) {
                true
            } else {
                models.deleteRecursively() && !models.exists()
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun parseContentRange(value: String?): LongArray? {
        if (value == null) {
            return null
        }
        val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
        return try {
            longArrayOf(
                match.groupValues[1].toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun declaredContentLength(jobId: Long, connection: HttpURLConnection): Long? {
        val raw = connection.getHeaderField("Content-Length") ?: return null
        return try {
            raw.trim().toLong()
        } catch (e: NumberFormatException) {
            throw protocolFailure(jobId, "Invalid Content-Length")
        }
    }

    private fun validateContentLength(jobId: Long, connection: HttpURLConnection, expected: Long) {
        val declared = declaredContentLength(jobId, connection) ?: return
        if (declared != expected) {
            throw protocolFailure(jobId, "Content-Length mismatch")
        }
    }

    private fun publishProgress(
        jobId: Long,
        percent: Int,
        onProgress: ((Long, Int) -> Unit)?,
    ) {
        val value = percent.coerceIn(0, 100)
        var notify = false
        synchronized(lock) {
            if (jobId == 0L || activeJobId == jobId) {
                currentProgress = value
                if (lastPostedProgress != value) {
                    lastPostedProgress = value
                    notify = onProgress != null
                }
            }
        }
        if (notify) {
            post { onProgress?.invoke(jobId, value) }
        }
    }

    private fun postDone(jobId: Long, onDone: ((Long, Boolean) -> Unit)?, success: Boolean) {
        if (onDone != null) {
            post { onDone(jobId, success) }
        }
    }

    private fun postDeleteDone(onDone: ((Boolean) -> Unit)?, success: Boolean) {
        if (onDone != null) {
            post { onDone(success) }
        }
    }

    private fun post(runnable: Runnable) {
        val runner = synchronized(lock) { postRunner }
        runner(runnable)
    }

    private fun percent(done: Long, total: Long): Int {
        if (total <= 0) {
            return 0
        }
        return (done.coerceAtLeast(0) * 100 / total).toInt().coerceIn(0, 100)
    }

    private fun verifyDigest(file: File, expected: String, jobId: Long): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkJob(jobId)
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

    private fun allocateJobIdLocked(): Long {
        nextJobId++
        if (nextJobId <= 0L) {
            nextJobId = 1L
        }
        return nextJobId
    }

    private fun invalidateReadyCacheLocked() {
        readyCachePath = null
        readyCacheValue = false
        revision++
    }

    private fun isCancelled(jobId: Long): Boolean = synchronized(lock) {
        jobId != 0L && activeJobId == jobId && cancelledJobs.contains(jobId)
    }

    private fun checkJob(jobId: Long) {
        if (isCancelled(jobId)) {
            throw CancellationException()
        }
    }

    private fun setJobError(jobId: Long, code: Int) {
        synchronized(lock) {
            if (jobId == 0L || activeJobId == jobId) {
                if (lastError == ERROR_NONE) {
                    lastError = code
                }
            }
        }
    }

    /** A body transfer ended early or its socket failed; already received bytes remain valid. */
    private class TransportFailure(message: String, cause: Throwable?) :
        IOException(message, cause)

    private fun protocolFailure(jobId: Long, message: String): IOException {
        setJobError(jobId, ERROR_NETWORK)
        return IOException(message)
    }

    private fun fsFailure(jobId: Long, message: String): IOException {
        setJobError(jobId, ERROR_IO)
        return IOException(message)
    }

    private fun deleteOrFail(jobId: Long, file: File) {
        if (file.exists() && !file.delete()) {
            throw fsFailure(jobId, "Cannot delete ${file.name}")
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (ignored: Throwable) {
        }
    }

    private val PART_SUFFIXES = listOf(".part", ".attempt", ".merged")
}
