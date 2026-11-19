package xyz.nextalone.nagram.hgram;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HgramSecretCacheTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] randomBytes(int size) {
        final byte[] data = new byte[size];
        new SecureRandom().nextBytes(data);
        return data;
    }

    private static byte[] readAll(File file) throws Exception {
        final byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                final int read = in.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return data;
    }

    private File encrypt(byte[] plain, byte[] key, String identity, String name) throws Exception {
        final File source = folder.newFile(name + ".src");
        final File encrypted = folder.newFile(name + ".enc");
        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write(plain);
        }
        HgramSecretCache.encryptFile(source, encrypted, key, identity);
        return encrypted;
    }

    private byte[] decrypt(File encrypted, byte[] key, String identity) throws Exception {
        final File restored = folder.newFile();
        HgramSecretCache.decryptFile(encrypted, restored, key, identity);
        return readAll(restored);
    }

    private static int firstChunkRecordEnd(File encrypted) throws Exception {
        try (DataInputStream in = new DataInputStream(new FileInputStream(encrypted))) {
            in.readInt();
            in.readInt();
            in.readInt();
            in.readLong();
            in.readInt();
            final int length = in.readInt();
            return 24 + 4 + 12 + length;
        }
    }

    @Test
    public void chunkedRoundTripKeepsBytesAndHeader() throws Exception {
        final byte[] key = randomBytes(32);
        final byte[] plain = randomBytes(200_000);
        plain[0] = (byte) 0xFF;
        plain[1] = (byte) 0xD8;
        plain[2] = (byte) 0xFF;
        final File encrypted = encrypt(plain, key, "blob-a", "roundtrip");
        assertTrue(encrypted.length() > plain.length);

        try (DataInputStream in = new DataInputStream(new FileInputStream(encrypted))) {
            assertEquals(0x48534332, in.readInt());
            assertEquals(2, in.readInt());
            assertEquals(64 * 1024, in.readInt());
            assertEquals(plain.length, in.readLong());
            assertEquals(4, in.readInt());
        }
        assertArrayEquals(plain, decrypt(encrypted, key, "blob-a"));
    }

    @Test
    public void nonAlignedSizesRoundTrip() throws Exception {
        final byte[] key = randomBytes(32);
        final int[] sizes = {1, 64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 2 * 64 * 1024 + 3};
        for (int size : sizes) {
            final byte[] plain = randomBytes(size);
            final File encrypted = encrypt(plain, key, "blob-size-" + size, "size" + size);
            assertArrayEquals("size " + size, plain, decrypt(encrypted, key, "blob-size-" + size));
        }
    }

    @Test
    public void wrongKeyFailsAuthentication() throws Exception {
        final byte[] key = randomBytes(32);
        final byte[] wrong = randomBytes(32);
        final File encrypted = encrypt(randomBytes(4096), key, "blob-key", "wrongkey");
        try {
            decrypt(encrypted, wrong, "blob-key");
            fail("decryption with a foreign key must fail");
        } catch (Exception expected) {
            // GCM authentication failure.
        }
    }

    @Test
    public void truncatedAtChunkBoundaryFails() throws Exception {
        final byte[] key = randomBytes(32);
        final byte[] plain = randomBytes(130_000);
        final File encrypted = encrypt(plain, key, "blob-trunc", "trunc");
        final int end = firstChunkRecordEnd(encrypted);
        try (RandomAccessFile raf = new RandomAccessFile(encrypted, "rw")) {
            raf.setLength(end);
        }
        try {
            decrypt(encrypted, key, "blob-trunc");
            fail("truncated container must fail");
        } catch (Exception expected) {
            // Missing chunks.
        }
    }

    @Test
    public void chunkSwapBetweenBlobsFailsIdentityAad() throws Exception {
        final byte[] key = randomBytes(32);
        final byte[] a = randomBytes(140_000);
        final byte[] b = randomBytes(140_000);
        final File fileA = encrypt(a, key, "blob-a", "swapA");
        final File fileB = encrypt(b, key, "blob-b", "swapB");
        final byte[] encryptedA = readAll(fileA);
        final byte[] encryptedB = readAll(fileB);

        final int startB;
        try (DataInputStream in = new DataInputStream(new FileInputStream(fileB))) {
            in.readInt();
            in.readInt();
            in.readInt();
            in.readLong();
            in.readInt();
            final int len1 = in.readInt();
            in.skipBytes(12 + len1);
            startB = 24 + 4 + 12 + len1;
        }
        final int lengthB;
        try (DataInputStream in = new DataInputStream(new FileInputStream(fileB))) {
            in.readInt();
            in.readInt();
            in.readInt();
            in.readLong();
            in.readInt();
            final int len1 = in.readInt();
            in.skipBytes(12 + len1);
            lengthB = in.readInt();
        }
        final int endB = startB + 4 + 12 + lengthB;
        System.arraycopy(encryptedB, startB, encryptedA, startB, endB - startB);
        try (FileOutputStream out = new FileOutputStream(fileA)) {
            out.write(encryptedA);
        }
        try {
            decrypt(fileA, key, "blob-a");
            fail("cross-blob chunk substitution must fail");
        } catch (Exception expected) {
            // AAD identity mismatch.
        }
    }

    @Test
    public void corruptedChunkFails() throws Exception {
        final byte[] key = randomBytes(32);
        final File encrypted = encrypt(randomBytes(1024), key, "blob-corrupt", "corrupt");
        try (RandomAccessFile raf = new RandomAccessFile(encrypted, "rw")) {
            final long position = raf.length() - 1;
            raf.seek(position);
            final int value = raf.read();
            raf.seek(position);
            raf.write(value ^ 0x55);
        }
        try {
            decrypt(encrypted, key, "blob-corrupt");
            fail("a corrupted chunk must fail authentication");
        } catch (Exception expected) {
            // GCM tag mismatch.
        }
    }

    @Test
    public void indexWriteFailureIsReportedAndDoesNotClobberOldIndex() throws Exception {
        final byte[] key = randomBytes(32);
        final File blocker = folder.newFile("blocker");
        final File impossible = new File(blocker, "index");
        assertFalse(HgramSecretCache.writeIndexAtomically(impossible, new byte[]{1, 2, 3}, key));

        final File index = folder.newFile("index");
        final byte[] payload = "hello-index".getBytes("UTF-8");
        assertTrue(HgramSecretCache.writeIndexAtomically(index, payload, key));

        final File partBlocker = new File(index.getParentFile(), "index.part");
        assertTrue(partBlocker.mkdirs());
        assertFalse(HgramSecretCache.writeIndexAtomically(index, "must-not-land".getBytes("UTF-8"), key));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HgramSecretCache.decryptFile(index, out, key, "index");
        assertArrayEquals(payload, out.toByteArray());
    }

    @Test
    public void migrationSkipsTemporaryNames() {
        assertFalse(HgramSecretCache.isMigratableFile(new File("/tmp/x.part")));
        assertFalse(HgramSecretCache.isMigratableFile(new File("/tmp/x.temp")));
        assertFalse(HgramSecretCache.isMigratableFile(new File("/tmp/x.preload")));
        assertFalse(HgramSecretCache.isMigratableFile(new File("/tmp/x.tmp")));
        assertTrue(HgramSecretCache.isMigratableFile(new File("/tmp/x.jpg")));
        assertTrue(HgramSecretCache.isMigratableFile(new File("/tmp/x")));
    }


    @Test
    public void trailingDataAfterChunksFails() throws Exception {
        final byte[] key = randomBytes(32);
        final File encrypted = encrypt(randomBytes(2048), key, "blob-trail", "trail");
        try (FileOutputStream out = new FileOutputStream(encrypted, true)) {
            out.write(new byte[]{0x7f});
        }
        try {
            decrypt(encrypted, key, "blob-trail");
            fail("trailing data after the declared chunk count must fail");
        } catch (Exception expected) {
            // Extra bytes are rejected.
        }
    }


    @Test
    public void concurrentIndexWritesLeaveOneValidAtomicIndex() throws Exception {
        final byte[] key = randomBytes(32);
        final File index = folder.newFile("concurrent-index");
        final byte[] a = "payload-a".getBytes("UTF-8");
        final byte[] b = "payload-b".getBytes("UTF-8");
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return HgramSecretCache.writeIndexAtomically(index, a, key);
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return HgramSecretCache.writeIndexAtomically(index, b, key);
            });
            start.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HgramSecretCache.decryptFile(index, out, key, "index");
        final byte[] result = out.toByteArray();
        assertTrue(java.util.Arrays.equals(a, result) || java.util.Arrays.equals(b, result));
        assertFalse(new File(index.getParentFile(), "concurrent-index.part").exists());
    }

    @Test
    public void deleteTreeIsIdempotent() throws Exception {
        final File dir = folder.newFolder("tree");
        final File child = new File(dir, "child");
        try (FileOutputStream out = new FileOutputStream(child)) {
            out.write(randomBytes(16));
        }
        HgramSecretCache.deleteTree(dir);
        assertFalse(dir.exists());
        HgramSecretCache.deleteTree(dir);
        assertFalse(dir.exists());
    }

    @Test
    public void oldFormatIsRejected() throws Exception {
        final byte[] key = randomBytes(32);
        final File legacy = folder.newFile("legacy");
        try (FileOutputStream out = new FileOutputStream(legacy)) {
            out.write(new byte[]{'H', 'S', 'C', '1', 0, 0, 0, 1, 0, 0, 0, 4});
        }
        try {
            decrypt(legacy, key, "legacy");
            fail("legacy/truncated header must not decode");
        } catch (Exception expected) {
            // Invalid header.
        }
    }

}
