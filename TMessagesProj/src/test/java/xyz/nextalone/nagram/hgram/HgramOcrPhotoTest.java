package xyz.nextalone.nagram.hgram;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HgramOcrPhotoTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void onlyStillPhotosCanBeRecognized() {
        assertTrue(HgramOcrPhoto.canRecognize(true, false, false, false));
        assertFalse(HgramOcrPhoto.canRecognize(false, false, false, false));
        assertFalse(HgramOcrPhoto.canRecognize(true, true, false, false));
        assertFalse(HgramOcrPhoto.canRecognize(true, false, true, false));
        assertFalse(HgramOcrPhoto.canRecognize(true, false, false, true));
    }

    @Test
    public void messageFileWinsWhenItExists() throws Exception {
        final File messageFile = temporary.newFile("photo.jpg");
        final File currentFile = temporary.newFile("current.jpg");
        assertEquals(messageFile, HgramOcrPhoto.pickFile(messageFile, currentFile.getAbsolutePath()));
    }

    @Test
    public void absoluteCurrentFileIsUsedAsFallback() throws Exception {
        final File currentFile = temporary.newFile("photo.jpg");
        final File missingMessageFile = new File(temporary.getRoot(), "missing.jpg");
        assertEquals(currentFile, HgramOcrPhoto.pickFile(missingMessageFile, currentFile.getAbsolutePath()));
        assertNull(HgramOcrPhoto.pickFile(null, "photo.jpg"));
        assertEquals(currentFile, HgramOcrPhoto.pickFile(null, currentFile.getAbsolutePath()));
    }
}
