package xyz.nextalone.nagram.hgram

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HgramOcrModelsTest {

    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    @Before
    fun resetState() {
        HgramOcrModels.resetForTests()
    }

    @Test
    fun isReadyRequiresBothFilesWithExactSizeAndSha256() {
        val root = temporary.newFolder("ready-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5, 6, 7)
        val first = model("rus", firstBytes)
        val second = model("eng", secondBytes)
        write(File(models, first.fileName), firstBytes)
        write(File(models, second.fileName), secondBytes)
        assertTrue(HgramOcrModels.isReady(root, listOf(first, second)))

        write(File(models, second.fileName), byteArrayOf(9, 9, 9, 9))
        assertFalse(HgramOcrModels.isReady(root, listOf(first, second)))

        write(File(models, second.fileName), secondBytes)
        write(File(models, first.fileName), byteArrayOf(1, 2))
        assertFalse(HgramOcrModels.isReady(root, listOf(first, second)))

        write(File(models, first.fileName), firstBytes)
        assertTrue(File(models, second.fileName).delete())
        assertFalse(HgramOcrModels.isReady(root, listOf(first, second)))
        assertFalse(HgramOcrModels.isReady(null, listOf(first, second)))
    }

    @Test
    fun cancelKeepsPartAndClearsThreadAndConnection() {
        HgramOcrModels.postRunner = { it.run() }
        val content = ByteArray(200_000) { (it * 13 % 249).toByte() }
        val spec = model("rus", content)
        val root = temporary.newFolder("cancel-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val target = File(models, spec.fileName)
        val part = File(models, spec.fileName + ".part")
        val prefix = 30_000
        write(part, content.copyOfRange(0, prefix))
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = FakeConnection(
            spec.url,
            content,
            HttpURLConnection.HTTP_PARTIAL,
            headers = mapOf(
                "Content-Range" to "bytes $prefix-${spec.size - 1}/${spec.size}",
                "Content-Length" to (spec.size - prefix).toString(),
            ),
            reading = reading,
            release = release,
        )
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { connection }
        val done = CountDownLatch(1)
        var doneJob = -1L

        val jobId = HgramOcrModels.download(root, listOf(spec), null) { id, _ ->
            doneJob = id
            done.countDown()
        }
        assertTrue(jobId > 0)
        assertTrue("download must reach the blocking read", reading.await(5, TimeUnit.SECONDS))
        val thread = HgramOcrModels.downloadThreadForTests()
        assertNotNull(thread)
        assertEquals(jobId, HgramOcrModels.activeJobId())
        assertTrue(HgramOcrModels.isJobActive(jobId))

        HgramOcrModels.cancel(jobId)
        assertTrue(connection.disconnected)
        release.countDown()
        thread!!.join(5_000L)
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertFalse(thread.isAlive)
        assertEquals(jobId, doneJob)
        assertEquals(HgramOcrModels.ERROR_CANCELLED, HgramOcrModels.lastError())
        assertEquals(-1, HgramOcrModels.progress())
        assertNull(HgramOcrModels.activeConnectionForTests())
        assertNull(HgramOcrModels.downloadThreadForTests())
        assertTrue(part.isFile)
        assertEquals(prefix.toLong(), part.length())
        assertFalse(target.exists())
    }

    @Test
    fun freshDownloadCancellationRetainsPrefixForNextRange() {
        HgramOcrModels.postRunner = { it.run() }
        val content = ByteArray(200_000) { (it * 17 % 251).toByte() }
        val spec = model("rus", content)
        val root = temporary.newFolder("fresh-cancel-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val target = File(models, spec.fileName)
        val part = File(models, spec.fileName + ".part")
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val first = FakeConnection(
            spec.url,
            content,
            HttpURLConnection.HTTP_OK,
            headers = mapOf("Content-Length" to spec.size.toString()),
            pauseAfter = 50_000,
            paused = paused,
            resume = resume,
        )
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { first }
        val done = CountDownLatch(1)
        val jobId = HgramOcrModels.download(root, listOf(spec), null) { _, _ -> done.countDown() }
        assertTrue(jobId > 0)
        assertTrue("download must pause after a valid prefix", paused.await(5, TimeUnit.SECONDS))

        HgramOcrModels.cancel(jobId)
        resume.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("cancelled transfer must retain its prefix", part.isFile)
        assertEquals(50_000L, part.length())
        assertArrayEquals(content.copyOfRange(0, 50_000), part.readBytes())
        assertFalse(File(models, spec.fileName + ".attempt").exists())
        assertFalse(File(models, spec.fileName + ".merged").exists())
        assertFalse(target.exists())

        val offset = part.length().toInt()
        val second = FakeConnection(
            spec.url,
            content,
            HttpURLConnection.HTTP_PARTIAL,
            headers = mapOf(
                "Content-Range" to "bytes $offset-${spec.size - 1}/${spec.size}",
                "Content-Length" to (spec.size - offset).toString(),
            ),
        )
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { second }
        val done2 = CountDownLatch(1)
        val job2 = HgramOcrModels.download(root, listOf(spec), null) { _, _ -> done2.countDown() }
        assertTrue(job2 > 0)
        assertTrue(done2.await(5, TimeUnit.SECONDS))
        assertEquals("bytes=$offset-", second.requestProperties["Range"])
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun invalidContentRangeDoesNotCorruptRetainedPart() {
        val content = ByteArray(100_000) { (it * 31 % 251).toByte() }
        val spec = model("rus", content)
        val root = temporary.newFolder("range-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val target = File(models, spec.fileName)
        val part = File(models, spec.fileName + ".part")
        val attempt = File(models, spec.fileName + ".attempt")
        val merged = File(models, spec.fileName + ".merged")
        val prefix = 40_000
        val remainder = (spec.size - prefix).toInt()

        fun resetPrefix() {
            if (target.exists()) assertTrue(target.delete())
            write(part, content.copyOfRange(0, prefix))
        }

        fun expectFailure(connection: FakeConnection, expectedPartSize: Int = prefix) {
            HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { connection }
            try {
                HgramOcrModels.downloadFileTo(spec, target, part, 0, spec.size, null)
                fail("response must be rejected")
            } catch (expected: IOException) {
            }
            assertEquals("retained prefix must survive", expectedPartSize.toLong(), part.length())
            assertArrayEquals(
                content.copyOfRange(0, expectedPartSize),
                part.readBytes(),
            )
            assertFalse(attempt.exists())
            assertFalse(merged.exists())
            assertFalse(target.exists())
        }

        // Wrong range start.
        resetPrefix()
        expectFailure(
            FakeConnection(
                spec.url,
                content,
                HttpURLConnection.HTTP_PARTIAL,
                headers = mapOf(
                    "Content-Range" to "bytes ${prefix - 1}-${spec.size - 1}/${spec.size}",
                    "Content-Length" to (remainder + 1).toString(),
                ),
                forcedStart = prefix - 1,
            )
        )

        // Wrong total.
        resetPrefix()
        expectFailure(
            FakeConnection(
                spec.url,
                content,
                HttpURLConnection.HTTP_PARTIAL,
                headers = mapOf(
                    "Content-Range" to "bytes $prefix-${spec.size - 1}/${spec.size - 1}",
                    "Content-Length" to remainder.toString(),
                ),
            )
        )

        // Missing header.
        resetPrefix()
        expectFailure(
            FakeConnection(spec.url, content, HttpURLConnection.HTTP_PARTIAL)
        )

        // Short body.
        resetPrefix()
        expectFailure(
            FakeConnection(
                spec.url,
                content,
                HttpURLConnection.HTTP_PARTIAL,
                headers = mapOf(
                    "Content-Range" to "bytes $prefix-${spec.size - 1}/${spec.size}",
                    "Content-Length" to remainder.toString(),
                ),
                shortBody = remainder - 10_000,
            ),
            expectedPartSize = spec.size.toInt() - 10_000,
        )

        // Transport abort mid-body.
        resetPrefix()
        expectFailure(
            FakeConnection(
                spec.url,
                content,
                HttpURLConnection.HTTP_PARTIAL,
                headers = mapOf(
                    "Content-Range" to "bytes $prefix-${spec.size - 1}/${spec.size}",
                    "Content-Length" to remainder.toString(),
                ),
                abortAfter = 10_000,
            ),
            expectedPartSize = prefix + 10_000,
        )

        // Correct 206 merges the retained prefix and installs the file.
        resetPrefix()
        var connection = FakeConnection(
            spec.url,
            content,
            HttpURLConnection.HTTP_PARTIAL,
            headers = mapOf(
                "Content-Range" to "bytes $prefix-${spec.size - 1}/${spec.size}",
                "Content-Length" to remainder.toString(),
            ),
        )
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { connection }
        assertTrue(HgramOcrModels.downloadFileTo(spec, target, part, 0, spec.size, null))
        assertEquals("bytes=$prefix-", connection.requestProperties["Range"])
        assertEquals(prefix, connection.servedOffset)
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())

        // 200 with a wrong Content-Length must not consume the prefix.
        resetPrefix()
        expectFailure(
            FakeConnection(
                spec.url,
                content,
                HttpURLConnection.HTTP_OK,
                headers = mapOf("Content-Length" to (spec.size - 1).toString()),
            )
        )

        // 200 without Content-Length restarts from zero and replaces the prefix.
        resetPrefix()
        connection = FakeConnection(spec.url, content, HttpURLConnection.HTTP_OK)
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { connection }
        assertTrue(HgramOcrModels.downloadFileTo(spec, target, part, 0, spec.size, null))
        assertEquals(0, connection.servedOffset)
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())

        // 416 drops the stale part and retries exactly once from zero.
        resetPrefix()
        val first = FakeConnection(spec.url, content, 416)
        val second = FakeConnection(
            spec.url,
            content,
            HttpURLConnection.HTTP_OK,
            headers = mapOf("Content-Length" to spec.size.toString()),
        )
        val index = AtomicInteger()
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory {
            if (index.getAndIncrement() == 0) first else second
        }
        assertTrue(HgramOcrModels.downloadFileTo(spec, target, part, 0, spec.size, null))
        assertEquals(0, second.servedOffset)
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun hashMismatchDeletesPartAndDoesNotInstall() {
        val content = ByteArray(90_000) { (it * 7 % 247).toByte() }
        val spec = model("rus", content).copy(sha256 = sha256(byteArrayOf(0)))
        val root = temporary.newFolder("hash-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val target = File(models, spec.fileName)
        val part = File(models, spec.fileName + ".part")
        val prefix = 30_000
        write(part, content.copyOfRange(0, prefix))
        val connection = FakeConnection(
            spec.url,
            content,
            HttpURLConnection.HTTP_PARTIAL,
            headers = mapOf(
                "Content-Range" to "bytes $prefix-${spec.size - 1}/${spec.size}",
                "Content-Length" to (spec.size - prefix).toString(),
            ),
        )
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { connection }

        try {
            HgramOcrModels.downloadFileTo(spec, target, part, 0, spec.size, null)
            fail("hash mismatch must fail")
        } catch (expected: IOException) {
        }

        assertEquals(HgramOcrModels.ERROR_HASH, HgramOcrModels.lastError())
        assertFalse(part.exists())
        assertFalse(File(models, spec.fileName + ".attempt").exists())
        assertFalse(File(models, spec.fileName + ".merged").exists())
        assertFalse(target.exists())
    }

    @Test
    fun concurrentStartCancelDeleteCannotClearNewJobHandles() {
        HgramOcrModels.postRunner = { it.run() }
        val content = ByteArray(150_000) { (it * 19 % 251).toByte() }
        val spec = model("rus", content)
        val root = temporary.newFolder("ownership-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val part = File(models, spec.fileName + ".part")
        val target = File(models, spec.fileName)
        val prefix = 50_000
        write(part, content.copyOfRange(0, prefix))

        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seamEntered = CountDownLatch(1)
        val seamRelease = CountDownLatch(1)
        val done1 = CountDownLatch(1)
        val job1 = AtomicLong()
        HgramOcrModels.beforeJobCleanup = { id ->
            if (id == job1.get()) {
                seamEntered.countDown()
                while (true) {
                    try {
                        seamRelease.await()
                        break
                    } catch (ignored: InterruptedException) {
                        // Delete interrupts the worker; keep the teardown seam held.
                    }
                }
            }
        }
        val first = FakeConnection(
            spec.url,
            content,
            HttpURLConnection.HTTP_PARTIAL,
            headers = mapOf(
                "Content-Range" to "bytes $prefix-${spec.size - 1}/${spec.size}",
                "Content-Length" to (spec.size - prefix).toString(),
            ),
            reading = reading,
            release = release,
        )
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { first }

        job1.set(HgramOcrModels.download(root, listOf(spec), null) { _, _ -> done1.countDown() })
        assertTrue(job1.get() > 0)
        assertTrue(reading.await(5, TimeUnit.SECONDS))

        assertEquals("only one writer may own the slot", 0L, HgramOcrModels.download(root, listOf(spec), null, null))

        HgramOcrModels.cancel(job1.get())
        release.countDown()
        assertTrue("worker must reach cleanup", seamEntered.await(5, TimeUnit.SECONDS))
        assertEquals(job1.get(), HgramOcrModels.activeJobId())

        val deleteDone = CountDownLatch(1)
        var deleted = false
        HgramOcrModels.delete(root) {
            deleted = it
            deleteDone.countDown()
        }
        assertTrue(HgramOcrModels.isDeletingForTests())
        assertEquals("download is refused while deleting", 0L, HgramOcrModels.download(root, listOf(spec), null, null))

        seamRelease.countDown()
        assertTrue(deleteDone.await(5, TimeUnit.SECONDS))
        assertTrue("delete reports success", deleted)
        assertTrue("partials are removed", !part.exists())
        assertFalse(File(models, spec.fileName + ".attempt").exists())
        assertFalse(File(models, spec.fileName + ".merged").exists())
        assertEquals(-1, HgramOcrModels.progress())
        assertEquals(0L, HgramOcrModels.activeJobId())
        assertNull(HgramOcrModels.downloadThreadForTests())

        // A new job gets its own handles; the old job id cannot cancel it.
        val reading2 = CountDownLatch(1)
        val release2 = CountDownLatch(1)
        val done2 = CountDownLatch(1)
        val second = FakeConnection(
            spec.url,
            content,
            HttpURLConnection.HTTP_OK,
            headers = mapOf("Content-Length" to spec.size.toString()),
            reading = reading2,
            release = release2,
        )
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { second }
        val job2 = HgramOcrModels.download(root, listOf(spec), null) { _, _ -> done2.countDown() }
        assertTrue(job2 > 0)
        assertTrue(reading2.await(5, TimeUnit.SECONDS))
        assertEquals(job2, HgramOcrModels.activeJobId())
        assertTrue(HgramOcrModels.isJobActive(job2))
        assertNotNull(HgramOcrModels.downloadThreadForTests())

        HgramOcrModels.cancel(job1.get())
        assertEquals("old cancel must not touch the new job", job2, HgramOcrModels.activeJobId())
        assertTrue(HgramOcrModels.isJobActive(job2))

        HgramOcrModels.cancel(job2)
        release2.countDown()
        assertTrue(done2.await(5, TimeUnit.SECONDS))
        assertEquals(0L, HgramOcrModels.activeJobId())
        assertNull(HgramOcrModels.downloadThreadForTests())
    }

    @Test
    fun deleteRemovesPartialDownload() {
        HgramOcrModels.postRunner = { it.run() }
        val root = temporary.newFolder("partial-delete-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val spec = HgramOcrModels.MODELS[0]
        write(File(models, spec.fileName + ".part"), byteArrayOf(1, 2, 3))
        write(File(models, spec.fileName + ".attempt"), byteArrayOf(4, 5))
        write(File(models, spec.fileName + ".merged"), byteArrayOf(6))

        val done = CountDownLatch(1)
        var deleted = false
        HgramOcrModels.delete(root) {
            deleted = it
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(deleted)
        assertFalse(models.exists())
        assertEquals(-1, HgramOcrModels.progress())
        assertFalse(HgramOcrModels.isDeletingForTests())
        assertNull(HgramOcrModels.deleteThreadForTests())
    }

    private fun model(name: String, bytes: ByteArray): HgramOcrModels.ModelSpec {
        return HgramOcrModels.ModelSpec(
            name,
            "$name.traineddata",
            "https://example.invalid/$name",
            bytes.size.toLong(),
            sha256(bytes),
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun write(file: File, data: ByteArray) {
        FileOutputStream(file).use { it.write(data) }
    }

    /** Minimal HttpURLConnection that serves [body] with the configured protocol surface. */
    private class FakeConnection(
        url: String,
        private val body: ByteArray,
        private val code: Int,
        private val headers: Map<String, String> = emptyMap(),
        private val forcedStart: Int? = null,
        private val shortBody: Int? = null,
        private val abortAfter: Int? = null,
        private val reading: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
        private val pauseAfter: Int? = null,
        private val paused: CountDownLatch? = null,
        private val resume: CountDownLatch? = null,
    ) : HttpURLConnection(URL(url)) {

        val requestProperties = LinkedHashMap<String, String>()
        var servedOffset = -1
        var disconnected = false

        override fun connect() {
        }

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getHeaderField(name: String): String? = headers[name]

        override fun setRequestProperty(key: String, value: String) {
            requestProperties[key] = value
        }

        override fun getRequestProperty(key: String): String? = requestProperties[key]

        override fun getInputStream(): InputStream {
            val requested = requestProperties["Range"]
                ?.removePrefix("bytes=")
                ?.removeSuffix("-")
                ?.toIntOrNull() ?: 0
            // A 200 response means the server ignored Range and starts from the beginning.
            val offset = forcedStart ?: if (code == HttpURLConnection.HTTP_OK) 0 else requested
            servedOffset = offset
            val available = (body.size - offset).coerceAtLeast(0)
            val limit = minOf(available, shortBody ?: available)
            val source = ByteArrayInputStream(body, offset, limit)
            val abort = abortAfter
            val limited: InputStream = if (abort == null) {
                source
            } else {
                object : InputStream() {
                    private var count = 0

                    override fun read(): Int {
                        if (count >= abort) {
                            throw IOException("connection aborted")
                        }
                        val value = source.read()
                        if (value >= 0) {
                            count++
                        }
                        return value
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (count >= abort) {
                            throw IOException("connection aborted")
                        }
                        val allowed = minOf(len, abort - count)
                        val read = source.read(b, off, allowed)
                        if (read > 0) {
                            count += read
                        }
                        return read
                    }
                }
            }
            val pauseAt = pauseAfter
            val pauseLatch = paused
            val resumeLatch = resume
            val paced: InputStream = if (pauseAt == null || pauseLatch == null || resumeLatch == null) {
                limited
            } else {
                object : InputStream() {
                    private var count = 0
                    private var isPaused = false

                    override fun read(): Int = read(ByteArray(1), 0, 1)

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!isPaused && count >= pauseAt) {
                            isPaused = true
                            pauseLatch.countDown()
                            try {
                                resumeLatch.await()
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return -1
                            }
                        }
                        val allowed = if (count < pauseAt) minOf(len, pauseAt - count) else len
                        val read = limited.read(b, off, allowed)
                        if (read > 0) {
                            count += read
                        }
                        return read
                    }
                }
            }
            val waitForRead = reading
            val releaseRead = release
            if (waitForRead == null || releaseRead == null) {
                return paced
            }
            return object : InputStream() {
                private var blocked = false

                override fun read(): Int = paced.read()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!blocked) {
                        blocked = true
                        waitForRead.countDown()
                        try {
                            releaseRead.await()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return -1
                        }
                    }
                    return paced.read(b, off, len)
                }
            }
        }
    }
}
