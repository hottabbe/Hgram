package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public class AutoDeleteMediaTaskTest {

    /**
     * The full {@code run()} needs ApplicationLoader/ImageLoader and a real media cache, so this
     * exercises the same recursive collector the size evictor uses: a prepared retry copy under
     * {@code <MEDIA_DIR_CACHE>/hgram_exif} must stay out of the eviction list and must survive.
     */
    @Test
    public void autoCacheLimitSkipsManagedExifCopies() throws Exception {
        File root = tempDir();
        File exifRoot = new File(root, "hgram_exif");
        File uuidDir = new File(exifRoot, "0f8b6d4c-7d2a-4a91-9a3f-2c1d8b0e5a11");
        File preparedJpeg = new File(uuidDir, "IMG_20261120_120000.jpg");
        File preparedVideo = new File(uuidDir, "FILE_20261120_120001.mp4");
        File normalPhoto = new File(root, "photo.jpg");
        File nestedDir = new File(root, "videos");
        File nestedVideo = new File(nestedDir, "clip.mp4");
        assertTrue(uuidDir.mkdirs());
        assertTrue(nestedDir.mkdirs());
        write(preparedJpeg, "prepared-jpeg");
        write(preparedVideo, "prepared-video");
        write(normalPhoto, "cache-photo");
        write(nestedVideo, "cache-video");

        assertTrue(AutoDeleteMediaTask.isManagedExifDir(exifRoot));
        assertTrue(AutoDeleteMediaTask.isManagedExifDir(uuidDir));
        assertTrue("even a direct prepared file is managed", AutoDeleteMediaTask.isManagedExifDir(preparedJpeg));
        assertFalse(AutoDeleteMediaTask.isManagedExifDir(normalPhoto));
        assertFalse(AutoDeleteMediaTask.isManagedExifDir(nestedVideo));
        assertFalse(AutoDeleteMediaTask.isManagedExifDir(root));

        ArrayList<File> collected = new ArrayList<>();
        AutoDeleteMediaTask.collectFilesRecursive(root, collected);

        boolean sawNormalPhoto = false;
        boolean sawNestedVideo = false;
        for (File file : collected) {
            assertNotNull(file);
            assertFalse("managed EXIF copy must never be collected: " + file,
                    AutoDeleteMediaTask.isManagedExifDir(file));
            if (file.equals(normalPhoto)) {
                sawNormalPhoto = true;
            }
            if (file.equals(nestedVideo)) {
                sawNestedVideo = true;
            }
        }
        assertTrue("ordinary cache files are still eviction candidates", sawNormalPhoto);
        assertTrue(sawNestedVideo);
        // No deletion happens during collection; the retry copy is intact for the pending send.
        assertTrue(preparedJpeg.isFile());
        assertTrue(preparedVideo.isFile());
    }

    private static void write(File file, String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes("UTF-8"));
        }
    }

    private static File tempDir() throws IOException {
        File file = File.createTempFile("hgram-cache", ".dir");
        if (!file.delete() || !file.mkdirs()) {
            throw new IOException("cannot create temp dir: " + file);
        }
        return file;
    }
}
