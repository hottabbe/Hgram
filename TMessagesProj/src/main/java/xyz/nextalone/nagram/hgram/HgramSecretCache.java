package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted at-rest cache for the second (hidden) space.
 *
 * <p>Blobs live in {@code files/hgram_secret_cache}; names are HMAC-SHA256 of the original path and
 * have no extension. Content is a chunked AES-256-GCM container authenticated by blob name, header
 * values and chunk index. The data key is random (256-bit) and wrapped by a non-exportable Android
 * Keystore key; it is deliberately not derived from the decoy PIN, so the cache is available while
 * the second space is unlocked and is wiped when the decoy PIN is removed. Message texts are NOT
 * covered: they remain in the shared {@code cache4.db} (separate D8 project).</p>
 */
public final class HgramSecretCache {

    private static final int FILE_MAGIC = 0x48534332; // HSC2
    private static final int FILE_VERSION = 2;
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int IV_SIZE = 12;
    private static final int TAG_BITS = 128;
    private static final String AAD_PREFIX = "HSC2";
    private static final String KEY_ALIAS = "hgram_secret_cache_wrap_v1";
    private static final String ROOT_NAME = "hgram_secret_cache";
    private static final String QUARANTINE_NAME = "hgram_secret_quarantine";
    private static final String KEY_FILE = "key";
    private static final String WIPE_MARKER_FILE = "wipe.pending";
    private static final String FALLBACK_WIPE_MARKER_FILE = "hgram_wipe.pending";
    private static final String INDEX_FILE = "index";
    private static final String INDEX_IDENTITY = "index";
    private static final String TEMP_DIR = "hgram_secret_tmp";
    static final int MARK_DURABLE = 0;
    static final int MARK_KEY_ONLY = 1;
    static final int MARK_FAILED = 2;
    private static final long MAX_INDEX_BYTES = 16L * 1024L * 1024L;
    private static final long SMALL_SYNC_DECRYPT_BYTES = 2L * 1024L * 1024L;
    private static final long TEMP_TTL_MS = 10_000L;

    private static final Object LOCK = new Object();
    private static final Object INDEX_LOCK = new Object();
    private static final Object KEY_LOCK = new Object();
    private static final Object WIPE_LOCK = new Object();
    private static final String STATE_PREFS = "hgram_secret_cache_state";
    private static final String KEY_WIPE_PENDING = "wipe_pending";
    private static final Map<String, String> pathToBlob = new HashMap<>();
    private static final Map<String, File> tempFiles = new HashMap<>();
    private static final Map<String, CountDownLatch> decrypting = new HashMap<>();
    private static volatile byte[] dataKey;
    private static volatile boolean indexLoaded;
    private static volatile boolean initialized;
    private static volatile boolean wipeInProgress;
    private static volatile long cachedSize = -1;
    private static volatile Boolean wipePendingCache;
    private static volatile boolean cacheUnusable;
    private static volatile boolean bulkQueued;
    static volatile boolean blockBulkForTests;
    static volatile boolean countDeleteTreeForTests;
    static volatile int deleteTreeCalls;
    static volatile boolean skipKeystoreForTests;
    static volatile boolean failKeystoreForTests;
    static volatile boolean failDeleteTreeForTests;
    static volatile boolean failPrefsCommitForTests;
    static volatile boolean throwPrefsCommitForTests;
    static volatile boolean failMarkerFileForTests;
    static volatile boolean failKeyDeleteForTests;
    static volatile boolean failKeyOverwriteForTests;

    private HgramSecretCache() {
    }

    private static Context context() {
        return ApplicationLoader.applicationContext;
    }

    private static File rootDir() {
        final Context context = context();
        return context == null ? null : new File(context.getFilesDir(), ROOT_NAME);
    }

    private static File quarantineDir() {
        final Context context = context();
        return context == null ? null : new File(context.getFilesDir(), QUARANTINE_NAME);
    }

    private static File tempDir() {
        final Context context = context();
        return context == null ? null : new File(context.getNoBackupFilesDir(), TEMP_DIR);
    }

