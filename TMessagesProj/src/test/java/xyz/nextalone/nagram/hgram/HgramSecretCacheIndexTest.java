package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Context;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramSecretCacheIndexTest {

    private Context context;
    private File root;
    private byte[] key;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        HgramSecretCache.resetForTests();
        root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        key = new byte[32];
        new SecureRandom().nextBytes(key);
    }

    @After
    public void tearDown() {
        HgramSecretCache.resetForTests();
    }

    private String row(String path, String blob) {
        return Base64.encodeToString(path.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP) + "\t" + blob;
    }

    private void publishIndex(String rows) throws Exception {
        assertTrue(HgramSecretCache.writeIndexAtomically(
                new File(root, "index"), rows.getBytes(StandardCharsets.UTF_8), key));
    }

    private File blob(String name) throws Exception {
        final File file = new File(root, name);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3});
        }
        return file;
    }

    private String readIndex() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        HgramSecretCache.decryptFile(new File(root, "index"), out, key, "index");
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void existingPublishedBlobSurvivesFailedIndexCommit() throws Exception {
        final String path = "/cache/a.jpg";
        final String name = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        publishIndex(row(path, name));
        final File target = blob(name);
        HgramSecretCache.putMappingForTest(path, name);

        assertTrue(HgramSecretCache.confirmPublishedForTest(
                key, path, name, true, true, target));
        assertTrue(target.exists());
        assertTrue(HgramSecretCache.hasMappingForTest(path));
    }

    @Test
    public void newBlobPublishedByAnotherWriterIsNotDeleted() throws Exception {
        final String path = "/cache/b.jpg";
        final String name = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        publishIndex(row(path, name));
        final File target = blob(name);
        HgramSecretCache.putMappingForTest(path, name);

        // existedBefore=false: this call created the blob, but a concurrent writer already
        // published the path into the on-disk index.
        assertTrue(HgramSecretCache.confirmPublishedForTest(
                key, path, name, false, true, target));
        assertTrue(target.exists());
    }

    @Test
    public void uncommittedNewBlobIsRolledBack() throws Exception {
        final String path = "/cache/c.jpg";
        final String name = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        publishIndex(row("/cache/other.jpg", "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"));
        final File target = blob(name);
        HgramSecretCache.putMappingForTest(path, name);

        assertFalse(HgramSecretCache.confirmPublishedForTest(
                key, path, name, false, false, target));
        assertFalse(target.exists());
        assertFalse(HgramSecretCache.hasMappingForTest(path));
    }

    @Test
    public void brokenBlobDropsOnlyItsEntry() throws Exception {
        final String pathA = "/cache/a.jpg";
        final String pathB = "/cache/b.jpg";
        final String nameA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        final String nameB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        publishIndex(row(pathA, nameA) + "\n" + row(pathB, nameB));
        final File targetA = blob(nameA);
        final File targetB = blob(nameB);
        HgramSecretCache.putMappingForTest(pathA, nameA);
        HgramSecretCache.putMappingForTest(pathB, nameB);

        HgramSecretCache.dropBrokenEntryForTest(pathA, nameA, key);

        assertFalse(targetA.exists());
        assertTrue(targetB.exists());
        assertFalse(HgramSecretCache.hasMappingForTest(pathA));
        assertTrue(HgramSecretCache.hasMappingForTest(pathB));
        final String index = readIndex();
        assertFalse(index.contains(row(pathA, nameA)));
        assertTrue(index.contains(row(pathB, nameB)));
    }
}
