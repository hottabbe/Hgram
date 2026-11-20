package xyz.nextalone.nagram.hgram

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun rangeResumeAppendsToExistingPart() {
        val content = ByteArray(100_000) { (it * 31 % 251).toByte() }
        val spec = model("rus", content)
        val root = temporary.newFolder("resume-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val target = File(models, spec.fileName)
        val part = File(models, spec.fileName + ".part")
        val prefix = 40_000
        write(part, content.copyOfRange(0, prefix))
        val connection = FakeConnection(spec.url, content, HttpURLConnection.HTTP_PARTIAL)
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { connection }

        assertTrue(HgramOcrModels.downloadFileTo(spec, target, part, 0, spec.size, null))

        assertEquals("bytes=$prefix-", connection.requestProperties["Range"])
        assertEquals(prefix, connection.servedOffset)
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun ignoredRangeResponseRestartsFromZero() {
        val content = ByteArray(80_000) { (it * 17 % 253).toByte() }
        val spec = model("rus", content)
        val root = temporary.newFolder("restart-root")
        val models = File(root, HgramOcrModels.MODELS_DIR)
        assertTrue(models.mkdirs())
        val target = File(models, spec.fileName)
        val part = File(models, spec.fileName + ".part")
        val prefix = 20_000
        write(part, content.copyOfRange(0, prefix))
        val connection = FakeConnection(spec.url, content, HttpURLConnection.HTTP_OK)
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { connection }

        assertTrue(HgramOcrModels.downloadFileTo(spec, target, part, 0, spec.size, null))

        assertEquals("bytes=$prefix-", connection.requestProperties["Range"])
        assertEquals(0, connection.servedOffset)
        assertArrayEquals(content, target.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun cancelKeepsPartAndClearsThreadAndConnection() {
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
            reading,
            release,
        )
        HgramOcrModels.connectionFactory = HgramOcrModels.ConnectionFactory { connection }

        HgramOcrModels.download(root, listOf(spec), null, null)
        assertTrue("download must reach the blocking read", reading.await(5, TimeUnit.SECONDS))
        val thread = HgramOcrModels.downloadThreadForTests()
        assertNotNull(thread)

        HgramOcrModels.cancel()
        assertTrue(connection.disconnected)
        release.countDown()
        thread!!.join(5_000L)

        assertFalse(thread.isAlive)
        assertEquals(HgramOcrModels.ERROR_CANCELLED, HgramOcrModels.lastError())
        assertEquals(-1, HgramOcrModels.progress())
        assertNull(HgramOcrModels.activeConnectionForTests())
        assertNull(HgramOcrModels.downloadThreadForTests())
        assertTrue(part.isFile)
        assertEquals(prefix.toLong(), part.length())
        assertFalse(target.exists())
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

    /** Minimal HttpURLConnection that serves [body] from the requested Range offset. */
    private class FakeConnection(
        url: String,
        private val body: ByteArray,
        private val code: Int,
        private val reading: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
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
            val offset = if (code == HttpURLConnection.HTTP_OK) 0 else requested
            servedOffset = offset
            val source = ByteArrayInputStream(body, offset, body.size - offset)
            val waitForRead = reading
            val releaseRead = release
            if (waitForRead == null || releaseRead == null) {
                return source
            }
            return object : InputStream() {
                private var blocked = false

                override fun read(): Int = source.read()

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
                    return source.read(b, off, len)
                }
            }
        }
    }
}