    /**
     * Runs before the first cache read. A durable pending-wipe marker is honoured first, so a
     * process killed during panic-wipe cannot reopen the old key on the next launch.
     */
    public static void onProcessStart() {
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            initialized = true;
        }
        if (isWipePending()) {
            // Main thread does only the cheap, fail-closed part. The tree walk is bulk work.
            cacheUnusable = true;
            synchronized (LOCK) {
                wipeInProgress = true;
                zeroKeyLocked();
            }
            invalidateKeyFileSync();
            startBulkWipe();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            cleanupTempFiles();
            cleanupQuarantine();
        });
    }

    private static void ensureInitialized() {
        if (!initialized) {
            onProcessStart();
        }
    }

    private static android.content.SharedPreferences statePrefs() {
        final Context context = context();
        return context == null ? null
                : context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
    }

    static boolean isWipePending() {
        Boolean cached = wipePendingCache;
        if (cached != null) {
            return cached;
        }
        boolean pending = false;
        final android.content.SharedPreferences prefs = statePrefs();
        if (prefs != null) {
            try {
                pending = prefs.getBoolean(KEY_WIPE_PENDING, false);
            } catch (Throwable t) {
                FileLog.e("HgramSecretCache: cannot read pending-wipe preference");
            }
        }
        pending |= isMarkerPresent(markerFile(rootDir()));
        pending |= isMarkerPartPresent(markerFile(rootDir()));
        pending |= isMarkerPresent(markerFile(fallbackMarkerDir()));
        pending |= isMarkerPartPresent(markerFile(fallbackMarkerDir()));
        for (File marker : emergencyMarkers()) {
            pending |= isMarkerPresent(marker);
            pending |= isMarkerPartPresent(marker);
        }
        pending |= hasWipeTrash();
        wipePendingCache = pending;
        return pending;
    }

    private static File markerFile(File dir) {
        return dir == null ? null : new File(dir, WIPE_MARKER_FILE);
    }

    private static boolean isMarkerPresent(File marker) {
        return marker != null && marker.isFile();
    }

    private static boolean isMarkerPartPresent(File marker) {
        return marker != null && new File(marker.getParentFile(),
                marker.getName() + ".part").isFile();
    }

    private static File fallbackMarkerDir() {
        final Context context = context();
        return context == null ? null : context.getNoBackupFilesDir();
    }

    private static File[] emergencyMarkers() {
        final Context context = context();
        if (context == null) {
            return new File[0];
        }
        final File cacheDir = context.getCacheDir();
        final File wipeDir = context.getDir("wipe", Context.MODE_PRIVATE);
        final File external = context.getExternalFilesDir(null);
        return new File[]{
                cacheDir == null ? null : new File(cacheDir, FALLBACK_WIPE_MARKER_FILE),
                wipeDir == null ? null : new File(wipeDir, FALLBACK_WIPE_MARKER_FILE),
                external == null ? null : new File(external, FALLBACK_WIPE_MARKER_FILE)
        };
    }

    private static File[] wipeTrashDirs() {
        final Context context = context();
        final File files = context == null ? null : context.getFilesDir();
        if (files == null) {
            return new File[0];
        }
        final File[] entries = files.listFiles();
        if (entries == null) {
            return new File[0];
        }
        final java.util.ArrayList<File> result = new java.util.ArrayList<>();
        for (File entry : entries) {
            if (entry.isDirectory() && entry.getName().startsWith(ROOT_NAME + ".wipe.")) {
                result.add(entry);
            }
        }
        return result.toArray(new File[0]);
    }

    private static boolean hasWipeTrash() {
        return wipeTrashDirs().length > 0;
    }

    private static boolean writeEmergencyMarkers() {
        boolean ok = false;
        for (File marker : emergencyMarkers()) {
            ok |= writeMarkerFile(marker);
        }
        return ok;
    }

    private static boolean commitWipeFlag(boolean value) {
        if (failPrefsCommitForTests) {
            return false;
        }
        if (throwPrefsCommitForTests) {
            FileLog.e("HgramSecretCache: pending-wipe preference commit failed");
            return false;
        }
        final android.content.SharedPreferences prefs = statePrefs();
        if (prefs == null) {
            return false;
        }
        try {
            final boolean committed = prefs.edit().putBoolean(KEY_WIPE_PENDING, value).commit();
            if (!committed) {
                FileLog.e("HgramSecretCache: pending-wipe preference commit failed");
            }
            return committed;
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: pending-wipe preference commit threw");
            return false;
        }
    }

    private static void fsyncDir(File dir) {
        if (dir == null) {
            return;
        }
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open(dir.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY, 0);
            android.system.Os.fsync(fd);
        } catch (Throwable t) {
            // Kill-durability comes from rename; fsync is best effort on platforms/filesystems
            // that do not allow opening a directory descriptor.
        } finally {
            if (fd != null) {
                try {
                    android.system.Os.close(fd);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean writeMarkerFile(File marker) {
        if (failMarkerFileForTests || marker == null) {
            return false;
        }
        try {
            final File parent = marker.getParentFile();
            if (parent == null) {
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
            final File part = new File(parent, marker.getName() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(new byte[]{'H', 'S', 'C', 'W', 1});
                out.getFD().sync();
            }
            // POSIX rename replaces the destination atomically; never delete the old marker first.
            if (!part.renameTo(marker)) {
                try (FileOutputStream out = new FileOutputStream(marker)) {
                    out.write(new byte[]{'H', 'S', 'C', 'W', 1});
                    out.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            } else {
                fsyncDir(parent);
            }
            return marker.isFile();
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot write pending-wipe marker file");
            return false;
        }
    }

    private static boolean deleteMarkerFile(File marker) {
        if (failMarkerFileForTests) {
            return false;
        }
        if (marker == null) {
            return true;
        }
        try {
            final File part = new File(marker.getParentFile(), marker.getName() + ".part");
            boolean ok = true;
            if (marker.exists()) {
                ok &= marker.delete() || !marker.exists();
            }
            if (part.exists()) {
                ok &= part.delete() || !part.exists();
            }
            return ok;
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot delete pending-wipe marker file");
            return !marker.exists()
                    && !new File(marker.getParentFile(), marker.getName() + ".part").exists();
        }
    }

    private static int markWipePending() {
        // Fail closed before any marker write can be interrupted.
        cacheUnusable = true;
        wipePendingCache = true;
        synchronized (LOCK) {
            wipeInProgress = true;
            zeroKeyLocked();
        }
        final boolean rootMarker = writeMarkerFile(markerFile(rootDir()));
        final boolean fallbackMarker = writeMarkerFile(markerFile(fallbackMarkerDir()));
        final boolean pref = commitWipeFlag(true);
        // Key invalidation is unconditional: a durable marker may still be lost with the process,
        // and an in-memory flag alone is never a reason to leave the wrapped DEK readable.
        boolean keyInvalidated = invalidateKeyFileSync();
        boolean durable = rootMarker || fallbackMarker || pref;

        if (!durable && !keyInvalidated) {
            final File root = rootDir();
            final boolean rootExisted = root != null && root.exists();
            final boolean renamed = renameRootToWipeTrash();
            if (renamed) {
                durable = true;
                if (!rootExisted || root == null || !root.exists()) {
                    // The old key (if any) moved with the root and is no longer at the readable path.
                    keyInvalidated = true;
                }
            }
        }
        if (!durable) {
            durable = writeEmergencyMarkers();
        }
        // Everything durable is done before this return: callers must not need a second step.
        if (durable && keyInvalidated) {
            return MARK_DURABLE;
        }
        if (keyInvalidated) {
            return MARK_KEY_ONLY;
        }
        return MARK_FAILED;
    }

    private static boolean clearWipePending() {
        final boolean pref = commitWipeFlag(false);
        boolean rootMarker = deleteMarkerFile(markerFile(rootDir()));
        rootMarker &= deleteMarkerFile(markerFile(fallbackMarkerDir()));
        for (File marker : emergencyMarkers()) {
            rootMarker &= deleteMarkerFile(marker);
        }
        final boolean fallbackMarker = true;
        if (pref && rootMarker && fallbackMarker) {
            wipePendingCache = false;
            return true;
        }
        wipePendingCache = true;
        return false;
    }

    private static byte[] dataKey() {
        byte[] cached = dataKey;
        if (cached != null) {
            return cached;
        }
        if (cacheUnusable || wipeInProgress || isWipePending()) {
            return null;
        }
        // Keystore unwrap/generate stays outside the global state lock; only publishing the key
        // and the final wipe check use LOCK.
        synchronized (KEY_LOCK) {
            cached = dataKey;
            if (cached != null) {
                return cached;
            }
            if (cacheUnusable || wipeInProgress || isWipePending()) {
                return null;
            }
            final File root = rootDir();
            if (root == null) {
                return null;
            }
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
            final File keyFile = new File(root, KEY_FILE);
            if (keyFile.isFile()) {
                try {
                    final byte[] loaded = unwrapKey(readAll(keyFile, 4096));
                    return publishDataKey(loaded);
                } catch (Throwable t) {
                    FileLog.e("HgramSecretCache: wrapped key is unusable, wiping cache");
                    scheduleWipeLocked();
                    return null;
                }
            }
            try {
                final byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                final byte[] wrapped = wrapKey(key);
                final File part = new File(root, KEY_FILE + ".part");
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(wrapped);
                    out.getFD().sync();
                }
                if (!part.renameTo(keyFile)) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw new IOException("cannot persist wrapped key");
                }
                return publishDataKey(key);
            } catch (Throwable t) {
                FileLog.e("HgramSecretCache: cannot create key");
                return null;
            }
        }
    }

    private static byte[] publishDataKey(byte[] key) {
        synchronized (LOCK) {
            if (cacheUnusable || wipeInProgress || isWipePending()) {
                Arrays.fill(key, (byte) 0);
                return null;
            }
            dataKey = key;
            return key;
        }
    }

    private static SecretKey keystoreKey() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            final KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    private static byte[] wrapKey(byte[] key) throws Exception {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
        final byte[] iv = cipher.getIV();
        final byte[] wrapped = cipher.doFinal(key);
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(1);
            out.writeByte(iv.length);
            out.write(iv);
            out.write(wrapped);
        }
        return buffer.toByteArray();
    }

    private static byte[] unwrapKey(byte[] blob) throws Exception {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(blob))) {
            final int version = in.readUnsignedByte();
            if (version != 1) {
                throw new IOException("unsupported key version");
            }
            final int ivLength = in.readUnsignedByte();
            if (ivLength <= 0 || ivLength > 32) {
                throw new IOException("bad iv");
            }
            final byte[] iv = new byte[ivLength];
            in.readFully(iv);
            final byte[] wrapped = new byte[in.available()];
            in.readFully(wrapped);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(wrapped);
        }
    }

    /**
     * Encrypts a cache file of a hidden dialog and removes the plaintext original.
     * Returns a readable temporary file for the download delegate, an empty file when the
     * operation failed (fail-closed), or the original file for ordinary dialogs.
     */
    public static File encryptHiddenFile(int account, File file, long dialogId) {
        return encryptHiddenFile(account, file, dialogId, true);
    }

    /** Migration variant: encrypts without producing a decrypted temporary copy. */
    static boolean encryptHiddenFileOnly(int account, File file, long dialogId) {
        return encryptHiddenFile(account, file, dialogId, false) != null;
    }

    private static File encryptHiddenFile(int account, File file, long dialogId, boolean wantReadable) {
        if (file == null || !file.isFile() || file.length() == 0) {
            return file;
        }
        if (!HgramSecretSpace.isHidden(account, dialogId)) {
            return file;
        }
        ensureInitialized();
        if (cacheUnusable) {
            quarantine(file);
            return wantReadable ? unavailable() : null;
        }
        final byte[] key = dataKey();
        final File root = rootDir();
        if (key == null || root == null) {
            quarantine(file);
            return wantReadable ? unavailable() : null;
        }

        final String path = file.getAbsolutePath();
        String blob = null;
        File target = null;
        boolean existedBefore = false;
        boolean mappingExistedBefore = false;
        boolean committed = false;
        try {
            ensureIndexLoaded(key);
            if (wipeInProgress) {
                quarantine(file);
                return wantReadable ? unavailable() : null;
            }
            synchronized (LOCK) {
                blob = pathToBlob.get(path);
                mappingExistedBefore = blob != null;
                if (blob == null) {
                    blob = blobName(path, key);
                }
            }
            target = new File(root, blob);
            existedBefore = target.isFile() && target.length() > 0;
            if (!existedBefore) {
                final File part = new File(root, blob + ".part");
                try {
                    encryptFile(file, part, key, blob);
                } catch (Throwable t) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw t;
                }
                synchronized (LOCK) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                if (!part.renameTo(target)) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw new IOException("cannot publish encrypted blob");
                }
            }

            synchronized (LOCK) {
                pathToBlob.put(path, blob);
            }
            boolean saved = saveIndex(key);
            if (!saved) {
                // Another writer may already have published this path while our write failed.
                saved = confirmPublishedOrRollback(
                        key, path, blob, existedBefore, mappingExistedBefore, target);
            }
            if (!saved) {
                quarantine(file);
                return wantReadable ? unavailable() : null;
            }
            committed = true;
            invalidateSize();
            if (!file.delete() && file.exists()) {
                FileLog.e("HgramSecretCache: cannot remove plaintext after commit");
                final File retry = file;
                Utilities.globalQueue.postRunnable(() -> {
                    if (!retry.delete() && retry.exists()) {
                        FileLog.e("HgramSecretCache: plaintext retry failed");
                    }
                }, 10_000L);
            }
            if (!wantReadable) {
                return file;
            }
            final File readable = resolveReadableBlocking(file);
            return readable == null ? unavailable() : readable;
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot encrypt cached file");
            if (!committed) {
                confirmPublishedOrRollback(
                        key, path, blob, existedBefore, mappingExistedBefore, target);
            }
            quarantine(file);
            return wantReadable ? unavailable() : null;
        }
    }

    private static void dropBrokenEntry(String path, String blob, byte[] key) {
        synchronized (LOCK) {
            pathToBlob.remove(path);
        }
        // Publish the removal first: if the index write fails, the blob is still present and
        // the stale mapping remains consistent instead of becoming a dangling entry.
        saveIndex(key);
        final File root = rootDir();
        if (root != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(root, blob).delete();
        }
    }

    static boolean confirmPublishedForTest(byte[] key, String path, String blob,
                                           boolean existedBefore, boolean mappingExistedBefore,
                                           File target) {
        return confirmPublishedOrRollback(key, path, blob, existedBefore, mappingExistedBefore, target);
    }

    static void dropBrokenEntryForTest(String path, String blob, byte[] key) {
        dropBrokenEntry(path, blob, key);
    }

    static void putMappingForTest(String path, String blob) {
        synchronized (LOCK) {
            pathToBlob.put(path, blob);
            indexLoaded = true;
        }
    }

    static boolean hasMappingForTest(String path) {
        synchronized (LOCK) {
            return pathToBlob.containsKey(path);
        }
    }

    /**
     * After a failed index commit, keeps a blob that the on-disk index already references.
     * Otherwise rolls back only this call's mapping and (only for a newly created blob) the file.
     * Must run under INDEX_LOCK so no concurrent writer can publish the path between the check
     * and the rollback.
     */
    private static boolean confirmPublishedOrRollback(byte[] key, String path, String blob,
                                                      boolean existedBefore, boolean mappingExistedBefore,
                                                      File target) {
        if (blob == null) {
            return false;
        }
        synchronized (INDEX_LOCK) {
            if (indexOnDiskContainsLocked(key, path, blob)) {
                return true;
            }
            if (!mappingExistedBefore) {
                synchronized (LOCK) {
                    if (blob.equals(pathToBlob.get(path))) {
                        pathToBlob.remove(path);
                    }
                }
            }
            if (!existedBefore && target != null && target.exists() && !target.delete()) {
                FileLog.e("HgramSecretCache: cannot remove uncommitted blob");
            }
            return false;
        }
    }

    private static boolean indexOnDiskContainsLocked(byte[] key, String path, String blob) {
        final File root = rootDir();
        final File index = root == null ? null : new File(root, INDEX_FILE);
        if (index == null || !index.isFile()) {
            return false;
        }
        try {
            final ByteArrayOutputStream plain = new ByteArrayOutputStream();
            decryptFile(index, plain, key, INDEX_IDENTITY);
            final String encoded = android.util.Base64.encodeToString(
                    path.getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                            | android.util.Base64.NO_WRAP);
            final String row = encoded + "\t" + blob;
            for (String line : new String(plain.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                if (row.equals(line)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot verify published index entry");
        }
        return false;
    }

    /**
     * Non-blocking lookup used by {@code FileLoader.getPathToAttach}. Small blobs are decrypted
     * synchronously; large ones are prepared in the background and the caller gets a missing path
     * until the temporary file is ready, which matches the normal "file not ready" contract.
     */
    public static File resolveReadable(File file) {
        if (file == null) {
            return null;
        }
        ensureInitialized();
        if (cacheUnusable) {
            return unavailable();
        }
        final String path = file.getAbsolutePath();
        if (path.isEmpty()) {
            return file;
        }
        final File root = rootDir();
        if (root == null || !new File(root, INDEX_FILE).isFile() || wipeInProgress) {
            return file;
        }
        final byte[] key = dataKey();
        if (key == null) {
            return unavailable();
        }
        String blob;
        ensureIndexLoaded(key);
        if (wipeInProgress) {
            return unavailable();
        }
        synchronized (LOCK) {
            blob = pathToBlob.get(path);
            if (blob == null) {
                return file;
            }
            final File cachedTemp = tempFiles.get(path);
            if (cachedTemp != null && cachedTemp.isFile() && cachedTemp.length() > 0) {
                return cachedTemp;
            }
            if (decrypting.containsKey(path)) {
                return unavailable();
            }
        }
        final File blobFile = new File(root, blob);
        if (blobFile.length() > 0 && blobFile.length() <= SMALL_SYNC_DECRYPT_BYTES
                && Looper.myLooper() == Looper.getMainLooper()) {
            final File readable = resolveReadableBlocking(file);
            return readable == null ? unavailable() : readable;
        }
        startDecrypt(path, blob, key);
        return unavailable();
    }

    private static File resolveReadableBlocking(File file) {
        final String path = file.getAbsolutePath();
        final File root = rootDir();
        if (root == null) {
            return null;
        }
        final byte[] key = dataKey();
        if (key == null) {
            return null;
        }
        String blob;
        CountDownLatch latch;
        boolean owner = false;
        ensureIndexLoaded(key);
        if (wipeInProgress) {
            return null;
        }
        synchronized (LOCK) {
            blob = pathToBlob.get(path);
            if (blob == null) {
                return null;
            }
            File cached = tempFiles.get(path);
            if (cached != null && cached.isFile() && cached.length() > 0) {
                return cached;
            }
            latch = decrypting.get(path);
            if (latch == null) {
                latch = new CountDownLatch(1);
                decrypting.put(path, latch);
                owner = true;
            }
        }
        if (owner) {
            decryptInto(path, blob, key, latch);
        } else {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        synchronized (LOCK) {
            return tempFiles.get(path);
        }
    }

    private static void startDecrypt(String path, String blob, byte[] key) {
        final CountDownLatch latch;
        synchronized (LOCK) {
            if (decrypting.containsKey(path)) {
                return;
            }
            latch = new CountDownLatch(1);
            decrypting.put(path, latch);
        }
        Utilities.globalQueue.postRunnable(() -> decryptInto(path, blob, key, latch));
    }

    private static void decryptInto(String path, String blob, byte[] key, CountDownLatch latch) {
        File out = null;
        try {
            final File root = rootDir();
            final File dir = tempDir();
            if (root == null || dir == null) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            out = new File(dir, tempFileName(path, blob));
            decryptFile(new File(root, blob), out, key, blob);
            synchronized (LOCK) {
                tempFiles.put(path, out);
            }
            invalidateSize();
            final File scheduled = out;
            Utilities.globalQueue.postRunnable(() -> {
                synchronized (LOCK) {
                    if (tempFiles.get(path) == scheduled) {
                        tempFiles.remove(path);
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                scheduled.delete();
            }, TEMP_TTL_MS);
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot decrypt cached file");
            if (out != null) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            dropBrokenEntry(path, blob, key);
        } finally {
            synchronized (LOCK) {
                decrypting.remove(path);
            }
            latch.countDown();
        }
    }

    /**
     * Moves a hidden plaintext that could not be encrypted out of the normal cache path.
     * Reads of the original path become unavailable; recovery/redownload is required.
     */
    private static void quarantine(File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        final File dir = quarantineDir();
        if (dir == null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final File target = new File(dir, sha256Name(file.getAbsolutePath())
                + "-" + System.currentTimeMillis());
        try {
            if (!file.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot quarantine cached file");
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * Background migration of files cached before the dialog moved into the second space.
     * Skips active downloads and temporary files.
     */
    public static void migrateHiddenDialogs(int account) {
        final List<Long> hidden = HgramSecretSpace.hiddenList(account);
        for (int i = 0; i < hidden.size(); i++) {
            migrateHiddenDialog(account, hidden.get(i));
        }
    }

    public static void migrateHiddenDialog(int account, long dialogId) {
        int failures = 0;
        try {
            final ArrayList<File> files = FileLoader.getInstance(account)
                    .getFileDatabase().getFilesForDialog(dialogId);
            for (int i = 0; i < files.size(); i++) {
                final File file = files.get(i);
                if (file == null || !file.isFile()) {
                    continue;
                }
                if (!isMigratableFile(file) || FileLoader.getInstance(account).isFileLoading(file)) {
                    continue;
                }
                if (!encryptHiddenFileOnly(account, file, dialogId) && failures++ < 3) {
                    FileLog.e("HgramSecretCache: migration left a file quarantined");
                }
            }
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: migration failed");
        }
    }

    static boolean isMigratableFile(File file) {
        if (file == null) {
            return false;
        }
        final String name = file.getName().toLowerCase();
        return !(name.endsWith(".part") || name.endsWith(".temp")
                || name.endsWith(".preload") || name.endsWith(".tmp"));
    }

    public static long size() {
        ensureInitialized();
        long value = cachedSize;
        if (value >= 0) {
            return value;
        }
        value = directorySize(rootDir());
        cachedSize = value;
        return value;
    }

    private static void invalidateSize() {
        cachedSize = -1;
    }

    private static long directorySize(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        long total = 0;
        final File[] entries = dir.listFiles();
        if (entries == null) {
            return 0;
        }
        for (File entry : entries) {
            total += entry.isDirectory() ? directorySize(entry) : entry.length();
        }
        return total;
    }

    public static void cleanupTempFiles() {
        final File dir;
        synchronized (LOCK) {
            if (!decrypting.isEmpty()) {
                // Let an active decoder finish; the per-file TTL will remove its temp.
                return;
            }
            tempFiles.clear();
            dir = tempDir();
        }
        deleteTree(dir);
    }

    /**
     * Starts an asynchronous, idempotent wipe. The pending marker is committed before returning;
     * even if the process dies before the deletion task runs, the next start wipes first.
     */
    public static void wipe() {
        markWipePending();
        cacheUnusable = true;
        invalidateLocked();
        startBulkWipe();
    }

    /** Synchronous variant for callers that already run on a background queue. */
    public static void clear() {
        markWipePending();
        cacheUnusable = true;
        invalidateLocked();
        startBulkWipe();
    }

    private static void invalidateLocked() {
        synchronized (LOCK) {
            wipeInProgress = true;
            zeroKeyLocked();
            indexLoaded = false;
            pathToBlob.clear();
            tempFiles.clear();
            releaseDecryptWaitersLocked();
            cachedSize = -1;
        }
    }

    private static void startBulkWipe() {
        synchronized (LOCK) {
            if (bulkQueued) {
                return;
            }
            bulkQueued = true;
        }
        if (blockBulkForTests) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (LOCK) {
                bulkQueued = false;
            }
            performWipeBlocking();
        });
    }

    private static boolean renameRootToWipeTrash() {
        final File root = rootDir();
        if (root == null || !root.exists()) {
            return true;
        }
        if (hasWipeTrash()) {
            // A trash directory is already a durable wipe signal; never accumulate more copies.
            return true;
        }
        try {
            final File trash = new File(root.getParentFile(),
                    root.getName() + ".wipe." + System.currentTimeMillis());
            if (root.renameTo(trash)) {
                return true;
            }
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot rename cache root for wipe");
        }
        return !root.exists();
    }

    private static boolean performWipeBlocking() {
        synchronized (WIPE_LOCK) {
            invalidateLocked();
            boolean ok;
            synchronized (INDEX_LOCK) {
                ok = deleteTree(rootDir());
                ok &= deleteTree(tempDir());
                ok &= deleteTree(quarantineDir());
                for (File trash : wipeTrashDirs()) {
                    ok &= deleteTree(trash);
                }
                ok &= deleteKeystoreAlias();
                ok &= cleanupInvalidKeys();
                if (ok) {
                    ok = clearWipePending();
                } else {
                    // Never lose the durable marker on a partial wipe; the next start retries.
                    markWipePending();
                    wipePendingCache = true;
                }
            }
            synchronized (LOCK) {
                wipeInProgress = false;
                bulkQueued = false;
            }
            cacheUnusable = !ok;
            return ok;
        }
    }

    private static boolean invalidateKeyFileSync() {
        final File root = rootDir();
        if (root == null) {
            return false;
        }
        final boolean keyGone = invalidateKeyFile(new File(root, KEY_FILE));
        final boolean partGone = invalidateKeyFile(new File(root, KEY_FILE + ".part"));
        if (!keyGone || !partGone) {
            FileLog.e("HgramSecretCache: cannot fully invalidate key file");
        }
        return keyGone && partGone;
    }

    private static boolean invalidateKeyFile(File file) {
        if (file == null) {
            return true;
        }
        try {
            if (!file.exists()) {
                return true;
            }
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot inspect key file");
            return false;
        }

        boolean overwritten = false;
        try {
            if (file.isFile()) {
                if (failKeyOverwriteForTests) {
                    throw new IOException("test overwrite failure");
                }
                final byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(random);
                    out.getFD().sync();
                }
                overwritten = true;
            }
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot overwrite key file");
        }

        if (!failKeyDeleteForTests) {
            try {
                if (file.delete() || !file.exists()) {
                    return true;
                }
            } catch (Throwable t) {
                FileLog.e("HgramSecretCache: cannot delete key file");
            }
        }

        // A rename is allowed only after the key material was actually destroyed. Moving a
        // still-valid wrapped key to key.invalid.* would keep it copyable until the queued wipe.
        if (!overwritten) {
            return false;
        }
        File invalid = null;
        try {
            final File invalidDir = fallbackMarkerDir() != null
                    ? fallbackMarkerDir() : file.getParentFile();
            //noinspection ResultOfMethodCallIgnored
            invalidDir.mkdirs();
            invalid = new File(invalidDir,
                    file.getName() + ".invalid." + System.currentTimeMillis());
            if (file.renameTo(invalid) || !file.exists()) {
                if (invalid == null || !invalid.exists()) {
                    return true;
                }
                try {
                    if (invalid.delete() || !invalid.exists()) {
                        return true;
                    }
                } catch (Throwable t) {
                    FileLog.e("HgramSecretCache: cannot delete invalidated key file");
                }
            }
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot quarantine key file");
        }
        return false;
    }

    private static void cleanupQuarantine() {
        final File dir = quarantineDir();
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        final long cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L;
        final File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isFile() && entry.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                entry.delete();
            }
        }
    }


    static void resetForTests() {
        skipKeystoreForTests = true;
        failKeystoreForTests = false;
        failDeleteTreeForTests = false;
        failPrefsCommitForTests = false;
        throwPrefsCommitForTests = false;
        failMarkerFileForTests = false;
        failKeyDeleteForTests = false;
        failKeyOverwriteForTests = false;
        blockBulkForTests = false;
        countDeleteTreeForTests = false;
        deleteTreeCalls = 0;
        bulkQueued = false;
        cacheUnusable = false;
        synchronized (LOCK) {
            wipeInProgress = false;
            zeroKeyLocked();
            indexLoaded = false;
            initialized = false;
            pathToBlob.clear();
            tempFiles.clear();
            decrypting.clear();
            cachedSize = -1;
            wipePendingCache = null;
        }
        deleteTree(rootDir());
        deleteTree(tempDir());
        deleteTree(quarantineDir());
        for (File trash : wipeTrashDirs()) {
            deleteTree(trash);
        }
        deleteKeystoreAlias();
        cleanupInvalidKeys();
        clearWipePending();
    }

    static int markWipePendingForTest() {
        return markWipePending();
    }

    static boolean performWipeBlockingForTest() {
        return performWipeBlocking();
    }

    static boolean hasWipeTrashForTest() {
        return hasWipeTrash();
    }

    static void resetDeleteTreeCounterForTest() {
        deleteTreeCalls = 0;
    }

    static boolean isCacheUnusableForTest() {
        return cacheUnusable;
    }

    static boolean hasDataKeyForTest() {
        synchronized (LOCK) {
            return dataKey != null;
        }
    }

    static void resetPendingCacheForTest() {
        wipePendingCache = null;
    }

    static boolean hasFileMarkerForTest() {
        return isMarkerPresent(markerFile(rootDir()))
                || isMarkerPresent(markerFile(fallbackMarkerDir()));
    }

    static boolean invalidateKeyFileSyncForTest() {
        return invalidateKeyFileSync();
    }

    private static void zeroKeyLocked() {
        if (dataKey != null) {
            Arrays.fill(dataKey, (byte) 0);
            dataKey = null;
        }
    }

    private static void releaseDecryptWaitersLocked() {
        for (CountDownLatch latch : decrypting.values()) {
            latch.countDown();
        }
        decrypting.clear();
    }

    private static void scheduleWipeLocked() {
        if (wipeInProgress) {
            return;
        }
        invalidateLocked();
        Utilities.globalQueue.postRunnable(() -> {
            markWipePending();
            performWipeBlocking();
        });
    }

    private static boolean cleanupInvalidKeys() {
        final File dir = fallbackMarkerDir();
        if (dir == null || !dir.isDirectory()) {
            return true;
        }
        final File[] entries = dir.listFiles();
        if (entries == null) {
            return true;
        }
        boolean ok = true;
        for (File entry : entries) {
            final String name = entry.getName();
            if (name.startsWith(KEY_FILE + ".invalid.")
                    || name.startsWith(KEY_FILE + ".part.invalid.")) {
                try {
                    ok &= entry.delete() || !entry.exists();
                } catch (Throwable t) {
                    ok = false;
                }
            }
        }
        return ok;
    }

    private static boolean deleteKeystoreAlias() {
        if (failKeystoreForTests) {
            return false;
        }
        if (skipKeystoreForTests) {
            return true;
        }
        try {
            final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
            return !keyStore.containsAlias(KEY_ALIAS);
        } catch (Throwable t) {
            FileLog.e("HgramSecretCache: cannot delete Keystore alias");
            return false;
        }
    }

    static boolean deleteTree(File file) {
        if (countDeleteTreeForTests) {
            deleteTreeCalls++;
        }
        if (failDeleteTreeForTests) {
            return false;
        }
        if (file == null || !file.exists()) {
            return true;
        }
        boolean ok = true;
        if (file.isDirectory()) {
            final File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    ok &= deleteTree(entry);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            ok = false;
        }
        return ok;
    }

    private static String tempFileName(String path, String blob) {
        String extension = "tmp";
        final String name = new File(path).getName();
        final int dot = name.lastIndexOf('.');
        if (dot > 0 && dot + 1 < name.length()) {
            final String candidate = name.substring(dot + 1);
            if (candidate.matches("[A-Za-z0-9]{1,8}")) {
                extension = candidate;
            }
        }
        return blob + "." + extension;
    }

    private static String blobName(String path, byte[] key) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return hex(mac.doFinal(path.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Name(String path) {
        try {
            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "recovery";
        }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder name = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            name.append(Character.forDigit((b >> 4) & 0xf, 16));
            name.append(Character.forDigit(b & 0xf, 16));
        }
        return name.toString();
    }

    private static File unavailable() {
        final File dir = tempDir();
        if (dir != null) {
            return new File(dir, "unavailable");
        }
        final Context context = context();
        return context == null ? new File("/dev/null")
                : new File(context.getFilesDir(), "hgram_cache_unavailable");
    }

    private static void ensureIndexLoaded(byte[] key) {
        if (indexLoaded) {
            return;
        }
        synchronized (INDEX_LOCK) {
            if (indexLoaded) {
                return;
            }
            final HashMap<String, String> loaded = new HashMap<>();
            boolean corrupted = false;
            final File root = rootDir();
            final File index = root == null ? null : new File(root, INDEX_FILE);
            if (index != null && index.isFile()) {
                try {
                    if (index.length() > MAX_INDEX_BYTES) {
                        throw new IOException("index too large");
                    }
                    final ByteArrayOutputStream plain = new ByteArrayOutputStream();
                    decryptFile(index, plain, key, INDEX_IDENTITY);
                    final String[] lines = new String(plain.toByteArray(), StandardCharsets.UTF_8).split("\n");
                    for (String line : lines) {
                        final int tab = line.indexOf('\t');
                        if (tab <= 0 || tab + 1 >= line.length()) {
                            continue;
                        }
                        final String path = new String(
                                android.util.Base64.decode(line.substring(0, tab),
                                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                                                | android.util.Base64.NO_WRAP),
                                StandardCharsets.UTF_8);
                        final String blob = line.substring(tab + 1);
                        if (!path.isEmpty() && blob.matches("[0-9a-f]{64}")) {
                            loaded.put(path, blob);
                        }
                    }
                } catch (Throwable t) {
                    FileLog.e("HgramSecretCache: encrypted index is unreadable, wiping cache");
                    corrupted = true;
                }
            }
            synchronized (LOCK) {
                if (!indexLoaded) {
                    pathToBlob.putAll(loaded);
                    indexLoaded = true;
                }
            }
            if (corrupted) {
                wipe();
            }
        }
    }

    private static boolean saveIndex(byte[] key) {
        // The whole snapshot + write + fsync + rename cycle is serialized. Content encryption
        // stays outside this lock, so a large media file never blocks another index commit.
        synchronized (INDEX_LOCK) {
            final HashMap<String, String> copy;
            synchronized (LOCK) {
                copy = new HashMap<>(pathToBlob);
            }
            final StringBuilder text = new StringBuilder(copy.size() * 96);
            for (Map.Entry<String, String> entry : copy.entrySet()) {
                final String path = android.util.Base64.encodeToString(
                        entry.getKey().getBytes(StandardCharsets.UTF_8),
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                                | android.util.Base64.NO_WRAP);
                text.append(path).append('\t').append(entry.getValue()).append('\n');
            }
            final byte[] payload = text.toString().getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_INDEX_BYTES - 64 * 1024) {
                return false;
            }
            final File root = rootDir();
            if (root == null) {
                return false;
            }
            return writeIndexAtomicallyLocked(new File(root, INDEX_FILE), payload, key);
        }
    }

    static boolean writeIndexAtomically(File index, byte[] payload, byte[] key) {
        synchronized (INDEX_LOCK) {
            return writeIndexAtomicallyLocked(index, payload, key);
        }
    }

    private static boolean writeIndexAtomicallyLocked(File index, byte[] payload, byte[] key) {
        if (payload.length > MAX_INDEX_BYTES - 64 * 1024) {
            return false;
        }
        final File part = new File(index.getParentFile(), index.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            encryptStream(new java.io.ByteArrayInputStream(payload), out, key, INDEX_IDENTITY,
                    payload.length);
            out.getFD().sync();
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        if (!part.isFile() || part.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        // POSIX rename replaces the destination atomically; the old index is never deleted first.
        if (!part.renameTo(index)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        return true;
    }

    static void encryptFile(File source, File target, byte[] key, String identity) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            encryptStream(in, out, key, identity, source.length());
        }
    }

    static void decryptFile(File source, File target, byte[] key, String identity) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            decryptStream(in, out, key, identity);
        }
    }

    static void decryptFile(File source, ByteArrayOutputStream target, byte[] key, String identity) throws Exception {
        try (FileInputStream in = new FileInputStream(source)) {
            decryptStream(in, target, key, identity);
        }
    }

    private static void encryptStream(java.io.InputStream in, java.io.OutputStream out,
                                      byte[] key, String identity, long totalPlainBytes) throws Exception {
        final DataOutputStream data = new DataOutputStream(out);
        final int chunkCount = (int) Math.max(1, (totalPlainBytes + CHUNK_SIZE - 1) / CHUNK_SIZE);
        data.writeInt(FILE_MAGIC);
        data.writeInt(FILE_VERSION);
        data.writeInt(CHUNK_SIZE);
        data.writeLong(totalPlainBytes);
        data.writeInt(chunkCount);
        final byte[] buffer = new byte[CHUNK_SIZE];
        final SecureRandom random = new SecureRandom();
        long written = 0;
        int index = 0;
        while (index < chunkCount) {
            final int read;
            if (index == chunkCount - 1 && totalPlainBytes == 0) {
                read = 0;
            } else {
                read = in.read(buffer);
                if (read < 0) {
                    throw new IOException("source ended before its declared size");
                }
            }
            written += read;
            final byte[] iv = new byte[IV_SIZE];
            random.nextBytes(iv);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(chunkAad(identity, index, totalPlainBytes, chunkCount, CHUNK_SIZE));
            final byte[] ciphertext = cipher.doFinal(buffer, 0, read);
            data.writeInt(ciphertext.length);
            data.write(iv);
            data.write(ciphertext);
            index++;
        }
        if (written != totalPlainBytes) {
            throw new IOException("source size mismatch");
        }
        data.flush();
    }

    private static void decryptStream(java.io.InputStream in, java.io.OutputStream out,
                                      byte[] key, String identity) throws Exception {
        final DataInputStream data = new DataInputStream(in);
        if (data.readInt() != FILE_MAGIC) {
            throw new IOException("bad cache header");
        }
        final int version = data.readInt();
        if (version != FILE_VERSION) {
            throw new IOException("unsupported cache version");
        }
        final int chunkSize = data.readInt();
        if (chunkSize <= 0 || chunkSize > 4 * 1024 * 1024) {
            throw new IOException("bad chunk size");
        }
        final long totalPlainBytes = data.readLong();
        final int chunkCount = data.readInt();
        if (totalPlainBytes < 0 || chunkCount <= 0
                || (long) chunkCount * chunkSize < totalPlainBytes) {
            throw new IOException("bad cache header");
        }
        long written = 0;
        for (int index = 0; index < chunkCount; index++) {
            final int ciphertextLength = data.readInt();
            if (ciphertextLength <= 0 || ciphertextLength > chunkSize + 64) {
                throw new IOException("bad chunk length");
            }
            final byte[] iv = new byte[IV_SIZE];
            data.readFully(iv);
            final byte[] ciphertext = new byte[ciphertextLength];
            data.readFully(ciphertext);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(chunkAad(identity, index, totalPlainBytes, chunkCount, chunkSize));
            final byte[] plain = cipher.doFinal(ciphertext);
            out.write(plain);
            written += plain.length;
        }
        if (data.read() != -1) {
            throw new IOException("trailing data after the last chunk");
        }
        if (written != totalPlainBytes) {
            throw new IOException("decrypted size mismatch");
        }
        out.flush();
    }

    private static byte[] chunkAad(String identity, int index, long totalPlainBytes,
                                   int chunkCount, int chunkSize) {
        final byte[] id = identity.getBytes(StandardCharsets.UTF_8);
        final byte[] aad = new byte[AAD_PREFIX.length() + id.length + 8 + 4 + 4 + 4];
        int offset = 0;
        for (int i = 0; i < AAD_PREFIX.length(); i++) {
            aad[offset++] = (byte) AAD_PREFIX.charAt(i);
        }
        System.arraycopy(id, 0, aad, offset, id.length);
        offset += id.length;
        for (int shift = 56; shift >= 0; shift -= 8) {
            aad[offset++] = (byte) (totalPlainBytes >>> shift);
        }
        for (int shift = 24; shift >= 0; shift -= 8) {
            aad[offset++] = (byte) (chunkCount >>> shift);
        }
        for (int shift = 24; shift >= 0; shift -= 8) {
            aad[offset++] = (byte) (chunkSize >>> shift);
        }
        for (int shift = 24; shift >= 0; shift -= 8) {
            aad[offset++] = (byte) (index >>> shift);
        }
        return aad;
    }

    private static byte[] readAll(File file, long max) throws IOException {
        if (file.length() > max) {
            throw new IOException("file too large");
        }
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
}
