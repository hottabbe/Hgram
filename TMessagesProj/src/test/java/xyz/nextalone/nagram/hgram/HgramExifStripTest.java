package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramExifStripTest {

    @Test
    public void jpegDropsApp1App13CommentButKeepsApp2AndScanData() {
        byte[] app0First = jpegSegment(0xE0,
                bytes("JFIF\0\u0001\u0001\0\0\u0001\0\u0001\0\0"));
        byte[] app1 = jpegSegment(0xE1, bytes("Exif\0\0secret-gps"));
        byte[] app2 = jpegSegment(0xE2, bytes("ICC_PROFILE\u0001\u0002\u0003\u0004\u0005"));
        byte[] app13 = jpegSegment(0xED, bytes("Photoshop 3.0"));
        byte[] comment = jpegSegment(0xFE, bytes("private comment"));
        byte[] app0Second = jpegSegment(0xE0, bytes("JFIF\0"));
        byte[] sof0 = jpegSegment(0xC0,
                new byte[]{8, 0, 16, 0, 16, 3, 1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1});
        byte[] sos = jpegSegment(0xDA, new byte[]{3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0});
        byte[] scanTail = new byte[]{
                0x12, 0x34, (byte) 0xFF, 0x00, (byte) 0xAB, (byte) 0xCD, (byte) 0xFF, (byte) 0xD9};
        byte[] original = concat(new byte[]{(byte) 0xFF, (byte) 0xD8},
                app0First, app1, app2, app13, comment, app0Second, sof0, sos, scanTail);

        byte[] stripped = HgramExifCleaner.stripMetadata(original);

        assertEquals((byte) 0xFF, stripped[0]);
        assertEquals((byte) 0xD8, stripped[1]);
        assertTrue(stripped.length < original.length);
        assertTrue(findJpegHeaderSegment(stripped, 0xE1) < 0);
        assertTrue(findJpegHeaderSegment(stripped, 0xED) < 0);
        assertTrue(findJpegHeaderSegment(stripped, 0xFE) < 0);
        assertTrue(findJpegHeaderSegment(stripped, 0xE0) >= 0);
        assertTrue(findJpegHeaderSegment(stripped, 0xC0) >= 0);

        int app2At = indexOf(stripped, app2);
        assertTrue(app2At >= 0);
        assertArrayEquals(app2, Arrays.copyOfRange(stripped, app2At, app2At + app2.length));

        byte[] sosMarker = new byte[]{(byte) 0xFF, (byte) 0xDA};
        int originalSosAt = indexOf(original, sosMarker);
        int strippedSosAt = indexOf(stripped, sosMarker);
        assertTrue(originalSosAt >= 0);
        assertTrue(strippedSosAt >= 0);
        assertArrayEquals(
                Arrays.copyOfRange(original, originalSosAt, original.length),
                Arrays.copyOfRange(stripped, strippedSosAt, stripped.length));
    }

    @Test
    public void jpegWithOversizedSegmentIsReturnedUnchanged() {
        byte[] broken = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE1,
                (byte) 0xFF, (byte) 0xFF,
                0x00, 0x10, 0x01, 0x02};

        byte[] stripped = HgramExifCleaner.stripMetadata(broken);

        assertArrayEquals(broken, stripped);
    }

    @Test
    public void jpegWithNonMarkerByteIsReturnedUnchanged() {
        byte[] malformed = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x00, 0x00,
                0x42,
                (byte) 0xFF, (byte) 0xD9};

        byte[] stripped = HgramExifCleaner.stripMetadata(malformed);

        assertArrayEquals(malformed, stripped);
    }

    @Test
    public void pngDropsExifAndTextChunksButKeepsCriticalChunks() {
        byte[] signature = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] ihdr = pngChunk("IHDR",
                new byte[]{0, 0, 0, 16, 0, 0, 0, 16, 8, 6, 0, 0, 0});
        byte[] exif = pngChunk("eXIf", bytes("Exif\0\0gps"));
        byte[] iccp = pngChunk("iCCP", bytes("profile\0data"));
        byte[] text = pngChunk("tEXt", bytes("Comment\0secret"));
        byte[] idat = pngChunk("IDAT", new byte[]{0x78, (byte) 0x9C, 1, 2, 3});
        byte[] iend = pngChunk("IEND", new byte[0]);
        byte[] original = concat(signature, ihdr, exif, iccp, text, idat, iend);

        byte[] stripped = HgramExifCleaner.stripMetadata(original);

        byte[] expected = concat(signature, ihdr, iccp, idat, iend);
        assertArrayEquals(expected, stripped);
        assertArrayEquals(signature, Arrays.copyOfRange(stripped, 0, signature.length));
        assertTrue(indexOf(stripped, bytes("eXIf")) < 0);
        assertTrue(indexOf(stripped, bytes("tEXt")) < 0);
        assertTrue(indexOf(stripped, bytes("iCCP")) >= 0);
        assertTrue(indexOf(stripped, bytes("IDAT")) >= 0);
        assertTrue(indexOf(stripped, bytes("IEND")) >= 0);
        assertArrayEquals(iend,
                Arrays.copyOfRange(stripped, stripped.length - iend.length, stripped.length));
    }

    @Test
    public void webpDropsExifAndXmpAndClearsVp8xFlags() {
        byte[] vp8xPayload = new byte[]{
                0x0C, 0, 0, 0,
                0, 0, 0x0F,
                0, 0, 0x0F};
        byte[] vp8x = riffChunk("VP8X", vp8xPayload);
        byte[] exif = riffChunk("EXIF", bytes("MM\0*gps"));
        byte[] xmp = riffChunk("XMP ", bytes("xmp-secret"));
        byte[] vp8 = riffChunk("VP8 ", new byte[]{0x30, 0x01, 0x00, (byte) 0x9D, 0x01, 0x2A});
        byte[] original = riffFile(concat(vp8x, exif, xmp, vp8));

        byte[] stripped = HgramExifCleaner.stripMetadata(original);

        assertTrue(indexOf(stripped, bytes("EXIF")) < 0);
        assertTrue(indexOf(stripped, bytes("XMP ")) < 0);
        assertTrue(indexOf(stripped, bytes("VP8X")) >= 0);
        int vp8xPayloadAt = indexOf(stripped, bytes("VP8X")) + 8;
        assertEquals(0, stripped[vp8xPayloadAt] & 0x0C);
        assertEquals((long) stripped.length - 8L, readU32LE(stripped, 4));

        int vp8At = indexOf(stripped, vp8);
        assertTrue(vp8At >= 0);
        assertArrayEquals(vp8, Arrays.copyOfRange(stripped, vp8At, vp8At + vp8.length));
    }

    @Test
    public void prepareForSendJpegWritesStrippedCopyAndLeavesSourceIntact() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        byte[] app1 = jpegSegment(0xE1, bytes("Exif\0\0gps"));
        byte[] original = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app1,
                jpegSegment(0xFE, bytes("comment")),
                jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0}),
                new byte[]{(byte) 0xFF, (byte) 0xD9});
        File source = new File(sourceDir, "DCIM_0001.jpg");
        write(source, original);

        File result = HgramExifCleaner.prepareForSend(source, cacheDir);

        assertTrue(result != null);
        assertTrue(result.isFile());
        assertTrue(result.getName().matches("IMG_\\d{8}_\\d{6}\\.jpg"));
        assertFalse(source.getAbsolutePath().equals(result.getAbsolutePath()));
        byte[] cleaned = read(result);
        assertTrue(findJpegHeaderSegment(cleaned, 0xE1) < 0);
        assertTrue(findJpegHeaderSegment(original, 0xE1) >= 0);
        assertArrayEquals(original, read(source));
    }

    @Test
    public void prepareForSendHeicCopiesBytesWithoutStripping() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        byte[] payload = new byte[]{
                0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c',
                0x00, 0x00, 0x00, 0x00, 0x68, 0x65, 0x69, 0x63, 1, 2, 3, 4};
        File source = new File(sourceDir, "photo.heic");
        write(source, payload);

        File result = HgramExifCleaner.prepareForSend(source, cacheDir);

        assertTrue(result != null);
        assertTrue(result.isFile());
        // HEIC is treated as an image for neutral naming, but its bytes are copied as-is.
        assertTrue("expected IMG_<timestamp>.heic, got " + result.getName(),
                result.getName().matches("IMG_\\d{8}_\\d{6}\\.heic"));
        assertArrayEquals(payload, read(result));
        assertArrayEquals(payload, read(source));
    }

    @Test
    public void prepareForSendCopiesMp4BytesAndUsesFilePrefix() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        byte[] payload = concat(
                new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'},
                bytes("isom"),
                new byte[]{0, 0, 2, 0},
                bytes("isomiso2mp41"),
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, (byte) 0xFF, 0x00});
        File source = new File(sourceDir, "clip.mp4");
        write(source, payload);

        File result = HgramExifCleaner.prepareForSend(source, cacheDir);

        assertTrue(result != null);
        assertTrue(result.isFile());
        // Video container is not treated as an image for neutral naming.
        assertTrue("expected FILE_<timestamp>.mp4, got " + result.getName(),
                result.getName().matches("FILE_\\d{8}_\\d{6}\\.mp4"));
        assertFalse(source.getAbsolutePath().equals(result.getAbsolutePath()));
        assertArrayEquals(payload, read(result));
        assertArrayEquals(payload, read(source));
    }

    @Test
    public void prepareForSendMissingFileReturnsNull() throws Exception {
        File cacheDir = tempDir();
        File missing = new File(cacheDir, "missing.jpg");

        assertNull(HgramExifCleaner.prepareForSend(missing, cacheDir));
    }

    @Test
    public void sweepCacheDeletesOnlyExpiredFiles() throws Exception {
        File cacheDir = tempDir();
        File dir = new File(cacheDir, "hgram_exif");
        assertTrue(dir.mkdirs());
        File oldFile = new File(dir, "IMG_20260101_010101.jpg");
        File newFile = new File(dir, "FILE_20260101_010102.mp4");
        File foreign = new File(dir, "old.bin");
        write(oldFile, new byte[]{1});
        write(newFile, new byte[]{2});
        write(foreign, new byte[]{3});
        long now = System.currentTimeMillis();
        assertTrue(oldFile.setLastModified(now - HgramExifCleaner.SWEEP_MAX_AGE_MS - 1000L));
        assertTrue(newFile.setLastModified(now));
        assertTrue(foreign.setLastModified(now - HgramExifCleaner.SWEEP_MAX_AGE_MS - 1000L));

        HgramExifCleaner.sweepCache(cacheDir, now);

        assertFalse(oldFile.exists());
        assertTrue(newFile.exists());
        assertTrue("foreign files must never be swept", foreign.exists());
    }

    @Test
    public void stripMetadataHandlesNullAndShortGarbage() {
        assertNull(HgramExifCleaner.stripMetadata(null));

        byte[] shortGarbage = new byte[]{0x01, 0x02, 0x03};
        assertArrayEquals(shortGarbage, HgramExifCleaner.stripMetadata(shortGarbage));

        byte[] empty = new byte[0];
        assertArrayEquals(empty, HgramExifCleaner.stripMetadata(empty));
    }

    @Test
    public void heicIsCopiedWithNeutralNameAndNotParsed() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        byte[] payload = new byte[]{
                (byte) 0xDE, (byte) 0xAD, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c',
                0x42, 0x42, 0x42, 0x42, 0x01, 0x02, (byte) 0xFE, (byte) 0xFF};
        File source = new File(sourceDir, "camera_output.heic");
        write(source, payload);

        HgramExifCleaner.Prepared prepared =
                HgramExifCleaner.prepareForSendDetailed(source, cacheDir);

        assertEquals(HgramExifCleaner.PrepareResult.COPIED, prepared.result);
        assertTrue(prepared.file != null);
        assertTrue(prepared.file.isFile());
        assertTrue("expected IMG_<timestamp>.heic, got " + prepared.file.getName(),
                prepared.file.getName().matches("IMG_\\d{8}_\\d{6}\\.heic"));
        assertArrayEquals(payload, read(prepared.file));
        assertArrayEquals(payload, read(source));
        assertFalse(source.getAbsolutePath().equals(prepared.file.getAbsolutePath()));
    }

    @Test
    public void streamingStripHandlesSixtyMegabyteJpeg() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        byte[] app1 = jpegSegment(0xE1, bytes("Exif\0\0gps-secret"));
        byte[] app2 = jpegSegment(0xE2, concat(bytes("ICC_PROFILE\0"), new byte[]{1, 2, 3, 4}));
        byte[] sos = jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0});
        byte[] header = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app1, app2, sos);
        final int chunkSize = 64 * 1024;
        final int chunkCount = (60 * 1024 * 1024) / chunkSize;
        byte[] chunk = new byte[chunkSize];
        File source = new File(sourceDir, "huge.jpg");
        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write(header);
            for (int i = 0; i < chunkCount; i++) {
                for (int j = 0; j < chunk.length; j++) {
                    chunk[j] = (byte) ((i * 31 + j * 7) & 0xFF);
                }
                out.write(chunk);
            }
        }
        byte[] lastTailBytes = Arrays.copyOfRange(chunk, chunk.length - 16, chunk.length);

        HgramExifCleaner.Prepared prepared =
                HgramExifCleaner.prepareForSendDetailed(source, cacheDir);

        assertEquals(HgramExifCleaner.PrepareResult.CLEANED, prepared.result);
        File cleaned = prepared.file;
        assertTrue(cleaned != null && cleaned.isFile());
        assertEquals(source.length() - app1.length, cleaned.length());

        byte[] prefix = readPrefix(cleaned, 1024);
        assertEquals((byte) 0xFF, prefix[0]);
        assertEquals((byte) 0xD8, prefix[1]);
        assertTrue(findJpegHeaderSegment(prefix, 0xE2) >= 0);
        assertTrue(findJpegHeaderSegment(prefix, 0xE1) < 0);
        assertArrayEquals(lastTailBytes, readLastBytes(cleaned, 16));
    }

    @Test
    public void pngDropsTimeChunkAndKeepsCriticalCrcs() {
        byte[] signature = new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] ihdr = pngChunk("IHDR",
                new byte[]{0, 0, 0, 16, 0, 0, 0, 16, 8, 6, 0, 0, 0});
        byte[] time = pngChunk("tIME", new byte[]{0x07, (byte) 0xE8, 1, 2, 3, 4, 5});
        byte[] text = pngChunk("tEXt", bytes("Comment\0secret"));
        byte[] exif = pngChunk("eXIf", bytes("Exif\0\0gps"));
        byte[] iccp = pngChunk("iCCP", bytes("profile\0data"));
        byte[] idat = pngChunk("IDAT", new byte[]{0x78, (byte) 0x9C, 1, 2, 3});
        byte[] iend = pngChunk("IEND", new byte[0]);
        byte[] original = concat(signature, ihdr, time, text, exif, iccp, idat, iend);

        byte[] stripped = HgramExifCleaner.stripMetadata(original);

        assertArrayEquals(concat(signature, ihdr, iccp, idat, iend), stripped);
        assertTrue(indexOf(stripped, bytes("tIME")) < 0);
        assertTrue(indexOf(stripped, bytes("tEXt")) < 0);
        assertTrue(indexOf(stripped, bytes("eXIf")) < 0);
        assertTrue(indexOf(stripped, bytes("iCCP")) >= 0);
        assertTrue("IHDR chunk (with CRC) must survive byte-for-byte",
                indexOf(stripped, ihdr) >= 0);
        assertTrue("IDAT chunk (with CRC) must survive byte-for-byte",
                indexOf(stripped, idat) >= 0);
        assertTrue("IEND chunk (with CRC) must survive byte-for-byte",
                indexOf(stripped, iend) >= 0);
    }

    @Test
    public void parallelPrepareCreatesDistinctFiles() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        byte[] app1 = jpegSegment(0xE1, bytes("Exif\0\0gps"));
        byte[] original = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app1,
                jpegSegment(0xFE, bytes("comment")),
                jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0}),
                new byte[]{(byte) 0xFF, (byte) 0xD9});
        final File source = new File(sourceDir, "parallel.jpg");
        write(source, original);

        final AtomicReference<HgramExifCleaner.Prepared> first = new AtomicReference<>();
        final AtomicReference<HgramExifCleaner.Prepared> second = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread one = new Thread(() -> {
            try {
                first.set(HgramExifCleaner.prepareForSendDetailed(source, cacheDir));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread two = new Thread(() -> {
            try {
                second.set(HgramExifCleaner.prepareForSendDetailed(source, cacheDir));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        one.start();
        two.start();
        one.join();
        two.join();

        assertNull(failure.get());
        assertTrue(first.get() != null && second.get() != null);
        assertEquals(HgramExifCleaner.PrepareResult.CLEANED, first.get().result);
        assertEquals(HgramExifCleaner.PrepareResult.CLEANED, second.get().result);
        File firstFile = first.get().file;
        File secondFile = second.get().file;
        assertTrue(firstFile != null && firstFile.isFile());
        assertTrue(secondFile != null && secondFile.isFile());
        assertTrue(firstFile.getParentFile() != null);
        assertTrue(secondFile.getParentFile() != null);
        assertFalse("each prepare call must use its own subdirectory",
                firstFile.getParentFile().getAbsolutePath()
                        .equals(secondFile.getParentFile().getAbsolutePath()));
        assertArrayEquals(read(firstFile), read(secondFile));
        assertArrayEquals(original, read(source));
    }

    @Test
    public void jpegKeepsOrientationTagOnly() {
        byte[] exifPayload = concat(
                bytes("Exif\0\0"),
                new byte[]{'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00},
                new byte[]{0x01, 0x00},
                new byte[]{0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
                        0x06, 0x00, 0x00, 0x00},
                new byte[]{0x00, 0x00, 0x00, 0x00},
                bytes("GPS-SECRET-PADDING-BLOCK"));
        byte[] app1 = jpegSegment(0xE1, exifPayload);
        byte[] original = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app1,
                jpegSegment(0xE0, bytes("JFIF\0")),
                jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0}),
                new byte[]{(byte) 0xFF, (byte) 0xD9});
        assertTrue(indexOf(original, bytes("GPS-SECRET")) >= 0);

        byte[] stripped = HgramExifCleaner.stripMetadata(original);

        int app1At = findJpegHeaderSegment(stripped, 0xE1);
        assertTrue("orientation-only EXIF APP1 must be preserved", app1At >= 0);
        int segmentLength = ((stripped[app1At + 2] & 0xFF) << 8)
                | (stripped[app1At + 3] & 0xFF);
        int segmentTotal = 2 + segmentLength;
        assertTrue("rebuilt APP1 must be small, was " + segmentTotal, segmentTotal < 40);
        byte[] payload = Arrays.copyOfRange(stripped, app1At + 4, app1At + 4 + segmentLength - 2);
        assertEquals(6, readExifOrientation(payload));
        assertTrue(indexOf(stripped, bytes("GPS-SECRET")) < 0);
    }

    @Test
    public void orientationOutsideValidRangeIsDropped() {
        int[] orientations = {9, 0};
        for (int orientation : orientations) {
            byte[] exifPayload = concat(
                    bytes("Exif\0\0"),
                    new byte[]{'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00},
                    new byte[]{0x01, 0x00},
                    new byte[]{0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
                            (byte) orientation, 0x00, 0x00, 0x00},
                    new byte[]{0x00, 0x00, 0x00, 0x00});
            assertEquals(orientation, readExifOrientation(exifPayload));
            byte[] original = concat(new byte[]{(byte) 0xFF, (byte) 0xD8},
                    jpegSegment(0xE1, exifPayload),
                    jpegSegment(0xE0, bytes("JFIF\0")),
                    jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0}),
                    new byte[]{(byte) 0xFF, (byte) 0xD9});
            assertTrue(findJpegHeaderSegment(original, 0xE1) >= 0);

            byte[] stripped = HgramExifCleaner.stripMetadata(original);

            assertTrue("APP1 with orientation " + orientation + " must be dropped",
                    findJpegHeaderSegment(stripped, 0xE1) < 0);
            assertTrue(findJpegHeaderSegment(stripped, 0xE0) >= 0);
            assertEquals((byte) 0xFF, stripped[0]);
            assertEquals((byte) 0xD8, stripped[1]);
        }
    }

    @Test
    public void brokenJpegReportsParseFailed() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        File source = new File(sourceDir, "broken.jpg");
        write(source, bytes("not a jpeg at all, just garbage bytes"));

        HgramExifCleaner.Prepared prepared =
                HgramExifCleaner.prepareForSendDetailed(source, cacheDir);

        assertEquals(HgramExifCleaner.PrepareResult.PARSE_FAILED, prepared.result);
        assertNull(prepared.file);
        assertFalse(HgramExifCleaner.cacheKey("x", HgramExifCleaner.PrepareResult.CLEANED)
                .equals(HgramExifCleaner.cacheKey("x", HgramExifCleaner.PrepareResult.PARSE_FAILED)));
        assertNull(HgramExifCleaner.cacheKey(null, HgramExifCleaner.PrepareResult.CLEANED));
    }

    @Test
    public void sentDocumentCacheSeparatesPreparedFromMarkerShapedRawPath() throws Exception {
        // Typed sent-file namespace: prepared results use the offset slot, failures stay raw.
        assertEquals(1001, HgramExifCleaner.sentFileType(1, HgramExifCleaner.PrepareResult.CLEANED));
        assertEquals(1001, HgramExifCleaner.sentFileType(1, HgramExifCleaner.PrepareResult.COPIED));
        assertEquals(1, HgramExifCleaner.sentFileType(1, HgramExifCleaner.PrepareResult.PARSE_FAILED));
        assertEquals(1, HgramExifCleaner.sentFileType(1, HgramExifCleaner.PrepareResult.FAILED));

        // A raw path that literally ends with the legacy marker (same length) still lands in the
        // raw slot: the typed namespace, not the string shape, separates the two entries.
        String markerShapedRaw = "/tmp/photo.jpg#hgram_exif:cleaned";
        String sameLengthPreparedSource = markerShapedRaw; // production appends the same size suffix
        String rawSlot = HgramExifCleaner.sentFileSlotKey(markerShapedRaw, 1,
                HgramExifCleaner.PrepareResult.FAILED);
        String preparedSlot = HgramExifCleaner.sentFileSlotKey(sameLengthPreparedSource, 1,
                HgramExifCleaner.PrepareResult.CLEANED);
        assertFalse("marker-shaped raw path must not alias the prepared slot",
                rawSlot.equals(preparedSlot));
        assertTrue(rawSlot.startsWith("1|"));
        assertTrue(preparedSlot.startsWith("1001|"));
        assertEquals("cleaned and copied share one prepared namespace",
                preparedSlot, HgramExifCleaner.sentFileSlotKey(markerShapedRaw, 1,
                        HgramExifCleaner.PrepareResult.COPIED));

        // Real prepares keep the source bytes/name intact and use neutral prepared names.
        File cacheDir = tempDir();
        byte[] app1 = jpegSegment(0xE1, bytes("Exif\0\0gps"));
        byte[] original = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app1,
                jpegSegment(0xFE, bytes("comment")),
                jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0}),
                new byte[]{(byte) 0xFF, (byte) 0xD9});
        File jpeg = new File(tempDir(), "OFF_ON_OFF.jpg");
        write(jpeg, original);

        HgramExifCleaner.Prepared cleaned =
                HgramExifCleaner.prepareForSendDetailed(jpeg, cacheDir);
        assertEquals(HgramExifCleaner.PrepareResult.CLEANED, cleaned.result);
        assertTrue(cleaned.file != null && cleaned.file.isFile());
        assertEquals(1001, HgramExifCleaner.sentFileType(1, cleaned.result));
        assertTrue("prepared file must carry a neutral name: " + cleaned.file.getName(),
                cleaned.file.getName().matches("IMG_\\d{8}_\\d{6}\\.jpg"));
        assertFalse("OFF raw slot and ON prepared slot must differ",
                HgramExifCleaner.sentFileSlotKey(jpeg.getAbsolutePath(), 1,
                                HgramExifCleaner.PrepareResult.FAILED)
                        .equals(HgramExifCleaner.sentFileSlotKey(jpeg.getAbsolutePath(), 1,
                                cleaned.result)));
        assertTrue(findJpegHeaderSegment(read(cleaned.file), 0xE1) < 0);
        assertArrayEquals("source bytes must stay untouched", original, read(jpeg));
        assertEquals("OFF_ON_OFF.jpg", jpeg.getName());
        HgramExifCleaner.releaseOwner(cleaned.file.getAbsolutePath(), true);

        // FAILED -> COPIED -> FAILED on the same MP4 path: separate slots and namespace.
        File video = new File(tempDir(), "clip_retry.mp4");
        HgramExifCleaner.Prepared missing =
                HgramExifCleaner.prepareForSendDetailed(video, cacheDir);
        assertEquals(HgramExifCleaner.PrepareResult.FAILED, missing.result);
        assertNull(missing.file);
        String failedSlot = HgramExifCleaner.sentFileSlotKey(video.getAbsolutePath(), 1,
                missing.result);
        assertEquals("1|" + video.getAbsolutePath(), failedSlot);

        byte[] mp4 = concat(
                new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'},
                bytes("isom"),
                new byte[]{0, 0, 2, 0},
                bytes("isomiso2mp41"),
                new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, (byte) 0xFF, 0x00});
        write(video, mp4);
        HgramExifCleaner.Prepared copied =
                HgramExifCleaner.prepareForSendDetailed(video, cacheDir);
        assertEquals(HgramExifCleaner.PrepareResult.COPIED, copied.result);
        assertEquals(1001, HgramExifCleaner.sentFileType(1, copied.result));
        assertTrue("copied file must carry a neutral name: " + copied.file.getName(),
                copied.file.getName().matches("FILE_\\d{8}_\\d{6}\\.mp4"));
        String copiedSlot = HgramExifCleaner.sentFileSlotKey(video.getAbsolutePath(), 1,
                copied.result);
        assertFalse("FAILED raw slot and COPIED prepared slot must differ",
                failedSlot.equals(copiedSlot));
        assertTrue(copiedSlot.startsWith("1001|"));
        assertArrayEquals("copied bytes are unchanged", mp4, read(copied.file));
        assertArrayEquals("source bytes must stay untouched", mp4, read(video));
        assertEquals("clip_retry.mp4", video.getName());
        HgramExifCleaner.releaseOwner(copied.file.getAbsolutePath(), true);

        assertTrue(video.delete());
        HgramExifCleaner.Prepared missingAgain =
                HgramExifCleaner.prepareForSendDetailed(video, cacheDir);
        assertEquals(HgramExifCleaner.PrepareResult.FAILED, missingAgain.result);
        assertNull(missingAgain.file);
        assertEquals("the raw slot is stable across OFF cycles", failedSlot,
                HgramExifCleaner.sentFileSlotKey(video.getAbsolutePath(), 1,
                        missingAgain.result));
        assertFalse("a prepared slot is never reused for a raw failure",
                copiedSlot.equals(HgramExifCleaner.sentFileSlotKey(video.getAbsolutePath(), 1,
                        missingAgain.result)));
    }

    /**
     * Mirrors the {@code sent_files_v2} contract of {@code MessagesStorage.getSentFile/putSentFile}
     * on a temp Robolectric SQLite database: the prepared GIF and both documents of a two-file
     * group must probe and ACK through the same type, while OFF rows stay in the raw slot.
     * The real {@code MessagesStorage} needs the native loader and is not constructible here.
     */
    @Test
    public void sentFileDatabaseSlotRoundTripForPreparedGifAndGroupedDocuments() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        final int base = MessagesStorage.SENT_FILE_TYPE_AUDIO;
        final int gifRawSlot = HgramExifCleaner.sentFileType(base, true, false);
        final int gifPreparedSlot = HgramExifCleaner.sentFileType(base, true, true);
        final int docPreparedSlot = HgramExifCleaner.sentFileType(base, false, true);
        assertEquals(2, gifRawSlot);
        assertEquals(1002, gifPreparedSlot);
        assertEquals(1001, docPreparedSlot);

        File gif = new File(sourceDir, "anim.gif");
        write(gif, bytes("GIF89a prepared bytes are copied without parsing"));
        HgramExifCleaner.Prepared preparedGif =
                HgramExifCleaner.prepareForSendDetailed(gif, cacheDir);
        assertEquals(HgramExifCleaner.PrepareResult.COPIED, preparedGif.result);
        assertTrue(preparedGif.file.getName().matches("FILE_\\d{8}_\\d{6}\\.gif"));
        String gifKey = gif.getAbsolutePath() + preparedGif.file.length();

        File dbFile = new File(tempDir(), "sent_files_v2.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        try {
            db.execSQL("CREATE TABLE sent_files_v2 (uid TEXT NOT NULL, type INTEGER NOT NULL,"
                    + " data BLOB, parent TEXT, PRIMARY KEY (uid, type))");

            // Probe before the ACK sees nothing; the ACK/put then round-trips the document.
            assertNull(getSentDocument(db, gifKey, gifPreparedSlot));
            assertNull(getSentDocument(db, gifKey, gifRawSlot));
            putSentDocument(db, gifKey, gifPreparedSlot,
                    document(preparedGif.file.getName(), 11, preparedGif.file.length()));
            TLRPC.Document gifHit = getSentDocument(db, gifKey, gifPreparedSlot);
            assertNotNull("ACK/put must hit the same slot as the probe", gifHit);
            assertEquals(preparedGif.file.getName(), documentName(gifHit));
            assertNull("a prepared row must not appear in the raw OFF slot",
                    getSentDocument(db, gifKey, gifRawSlot));
            assertTrue(hasSentRow(db, gifKey, gifPreparedSlot));
            assertFalse(hasSentRow(db, gifKey, gifRawSlot));

            // OFF and ON rows coexist for the same path: OFF probes 2, ON probes 1002.
            putSentDocument(db, gifKey, gifRawSlot,
                    document("anim.gif", 12, gif.length()));
            assertEquals("anim.gif", documentName(getSentDocument(db, gifKey, gifRawSlot)));
            assertEquals(preparedGif.file.getName(),
                    documentName(getSentDocument(db, gifKey, gifPreparedSlot)));
            assertEquals("both typed rows share the uid but not the PK", 2,
                    countSentRows(db, gifKey));

            // A two-file group: each prepared copy has its own key/slot and its own document.
            byte[] app1 = jpegSegment(0xE1, bytes("Exif\0\0gps"));
            byte[] jpeg = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app1,
                    jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0}),
                    new byte[]{(byte) 0xFF, (byte) 0xD9});
            File groupJpeg = new File(sourceDir, "group_photo.jpg");
            write(groupJpeg, jpeg);
            File groupGif = new File(sourceDir, "group_anim.gif");
            write(groupGif, bytes("GIF89a group"));
            HgramExifCleaner.Prepared preparedJpeg =
                    HgramExifCleaner.prepareForSendDetailed(groupJpeg, cacheDir);
            HgramExifCleaner.Prepared preparedGroupGif =
                    HgramExifCleaner.prepareForSendDetailed(groupGif, cacheDir);
            assertEquals(HgramExifCleaner.PrepareResult.CLEANED, preparedJpeg.result);
            assertEquals(HgramExifCleaner.PrepareResult.COPIED, preparedGroupGif.result);
            String jpegKey = groupJpeg.getAbsolutePath() + preparedJpeg.file.length();
            String groupGifKey = groupGif.getAbsolutePath() + preparedGroupGif.file.length();
            putSentDocument(db, jpegKey, docPreparedSlot,
                    document(preparedJpeg.file.getName(), 21, preparedJpeg.file.length()));
            putSentDocument(db, groupGifKey, gifPreparedSlot,
                    document(preparedGroupGif.file.getName(), 22, preparedGroupGif.file.length()));
            assertEquals(preparedJpeg.file.getName(),
                    documentName(getSentDocument(db, jpegKey, docPreparedSlot)));
            assertEquals(preparedGroupGif.file.getName(),
                    documentName(getSentDocument(db, groupGifKey, gifPreparedSlot)));
            assertNull(getSentDocument(db, jpegKey, gifPreparedSlot));
            assertNull(getSentDocument(db, groupGifKey, docPreparedSlot));

            // Fail-open: a missing source keeps the legacy raw slot and never writes prepared.
            File missing = new File(sourceDir, "missing_clip.mp4");
            HgramExifCleaner.Prepared failed =
                    HgramExifCleaner.prepareForSendDetailed(missing, cacheDir);
            assertEquals(HgramExifCleaner.PrepareResult.FAILED, failed.result);
            assertNull(failed.file);
            int failedSlot = HgramExifCleaner.sentFileType(base, true, false);
            assertEquals(gifRawSlot, failedSlot);
            putSentDocument(db, missing.getAbsolutePath(), failedSlot,
                    document("missing_clip.mp4", 31, 0));
            assertNull("fail-open must not write the prepared slot",
                    getSentDocument(db, missing.getAbsolutePath(), gifPreparedSlot));
            assertEquals("missing_clip.mp4",
                    documentName(getSentDocument(db, missing.getAbsolutePath(), failedSlot)));

            HgramExifCleaner.releaseOwner(preparedJpeg.file.getAbsolutePath(), true);
            HgramExifCleaner.releaseOwner(preparedGroupGif.file.getAbsolutePath(), true);
        } finally {
            db.close();
        }
        HgramExifCleaner.releaseOwner(preparedGif.file.getAbsolutePath(), true);
    }

    /**
     * Helper-level reject coverage: sendMessage/editMessage need the full controller graph, so the
     * three rejects (missing user, secret peer, edit without a target) all go through the same
     * {@code releaseProvisional(params)} contract. Retryable/no-params paths keep the lease.
     */
    @Test
    public void rejectBeforeAndAfterLocalIdReleasesProvisional() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();

        HgramExifCleaner.Prepared beforeId = prepareJpeg(sourceDir, cacheDir, "no_user.jpg");
        String beforeIdPath = beforeId.file.getAbsolutePath();
        HgramExifCleaner.registerOwner(beforeIdPath, 1, 900, 0, "1001");
        SendMessagesHelper.releaseProvisional(preparedParams(beforeIdPath, 1001));
        assertFalse("a pre-id reject must release the provisional copy",
                beforeId.file.exists());
        assertFalse(beforeId.file.getParentFile().exists());
        assertFalse(HgramExifCleaner.isLeasedForTests(beforeIdPath));

        HgramExifCleaner.Prepared retryable = prepareJpeg(sourceDir, cacheDir, "retry.jpg");
        String retryablePath = retryable.file.getAbsolutePath();
        HgramExifCleaner.registerOwner(retryablePath, 1, 900, 0, "1001");
        SendMessagesHelper.releaseProvisional(null);
        SendMessagesHelper.releaseProvisional(new HashMap<>());
        assertTrue("retryable/no-params failures keep the prepared copy",
                retryable.file.isFile());
        assertTrue(HgramExifCleaner.isLeasedForTests(retryablePath));
        SendMessagesHelper.releaseProvisional(preparedParams(retryablePath, 1001));

        // After newMsg.id is assigned the real owner exists; a stale provisional release or an
        // unrelated release must keep it until releaseOwnersForMessages removes that message.
        HgramExifCleaner.Prepared afterId = prepareJpeg(sourceDir, cacheDir, "after_id.jpg");
        String afterIdPath = afterId.file.getAbsolutePath();
        HgramExifCleaner.registerOwner(afterIdPath, 1, 900, 0, "1001");
        HgramExifCleaner.registerOwner(afterIdPath, 1, 900, 77, "1001");
        HgramExifCleaner.releaseProvisional(afterIdPath);
        assertTrue("a real owner survives a provisional release", afterId.file.isFile());
        assertTrue(HgramExifCleaner.isLeasedForTests(afterIdPath));
        assertEquals(0, HgramExifCleaner.releaseOwnersForMessages(1, 901,
                Arrays.asList(77), true));
        assertTrue(afterId.file.isFile());
        assertEquals(1, HgramExifCleaner.releaseOwnersForMessages(1, 900,
                Arrays.asList(77), true));
        assertFalse(afterId.file.exists());
        assertFalse(HgramExifCleaner.isLeasedForTests(afterIdPath));
    }

    @Test
    public void twoOwnersOfSamePathSurviveFirstRelease() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        HgramExifCleaner.Prepared prepared = prepareJpeg(sourceDir, cacheDir, "shared.jpg");
        String path = prepared.file.getAbsolutePath();
        File uuidDir = prepared.file.getParentFile();

        HgramExifCleaner.registerOwner(path, 1, 700, 41, "1001");
        HgramExifCleaner.registerOwner(path, 2, 800, 42, "1002");
        // Repeated pins are idempotent and must not duplicate/evict the other owner.
        HgramExifCleaner.registerOwner(path, 1, 700, 41, "1001");
        HgramExifCleaner.registerOwner(path, 2, 800, 42, "1002");

        HgramExifCleaner.releaseOwner(path, 1, 700, 41, "1001", true);
        assertTrue("the first release must keep the copy for the second owner",
                prepared.file.isFile());
        assertTrue(uuidDir.isDirectory());
        assertTrue(HgramExifCleaner.isLeasedForTests(path));

        HgramExifCleaner.releaseOwner(path, 2, 800, 42, "1002", true);
        assertFalse("the last owner deletes the copy", prepared.file.exists());
        assertFalse(uuidDir.exists());
        assertFalse(HgramExifCleaner.isLeasedForTests(path));
    }

    @Test
    public void preparedLeaseReleasedOnRejectedHandoffAndTerminalDeletion() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();

        HgramExifCleaner.Prepared first = prepareJpeg(sourceDir, cacheDir, "owned.jpg");
        String firstPath = first.file.getAbsolutePath();
        File firstDir = first.file.getParentFile();
        HgramExifCleaner.registerOwner(firstPath, 1, 100, 0, "document");
        assertTrue("a provisional owner must keep the lease", HgramExifCleaner.isLeasedForTests(firstPath));

        // Other dialogs/messages must not release this copy.
        assertEquals(0, HgramExifCleaner.releaseOwnersForMessages(1, 101, Arrays.asList(55), true));
        assertEquals(0, HgramExifCleaner.releaseOwnersForMessages(1, 100, Arrays.asList(56), true));
        assertTrue(HgramExifCleaner.isLeasedForTests(firstPath));
        assertTrue(first.file.isFile());

        // The provisional owner is replaced by the real id; repeating it stays idempotent.
        HgramExifCleaner.registerOwner(firstPath, 1, 100, 55, "document");
        HgramExifCleaner.registerOwner(firstPath, 1, 100, 55, "document");
        assertTrue(HgramExifCleaner.isLeasedForTests(firstPath));
        assertEquals("exactly one owner is released", 1,
                HgramExifCleaner.releaseOwnersForMessages(1, 100, Arrays.asList(55), true));
        assertFalse(HgramExifCleaner.isLeasedForTests(firstPath));
        assertFalse(first.file.exists());
        assertFalse(firstDir.exists());
        assertEquals("a repeated release is a no-op", 0,
                HgramExifCleaner.releaseOwnersForMessages(1, 100, Arrays.asList(55), true));

        // Rejected hand-off: discardPrepared removes both the file and its owner/lease.
        HgramExifCleaner.Prepared rejected = prepareJpeg(sourceDir, cacheDir, "rejected.jpg");
        String rejectedPath = rejected.file.getAbsolutePath();
        HgramExifCleaner.registerOwner(rejectedPath, 1, 100, 0, "document");
        HgramExifCleaner.discardPrepared(rejectedPath);
        assertFalse(rejected.file.exists());
        assertFalse(rejected.file.getParentFile().exists());
        assertFalse(HgramExifCleaner.isLeasedForTests(rejectedPath));

        // Failed rename policy: the lease goes away, the file stays for TTL/manual cleanup.
        HgramExifCleaner.Prepared failedRename = prepareJpeg(sourceDir, cacheDir, "rename.jpg");
        String failedPath = failedRename.file.getAbsolutePath();
        HgramExifCleaner.registerOwner(failedPath, 1, 100, 77, "document");
        HgramExifCleaner.releaseOwner(failedPath, false);
        assertFalse(HgramExifCleaner.isLeasedForTests(failedPath));
        assertTrue("releaseOwner(false) must keep the file", failedRename.file.isFile());
        assertEquals("the owner is gone after releaseOwner(false)", 0,
                HgramExifCleaner.releaseOwnersForMessages(1, 100, Arrays.asList(77), true));
        assertTrue(failedRename.file.isFile());
        HgramExifCleaner.releaseOwner(failedPath, true);

        // Two owned copies: releasing one leaves the other file and its lease alone.
        HgramExifCleaner.Prepared left = prepareJpeg(sourceDir, cacheDir, "left.jpg");
        HgramExifCleaner.Prepared right = prepareJpeg(sourceDir, cacheDir, "right.jpg");
        String rightPath = right.file.getAbsolutePath();
        HgramExifCleaner.registerOwner(left.file.getAbsolutePath(), 1, 200, 91, "document");
        HgramExifCleaner.registerOwner(rightPath, 1, 200, 92, "document");
        assertEquals(1, HgramExifCleaner.releaseOwnersForMessages(1, 200, Arrays.asList(91), true));
        assertFalse(left.file.exists());
        assertTrue("the second owned copy must survive", right.file.isFile());
        assertTrue(HgramExifCleaner.isLeasedForTests(rightPath));
        HgramExifCleaner.releaseOwner(rightPath, true);
        assertFalse(right.file.exists());
    }

    @Test
    public void sweepPreservesReferencedAttachPathAfterRestart() throws Exception {
        File cacheDir = tempDir();
        File root = new File(cacheDir, "hgram_exif");
        assertTrue(root.mkdirs());
        long now = System.currentTimeMillis();
        long expired = now - HgramExifCleaner.SWEEP_MAX_AGE_MS - 60_000L;

        File pinnedDir = new File(root, "0f8b6d4c-7d2a-4a91-9a3f-2c1d8b0e5a11");
        assertTrue(pinnedDir.mkdirs());
        File pinned = new File(pinnedDir, "IMG_20261120_120000.jpg");
        write(pinned, bytes("prepared-jpeg-bytes"));
        assertTrue(pinned.setLastModified(expired));
        assertTrue(pinnedDir.setLastModified(expired));

        File orphanDir = new File(root, "11111111-2222-4333-8444-555555555555");
        assertTrue(orphanDir.mkdirs());
        File orphan = new File(orphanDir, "FILE_20261120_130000.mp4");
        write(orphan, bytes("orphan-bytes"));
        assertTrue(orphan.setLastModified(expired));
        assertTrue(orphanDir.setLastModified(expired));

        File flatArtifact = new File(root, "IMG_20261120_140000.png");
        write(flatArtifact, bytes("flat-bytes"));
        assertTrue(flatArtifact.setLastModified(expired));

        File foreignFile = new File(root, "notes.txt");
        write(foreignFile, bytes("keep-me"));
        assertTrue(foreignFile.setLastModified(expired));

        File foreignDir = new File(root, "customer-uploads");
        assertTrue(foreignDir.mkdirs());
        File foreignChild = new File(foreignDir, "IMG_keep.bin");
        write(foreignChild, bytes("keep"));
        assertTrue(foreignChild.setLastModified(expired));
        assertTrue(foreignDir.setLastModified(expired));

        File inFlightDir = new File(root, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        assertTrue(inFlightDir.mkdirs());
        File inFlightFile = new File(inFlightDir, "IMG_20261120_150000.jpg");
        write(inFlightFile, bytes("in-flight"));
        assertTrue(inFlightFile.setLastModified(expired));
        assertTrue(inFlightDir.setLastModified(expired));

        // Restart path: a pending send re-pins its prepared file before the next sweep runs.
        HgramExifCleaner.pinAttachPath(pinned.getAbsolutePath());
        assertTrue(HgramExifCleaner.isLeasedForTests(pinned.getAbsolutePath()));
        markInFlight(inFlightDir, true);
        try {
            HgramExifCleaner.sweepCache(cacheDir, now);
        } finally {
            markInFlight(inFlightDir, false);
        }

        assertTrue("pinned prepared file must survive sweep", pinned.isFile());
        assertTrue("UUID dir with a leased file must survive", pinnedDir.isDirectory());
        assertFalse("expired orphan artifact dir must be removed", orphanDir.exists());
        assertFalse("expired flat neutral artifact must be removed", flatArtifact.exists());
        assertTrue("foreign file must survive sweep", foreignFile.isFile());
        assertTrue("foreign dir must survive sweep", foreignDir.isDirectory());
        assertTrue("foreign file inside a foreign dir must survive", foreignChild.isFile());
        assertTrue("in-flight prepare dir must survive sweep", inFlightDir.isDirectory());

        HgramExifCleaner.releaseAttachPath(pinned.getAbsolutePath());
        assertFalse(HgramExifCleaner.isLeasedForTests(pinned.getAbsolutePath()));

        HgramExifCleaner.sweepCache(cacheDir, now);

        assertFalse("released artifact dir must be swept once expired", pinnedDir.exists());
        assertFalse("expired in-flight dir must be swept after it leaves flight",
                inFlightDir.exists());
        assertTrue(foreignFile.isFile());
        assertTrue(foreignDir.isDirectory());
    }

    @Test
    public void sweepKeepsPersistedRetryAndLaterPendingPagesAcrossAccounts() throws Exception {
        File cacheDir = tempDir();
        File root = new File(cacheDir, "hgram_exif");
        assertTrue(root.mkdirs());
        long now = System.currentTimeMillis();
        long expired = now - 100L * 60L * 60L * 1000L;

        // Simulates state 2 (pending) restored past MessagesStorage's 1000-row page: the sweep
        // cannot see it in the loaded pages, so the pinned attach path is its only protection.
        File pendingDir = new File(root, "33333333-4444-4555-8666-777777777777");
        assertTrue(pendingDir.mkdirs());
        File pending = new File(pendingDir, "FILE_20260101_010101.mp4");
        write(pending, bytes("persisted-retry"));
        assertTrue(pending.setLastModified(expired));
        assertTrue(pendingDir.setLastModified(expired));
        HgramExifCleaner.pinAttachPath(pending.getAbsolutePath());
        assertTrue(HgramExifCleaner.isLeasedForTests(pending.getAbsolutePath()));

        File orphanDir = new File(root, "44444444-5555-4666-8777-888888888888");
        assertTrue(orphanDir.mkdirs());
        File orphan = new File(orphanDir, "IMG_20260101_010102.jpg");
        write(orphan, bytes("orphan"));
        assertTrue(orphan.setLastModified(expired));
        assertTrue(orphanDir.setLastModified(expired));

        // A foreign file keeps itself and its UUID directory alive; only our own copy goes, and
        // the returned counter must count files, not directories.
        File sharedDir = new File(root, "55555555-6666-4777-8888-999999999999");
        assertTrue(sharedDir.mkdirs());
        File own = new File(sharedDir, "IMG_20260101_010103.jpg");
        File foreign = new File(sharedDir, "keep.txt");
        write(own, bytes("own"));
        write(foreign, bytes("keep"));
        assertTrue(own.setLastModified(expired));
        assertTrue(foreign.setLastModified(expired));
        assertTrue(sharedDir.setLastModified(expired));

        int deleted = HgramExifCleaner.sweepCache(cacheDir, now);

        assertTrue("pinned pending/retry copy must survive the sweep", pending.isFile());
        assertTrue("UUID dir with a pinned copy must survive", pendingDir.isDirectory());
        assertFalse("expired orphan copy must be removed", orphan.exists());
        assertFalse("empty UUID dir must be removed", orphanDir.exists());
        assertFalse("expired own copy must be removed", own.exists());
        assertTrue("foreign file inside a UUID dir must survive", foreign.isFile());
        assertTrue("UUID dir with a foreign file must survive", sharedDir.isDirectory());
        assertEquals("only two owned artifacts are deleted", 2, deleted);
        assertFalse("automatic sweep must stay disabled", HgramExifCleaner.AUTO_SWEEP_ENABLED);

        HgramExifCleaner.releaseAttachPath(pending.getAbsolutePath());
        assertFalse(HgramExifCleaner.isLeasedForTests(pending.getAbsolutePath()));

        int afterRelease = HgramExifCleaner.sweepCache(cacheDir, now);
        assertFalse("released expired copy is swept on the next pass", pending.exists());
        assertFalse("its UUID dir is swept too", pendingDir.exists());
        assertEquals("one more owned artifact is deleted", 1, afterRelease);
        assertTrue("foreign file still survives", foreign.isFile());
    }

    @Test
    public void discardPreparedReleasesLeaseAndRemovesOnlyOwnArtifacts() throws Exception {
        File cacheDir = tempDir();
        File sourceDir = tempDir();
        byte[] app1 = jpegSegment(0xE1, bytes("Exif\0\0gps"));
        byte[] original = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app1,
                jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0}),
                new byte[]{(byte) 0xFF, (byte) 0xD9});
        File source = new File(sourceDir, "discard.jpg");
        write(source, original);

        HgramExifCleaner.Prepared prepared =
                HgramExifCleaner.prepareForSendDetailed(source, cacheDir);
        assertEquals(HgramExifCleaner.PrepareResult.CLEANED, prepared.result);
        File preparedFile = prepared.file;
        File uuidDir = preparedFile.getParentFile();
        assertTrue(preparedFile.isFile());
        assertTrue(HgramExifCleaner.isLeasedForTests(preparedFile.getAbsolutePath()));

        File root = new File(cacheDir, "hgram_exif");
        File foreignFile = new File(root, "notes.txt");
        write(foreignFile, bytes("keep"));
        File foreignDir = new File(root, "customer-uploads");
        assertTrue(foreignDir.mkdirs());
        File foreignChild = new File(foreignDir, "IMG_keep.bin");
        write(foreignChild, bytes("keep"));

        HgramExifCleaner.discardPrepared(preparedFile.getAbsolutePath());

        assertFalse("prepared file must be removed", preparedFile.exists());
        assertFalse("empty UUID subdirectory must be removed", uuidDir.exists());
        assertFalse(HgramExifCleaner.isLeasedForTests(preparedFile.getAbsolutePath()));
        assertTrue(foreignFile.isFile());
        assertTrue(foreignDir.isDirectory());
        assertTrue(foreignChild.isFile());

        // Paths outside hgram_exif/<uuid>/ are never deleted.
        File external = new File(sourceDir, "IMG_20261120_120000.jpg");
        write(external, bytes("external"));
        HgramExifCleaner.discardPrepared(external.getAbsolutePath());
        assertTrue("foreign file outside the cache must survive", external.isFile());
        HgramExifCleaner.discardPrepared(foreignFile.getAbsolutePath());
        assertTrue("non-neutral file inside the cache must survive", foreignFile.isFile());

        // A UUID directory still holding another entry is kept; only our file is removed.
        File sharedDir = new File(root, "22222222-3333-4444-8555-666666666666");
        assertTrue(sharedDir.mkdirs());
        File sharedPrepared = new File(sharedDir, "IMG_20261120_130000.jpg");
        File sibling = new File(sharedDir, "FILE_20261120_130001.mp4");
        write(sharedPrepared, bytes("prepared"));
        write(sibling, bytes("sibling"));
        HgramExifCleaner.discardPrepared(sharedPrepared.getAbsolutePath());
        assertFalse(sharedPrepared.exists());
        assertTrue("UUID dir with another entry must survive", sibling.isFile());

        // Sweep afterwards still leaves foreign files/dirs alone.
        long now = System.currentTimeMillis();
        assertTrue(sharedDir.setLastModified(now - HgramExifCleaner.SWEEP_MAX_AGE_MS - 1000L));
        assertTrue(sibling.setLastModified(now - HgramExifCleaner.SWEEP_MAX_AGE_MS - 1000L));
        HgramExifCleaner.sweepCache(cacheDir, now);
        assertFalse("expired own artifact dir is swept", sharedDir.exists());
        assertTrue("foreign file must survive sweep", foreignFile.isFile());
        assertTrue("foreign dir must survive sweep", foreignDir.isDirectory());
        assertTrue("foreign child must survive sweep", foreignChild.isFile());
    }

    /**
     * The media cache is shared by all accounts, so the sweep barrier is a pure helper contract:
     * production calls it from {@code processUnsentMessages} with the activated account count.
     */
    @Test
    public void pendingPinBarrierWaitsForAllActivatedAccounts() {
        HgramExifCleaner.resetPinBarrierForTests();

        assertFalse("first of two accounts must not open the sweep barrier",
                HgramExifCleaner.registerPendingPins(0, 2));
        assertTrue("last activated account opens the barrier",
                HgramExifCleaner.registerPendingPins(1, 2));
        assertTrue("after the barrier, later passes may sweep",
                HgramExifCleaner.registerPendingPins(0, 2));

        assertFalse("a newly activated account must pin before the next sweep",
                HgramExifCleaner.registerPendingPins(0, 3));
        assertTrue(HgramExifCleaner.registerPendingPins(2, 3));

        HgramExifCleaner.resetPinBarrierForTests();
        assertTrue("a single activated account sweeps right away",
                HgramExifCleaner.registerPendingPins(0, 1));
        assertTrue("zero activated accounts is not a barrier",
                HgramExifCleaner.registerPendingPins(0, 0));
    }

    /**
     * Simulates the concurrent-prepare window: the production field is filled by
     * {@code prepareForSendDetailed} while writing, which cannot be paused deterministically.
     */
    @SuppressWarnings("unchecked")
    private static void markInFlight(File subdir, boolean inFlight) throws Exception {
        Field field = HgramExifCleaner.class.getDeclaredField("IN_FLIGHT");
        field.setAccessible(true);
        Set<String> keys = (Set<String>) field.get(null);
        assertTrue(keys != null);
        if (inFlight) {
            keys.add(subdir.getAbsolutePath());
        } else {
            keys.remove(subdir.getAbsolutePath());
        }
    }


    private static HashMap<String, String> preparedParams(String path, int slot) {
        HashMap<String, String> params = new HashMap<>();
        params.put("hgramExifPrepared", "1");
        params.put("hgramExifResult", HgramExifCleaner.PrepareResult.CLEANED.name());
        params.put("hgramExifSlot", String.valueOf(slot));
        params.put("hgramExifPath", path);
        return params;
    }

    private static TLRPC.TL_document document(String name, int id, long size) {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = id;
        document.access_hash = id;
        document.dc_id = 2;
        document.size = size;
        document.mime_type = "application/octet-stream";
        document.file_reference = new byte[0];
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = name;
        document.attributes.add(filename);
        return document;
    }

    private static String documentName(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                return attribute.file_name;
            }
        }
        return null;
    }

    /** Same row shape/serialization as MessagesStorage.putSentFile. */
    private static void putSentDocument(SQLiteDatabase db, String path, int type,
                                        TLRPC.TL_document document) {
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.flags |= 1;
        media.document = document;
        SerializedData data = new SerializedData();
        media.serializeToStream(data);
        byte[] bytes = data.toByteArray();
        data.cleanup();
        ContentValues values = new ContentValues();
        values.put("uid", md5(path));
        values.put("type", type);
        values.put("data", bytes);
        values.put("parent", "sent_test");
        assertTrue(db.insertWithOnConflict("sent_files_v2", null, values,
                SQLiteDatabase.CONFLICT_REPLACE) != -1);
    }

    /** Same query/deserialization as MessagesStorage.getSentFile. */
    private static TLRPC.Document getSentDocument(SQLiteDatabase db, String path, int type) {
        try (Cursor cursor = db.rawQuery(
                "SELECT data FROM sent_files_v2 WHERE uid = ? AND type = ?",
                new String[]{md5(path), Integer.toString(type)})) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            byte[] blob = cursor.getBlob(0);
            if (blob == null) {
                return null;
            }
            SerializedData data = new SerializedData(blob);
            try {
                TLRPC.MessageMedia media = TLRPC.MessageMedia.TLdeserialize(
                        data, data.readInt32(false), false);
                if (media instanceof TLRPC.TL_messageMediaDocument) {
                    return (TLRPC.TL_document) ((TLRPC.TL_messageMediaDocument) media).document;
                }
                return null;
            } finally {
                data.cleanup();
            }
        }
    }

    private static String md5(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean hasSentRow(SQLiteDatabase db, String path, int type) {
        return getSentDocument(db, path, type) != null;
    }

    private static int countSentRows(SQLiteDatabase db, String path) {
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM sent_files_v2 WHERE uid = ?",
                new String[]{md5(path)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static byte[] jpegSegment(int marker, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        int length = payload.length + 2;
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] pngChunk(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU32BE(out, payload.length);
        byte[] typeBytes = bytes(type);
        out.write(typeBytes, 0, typeBytes.length);
        out.write(payload, 0, payload.length);
        out.write(0xDE);
        out.write(0xAD);
        out.write(0xBE);
        out.write(0xEF);
        return out.toByteArray();
    }

    private static byte[] riffChunk(String fourcc, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tag = bytes(fourcc);
        out.write(tag, 0, tag.length);
        writeU32LE(out, payload.length);
        out.write(payload, 0, payload.length);
        if ((payload.length & 1) != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] riffFile(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] riff = bytes("RIFF");
        byte[] webp = bytes("WEBP");
        out.write(riff, 0, riff.length);
        writeU32LE(out, body.length + 4);
        out.write(webp, 0, webp.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void writeU32BE(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private static void writeU32LE(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }

    private static long readU32LE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static int readExifOrientation(byte[] payload) {
        if (payload == null || payload.length < 14
                || payload[0] != 'E' || payload[1] != 'x' || payload[2] != 'i'
                || payload[3] != 'f' || payload[4] != 0 || payload[5] != 0) {
            return -1;
        }
        int base = 6;
        boolean little;
        if (payload[base] == 'I' && payload[base + 1] == 'I') {
            little = true;
        } else if (payload[base] == 'M' && payload[base + 1] == 'M') {
            little = false;
        } else {
            return -1;
        }
        if (readU16(payload, base + 2, little) != 42) {
            return -1;
        }
        long ifdOffset = readU32(payload, base + 4, little);
        if (ifdOffset < 8 || ifdOffset > payload.length - base) {
            return -1;
        }
        int ifd = base + (int) ifdOffset;
        int count = readU16(payload, ifd, little);
        if (count < 0) {
            return -1;
        }
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > payload.length) {
                return -1;
            }
            if (readU16(payload, entry, little) == 0x0112) {
                if (readU16(payload, entry + 2, little) != 3
                        || readU32(payload, entry + 4, little) != 1) {
                    return -1;
                }
                return readU16(payload, entry + 8, little);
            }
        }
        return -1;
    }

    private static int readU16(byte[] data, int offset, boolean little) {
        if (offset < 0 || offset + 2 > data.length) {
            return -1;
        }
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        return little ? (second << 8) | first : (first << 8) | second;
    }

    private static long readU32(byte[] data, int offset, boolean little) {
        if (offset < 0 || offset + 4 > data.length) {
            return -1L;
        }
        if (little) {
            return (data[offset] & 0xFFL)
                    | ((data[offset + 1] & 0xFFL) << 8)
                    | ((data[offset + 2] & 0xFFL) << 16)
                    | ((data[offset + 3] & 0xFFL) << 24);
        }
        return ((data[offset] & 0xFFL) << 24)
                | ((data[offset + 1] & 0xFFL) << 16)
                | ((data[offset + 2] & 0xFFL) << 8)
                | (data[offset + 3] & 0xFFL);
    }

    private static byte[] readPrefix(File file, int maxBytes) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), maxBytes)];
            int read = 0;
            while (read < buffer.length) {
                int count = in.read(buffer, read, buffer.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return Arrays.copyOf(buffer, read);
        }
    }

    private static byte[] readLastBytes(File file, int count) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int length = (int) Math.min(file.length(), count);
            byte[] buffer = new byte[length];
            raf.seek(file.length() - length);
            raf.readFully(buffer);
            return buffer;
        }
    }

    private static int findJpegHeaderSegment(byte[] data, int marker) {
        if (data == null || data.length < 4) {
            return -1;
        }
        int pos = 2;
        while (pos + 1 < data.length) {
            int markerStart = pos;
            if ((data[pos] & 0xFF) != 0xFF) {
                return -1;
            }
            while (pos < data.length && (data[pos] & 0xFF) == 0xFF) {
                pos++;
            }
            if (pos >= data.length) {
                return -1;
            }
            int current = data[pos++] & 0xFF;
            if (current == 0xD8 || current == 0x01 || (current >= 0xD0 && current <= 0xD7)) {
                continue;
            }
            if (current == 0xD9 || current == 0xDA) {
                return -1;
            }
            if (pos + 1 >= data.length) {
                return -1;
            }
            int length = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            if (length < 2 || pos + length > data.length) {
                return -1;
            }
            if (current == marker) {
                return markerStart;
            }
            pos += length;
        }
        return -1;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        if (needle.length == 0 || data.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static File tempDir() throws IOException {
        File file = File.createTempFile("hgram-exif", ".dir");
        if (!file.delete() || !file.mkdirs()) {
            throw new IOException("cannot create temp dir: " + file);
        }
        return file;
    }

    private static HgramExifCleaner.Prepared prepareJpeg(File sourceDir, File cacheDir, String name)
            throws IOException {
        byte[] app1 = jpegSegment(0xE1, bytes("Exif\0\0gps"));
        byte[] jpeg = concat(new byte[]{(byte) 0xFF, (byte) 0xD8}, app1,
                jpegSegment(0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0}),
                new byte[]{(byte) 0xFF, (byte) 0xD9});
        File source = new File(sourceDir, name);
        write(source, jpeg);
        HgramExifCleaner.Prepared prepared =
                HgramExifCleaner.prepareForSendDetailed(source, cacheDir);
        assertEquals(HgramExifCleaner.PrepareResult.CLEANED, prepared.result);
        assertTrue(prepared.file != null && prepared.file.isFile());
        return prepared;
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
