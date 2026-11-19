package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramSecretCacheWipeTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        HgramSecretCache.resetForTests();
    }

    @After
    public void tearDown() {
        HgramSecretCache.resetForTests();
    }

    @Test
    public void pendingWipeMarkerSurvivesAndWipesBeforeCacheInit() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        final File temp = new File(context.getNoBackupFilesDir(), "hgram_secret_tmp");
        final File quarantine = new File(context.getFilesDir(), "hgram_secret_quarantine");
        assertTrue(root.mkdirs());
        assertTrue(temp.mkdirs());
        assertTrue(quarantine.mkdirs());
        write(new File(root, "key"), new byte[]{1, 2, 3});
        write(new File(root, "index"), new byte[]{4, 5, 6});
        write(new File(root, "blob"), new byte[]{7, 8, 9});
        write(new File(temp, "blob.tmp"), new byte[]{10});
        write(new File(quarantine, "recovery"), new byte[]{11});

        HgramSecretCache.markWipePendingForTest();
        assertTrue(HgramSecretCache.isWipePending());

        // Simulates a fresh process: initialized flag is reset, but the durable prefs marker stays.
        HgramSecretCache.blockBulkForTests = true;
        HgramSecretCache.onProcessStart();
        assertTrue(HgramSecretCache.isCacheUnusableForTest());
        HgramSecretCache.performWipeBlockingForTest();

        assertFalse(root.exists());
        assertFalse(temp.exists());
        assertFalse(quarantine.exists());
        assertFalse(HgramSecretCache.isWipePending());
        assertFalse(context.getSharedPreferences("hgram_secret_cache_state", Context.MODE_PRIVATE)
                .getBoolean("wipe_pending", false));
    }

    @Test
    public void clearDuringPendingWipeIsIdempotent() {
        HgramSecretCache.markWipePendingForTest();
        HgramSecretCache.blockBulkForTests = true;
        HgramSecretCache.clear();
        HgramSecretCache.clear();
        assertTrue(HgramSecretCache.isCacheUnusableForTest());
        HgramSecretCache.performWipeBlockingForTest();
        assertFalse(HgramSecretCache.isWipePending());
    }


    @Test
    public void aliasDeletionFailureKeepsPendingMarker() {
        HgramSecretCache.skipKeystoreForTests = false;
        HgramSecretCache.failKeystoreForTests = true;
        HgramSecretCache.markWipePendingForTest();
        HgramSecretCache.clear();
        assertTrue(HgramSecretCache.isWipePending());
    }

    @Test
    public void deleteTreeFailureKeepsPendingMarker() {
        HgramSecretCache.failDeleteTreeForTests = true;
        HgramSecretCache.markWipePendingForTest();
        HgramSecretCache.clear();
        assertTrue(HgramSecretCache.isWipePending());
    }


    @Test
    public void prefsCommitFalseStillWritesFileMarkerAndRetriesOnStart() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        write(new File(root, "key"), new byte[]{1, 2, 3});
        HgramSecretCache.failPrefsCommitForTests = true;

        assertEquals(HgramSecretCache.MARK_DURABLE, HgramSecretCache.markWipePendingForTest());
        assertTrue(HgramSecretCache.hasFileMarkerForTest());

        // Simulate the next process: prefs work again and only the file marker remains.
        HgramSecretCache.failPrefsCommitForTests = false;
        HgramSecretCache.resetPendingCacheForTest();
        assertTrue(HgramSecretCache.isWipePending());

        HgramSecretCache.blockBulkForTests = true;
        HgramSecretCache.onProcessStart();
        HgramSecretCache.performWipeBlockingForTest();
        assertFalse(root.exists());
        assertFalse(HgramSecretCache.hasFileMarkerForTest());
        assertFalse(HgramSecretCache.isWipePending());
    }

    @Test
    public void commitExceptionDoesNotEscape() {
        HgramSecretCache.failPrefsCommitForTests = true;
        HgramSecretCache.throwPrefsCommitForTests = true;
        HgramSecretCache.failMarkerFileForTests = true;
        assertEquals(HgramSecretCache.MARK_KEY_ONLY, HgramSecretCache.markWipePendingForTest());
        assertTrue(HgramSecretCache.isWipePending());
    }

    @Test
    public void onlyFileMarkerIsEnough() {
        HgramSecretCache.failPrefsCommitForTests = true;
        assertEquals(HgramSecretCache.MARK_DURABLE, HgramSecretCache.markWipePendingForTest());
        HgramSecretCache.resetPendingCacheForTest();
        assertTrue(HgramSecretCache.isWipePending());
    }

    @Test
    public void undeletableKeyIsOverwrittenAndRenamed() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        final File key = new File(root, "key");
        write(key, new byte[]{1, 2, 3, 4});
        HgramSecretCache.failKeyDeleteForTests = true;

        assertTrue(HgramSecretCache.invalidateKeyFileSyncForTest());
        assertFalse(key.exists());
        final File[] invalid = root.listFiles((dir, name) -> name.startsWith("key.invalid."));
        assertTrue(invalid == null || invalid.length == 0);
    }

    @Test
    public void failedMarkersFallBackToSynchronousKeyRemoval() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        final File key = new File(root, "key");
        write(key, new byte[]{9, 8, 7});
        HgramSecretCache.failPrefsCommitForTests = true;
        HgramSecretCache.failMarkerFileForTests = true;
        HgramSecretCache.blockBulkForTests = true;

        HgramSecretCache.wipe();

        assertFalse(key.exists());
        assertTrue(HgramSecretCache.isWipePending());
    }


    @Test
    public void failedMarkersAndKeyProduceFailedAndUnusableCache() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        final File key = new File(root, "key");
        write(key, new byte[]{1, 2, 3, 4});
        HgramSecretCache.failPrefsCommitForTests = true;
        HgramSecretCache.failMarkerFileForTests = true;
        HgramSecretCache.failKeyOverwriteForTests = true;
        HgramSecretCache.failKeyDeleteForTests = true;
        HgramSecretCache.failDeleteTreeForTests = true;

        assertEquals(HgramSecretCache.MARK_DURABLE, HgramSecretCache.markWipePendingForTest());
        HgramSecretCache.wipe();

        assertTrue(HgramSecretCache.isCacheUnusableForTest());
        assertFalse(HgramSecretCache.hasDataKeyForTest());
        final File original = new File(root, "blob");
        final File readable = HgramSecretCache.resolveReadable(original);
        assertEquals("unavailable", readable.getName());
    }

    @Test
    public void overwriteFailureIsNotReportedAsKeyInvalidation() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        final File key = new File(root, "key");
        write(key, new byte[]{5, 6, 7, 8});
        HgramSecretCache.failKeyOverwriteForTests = true;
        HgramSecretCache.failKeyDeleteForTests = true;
        assertFalse(HgramSecretCache.invalidateKeyFileSyncForTest());
        assertTrue(key.exists());
    }


    @Test
    public void failedMarkersRenameRootAndKeepItUnreadableUntilBulk() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        final File key = new File(root, "key");
        write(key, new byte[]{1, 2, 3});
        HgramSecretCache.failPrefsCommitForTests = true;
        HgramSecretCache.failMarkerFileForTests = true;
        HgramSecretCache.failKeyOverwriteForTests = true;
        HgramSecretCache.failKeyDeleteForTests = true;
        HgramSecretCache.blockBulkForTests = true;

        assertEquals(HgramSecretCache.MARK_DURABLE, HgramSecretCache.markWipePendingForTest());
        HgramSecretCache.wipe();

        assertFalse(root.exists());
        assertTrue(HgramSecretCache.hasWipeTrashForTest());
        assertTrue(HgramSecretCache.isWipePending());
        assertTrue(HgramSecretCache.isCacheUnusableForTest());
        assertFalse(HgramSecretCache.hasDataKeyForTest());
        assertEquals("unavailable",
                HgramSecretCache.resolveReadable(new File(root, "blob")).getName());

        HgramSecretCache.failPrefsCommitForTests = false;
        HgramSecretCache.failMarkerFileForTests = false;
        HgramSecretCache.performWipeBlockingForTest();
        assertFalse(HgramSecretCache.hasWipeTrashForTest());
        assertFalse(HgramSecretCache.isWipePending());
    }

    @Test
    public void startupWithExistingTrashStaysUnusableAndSchedulesBulk() throws Exception {
        final File trash = new File(context.getFilesDir(), "hgram_secret_cache.wipe.123");
        assertTrue(trash.mkdirs());
        write(new File(trash, "key"), new byte[]{1, 2, 3});
        HgramSecretCache.blockBulkForTests = true;
        HgramSecretCache.resetPendingCacheForTest();

        HgramSecretCache.onProcessStart();

        assertTrue(HgramSecretCache.isCacheUnusableForTest());
        assertTrue(HgramSecretCache.isWipePending());
        assertFalse(HgramSecretCache.hasDataKeyForTest());
        HgramSecretCache.performWipeBlockingForTest();
        assertFalse(trash.exists());
    }

    @Test
    public void wipeDoesNotDeleteTreeOnCallerThread() {
        HgramSecretCache.blockBulkForTests = true;
        HgramSecretCache.countDeleteTreeForTests = true;
        HgramSecretCache.resetDeleteTreeCounterForTest();
        HgramSecretCache.wipe();
        assertEquals(0, HgramSecretCache.deleteTreeCalls);
        HgramSecretCache.performWipeBlockingForTest();
        assertTrue(HgramSecretCache.deleteTreeCalls > 0);
    }

    @Test
    public void keyOnlyWipeInvalidatesKeyAndLeavesBulkForBackground() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        final File key = new File(root, "key");
        write(key, new byte[]{7, 7, 7});
        HgramSecretCache.failPrefsCommitForTests = true;
        HgramSecretCache.failMarkerFileForTests = true;
        HgramSecretCache.blockBulkForTests = true;
        HgramSecretCache.countDeleteTreeForTests = true;
        HgramSecretCache.resetDeleteTreeCounterForTest();

        assertEquals(HgramSecretCache.MARK_KEY_ONLY, HgramSecretCache.markWipePendingForTest());
        HgramSecretCache.clear();

        assertFalse(key.exists());
        assertTrue(HgramSecretCache.isCacheUnusableForTest());
        assertEquals(0, HgramSecretCache.deleteTreeCalls);
        HgramSecretCache.clear();
        assertEquals(0, HgramSecretCache.deleteTreeCalls);
        HgramSecretCache.performWipeBlockingForTest();
        assertFalse(root.exists());
    }


    @Test
    public void leftoverMarkerPartIsPendingAndWipedOnStart() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        final File part = new File(root, "wipe.pending.part");
        write(part, new byte[]{'H', 'S', 'C', 'W'});
        HgramSecretCache.resetPendingCacheForTest();
        HgramSecretCache.blockBulkForTests = true;

        assertTrue(HgramSecretCache.isWipePending());
        HgramSecretCache.onProcessStart();
        assertTrue(HgramSecretCache.isCacheUnusableForTest());
        HgramSecretCache.performWipeBlockingForTest();

        assertFalse(part.exists());
        assertFalse(HgramSecretCache.hasFileMarkerForTest());
        assertFalse(HgramSecretCache.isWipePending());
    }

    @Test
    public void markerOverwritesExistingFileWithoutPart() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        final File marker = new File(root, "wipe.pending");
        write(marker, new byte[]{'o', 'l', 'd'});
        assertEquals(HgramSecretCache.MARK_DURABLE, HgramSecretCache.markWipePendingForTest());
        assertTrue(marker.isFile());
        assertFalse(new File(root, "wipe.pending.part").exists());
    }

    @Test
    public void successfulBulkRemovesMarkersPartsAndTrash() throws Exception {
        final File root = new File(context.getFilesDir(), "hgram_secret_cache");
        assertTrue(root.mkdirs());
        write(new File(root, "wipe.pending.part"), new byte[]{1});
        final File invalid = new File(context.getNoBackupFilesDir(), "key.invalid.1");
        write(invalid, new byte[]{2});
        HgramSecretCache.markWipePendingForTest();
        HgramSecretCache.blockBulkForTests = true;
        HgramSecretCache.performWipeBlockingForTest();
        assertFalse(new File(root, "wipe.pending").exists());
        assertFalse(new File(root, "wipe.pending.part").exists());
        assertFalse(HgramSecretCache.hasWipeTrashForTest());
        assertFalse(HgramSecretCache.isWipePending());
        assertFalse(invalid.exists());
    }

    private static void write(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }
}
