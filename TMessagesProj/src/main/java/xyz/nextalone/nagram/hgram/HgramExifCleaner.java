package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.FileLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure naming/policy helpers for send-as-file metadata cleanup. */
public final class HgramExifCleaner {

    /** Stream buffer for every copy/skip operation; the send path never reads a whole file. */
    private static final int BUFFER_SIZE = 64 * 1024;

    private static final String CACHE_DIR_NAME = "hgram_exif";

    /** Cache subdirectories currently being written; sweep must never touch them. */
    private static final Set<String> IN_FLIGHT =
            Collections.synchronizedSet(new HashSet<String>());

    /** Prepared artifacts still referenced by a queued/retried send; sweep must keep them. */
    private static final Set<String> LEASED =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * Typed ownership record for a prepared copy: which account/dialog/message points at it and
     * which typed sent-file slot it represents. A provisional record ({@code messageId == 0}) is
     * registered while the copy is being prepared and replaced by the real message id as soon as
     * the message exists.
     */
    public static final class Owner {
        public final int account;
        public final long dialogId;
        public final int messageId;
        public final String slot;

        Owner(int account, long dialogId, int messageId, String slot) {
            this.account = account;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.slot = slot;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Owner)) {
                return false;
            }
            Owner other = (Owner) o;
            return account == other.account && dialogId == other.dialogId
                    && messageId == other.messageId && Objects.equals(slot, other.slot);
        }

        @Override
        public int hashCode() {
            int result = account;
            result = 31 * result + (int) (dialogId ^ (dialogId >>> 32));
            result = 31 * result + messageId;
            result = 31 * result + (slot != null ? slot.hashCode() : 0);
            return result;
        }
    }

    /** Canonical prepared path to the set of messages/slots that still reference it. */
    private static final Map<String, Set<Owner>> OWNERS = new HashMap<>();

    /**
     * Serializes lease mutations, prepared-artifact deletion and sweeps. All accounts share
     * {@code MEDIA_DIR_CACHE}, so one account's sweep must not interleave with another account's
     * pin pass (or with a terminal discard releasing/deleting the same artifact).
     */
    private static final Object LEASE_LOCK = new Object();

    /** Accounts that already pinned their restored pending sends in this process. */
    private static final Set<Integer> PIN_READY_ACCOUNTS = new HashSet<>();

    /** Accounts that must pin before the shared cache may be swept; only grows while activated. */
    private static int pinRequiredAccounts;

    /** Sent-file cache namespace for prepared documents. */
    private static final String CACHE_KEY_PREFIX = "#hgram_exif:";

    public enum PrepareResult {
        CLEANED,
        COPIED,
        PARSE_FAILED,
        FAILED
    }

    public static final class Prepared {
        public final File file;
        public final PrepareResult result;

        public Prepared(File file, PrepareResult result) {
            this.file = file;
            this.result = result;
        }
    }

    /**
     * The attach path keeps a reference until the message is queued, but that queue/retry
     * state is not visible from here. A 72h window keeps prepared copies alive for delayed
     * sends and retries while still bounding cache growth.
     */
    public static final long SWEEP_MAX_AGE_MS = 72L * 60L * 60L * 1000L;

    /**
     * Automatic cache sweeps are disabled. Restored queue state is incomplete in both directions:
     * {@code MessagesStorage} restores only send states 1 and 3 and caps the pending list at 1000
     * rows, so a sweep running at startup cannot see every prepared copy that is still needed for
     * a retry. Prepared files are therefore cleared only by an explicit user action in settings
     * ({@link #sweepPreparedIfEnabled}).
     */
    public static final boolean AUTO_SWEEP_ENABLED = false;

    /** Manual sweep result: metadata removal is disabled (legacy OFF). */
    public static final int SWEEP_DISABLED = -2;

    /** Manual sweep result: not every activated account has pinned its pending sends yet. */
    public static final int SWEEP_NOT_READY = -1;

    private HgramExifCleaner() {
    }

    public static boolean shouldClean(boolean sendAsFile, boolean compressedPhoto) {
        return sendAsFile && !compressedPhoto;
    }

    public static boolean shouldRewriteVideoName(boolean sendAsFile, boolean video) {
        return sendAsFile && video;
    }

    public static String neutralName(String originalName, long timestamp, boolean image) {
        String extension = extension(originalName);
        String prefix = image ? "IMG_" : "FILE_";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(timestamp));
        return prefix + stamp + (extension.isEmpty() ? "" : "." + extension);
    }

    /**
     * Legacy string-marker key, kept only for compatibility. Production lookups use the typed
     * slot from {@link #sentFileType(int, PrepareResult)} so a raw path shaped like this marker
     * can never alias a prepared document.
     *
     * @deprecated use {@link #sentFileType(int, PrepareResult)}
     */
    @Deprecated
    public static String cacheKey(String originalPath, PrepareResult result) {
        if (originalPath == null) {
            return null;
        }
        if (result == PrepareResult.CLEANED) {
            return originalPath + CACHE_KEY_PREFIX + "cleaned";
        }
        if (result == PrepareResult.COPIED) {
            return originalPath + CACHE_KEY_PREFIX + "copied";
        }
        return originalPath;
    }

    /** Compatibility delegate for callers that only know whether cleanup happened. */
    public static String cacheKey(String originalPath, boolean cleaned) {
        return cacheKey(originalPath, cleaned ? PrepareResult.CLEANED : PrepareResult.PARSE_FAILED);
    }

    /**
     * Prepared documents use a separate typed sent-file slot: the base type is offset by
     * {@link #PREPARED_SENT_FILE_TYPE_OFFSET}, so the raw path string and size can never collide
     * with a prepared copy stored for the same source. Failed or disabled prepares stay in the
     * legacy raw slot.
     */
    public static final int PREPARED_SENT_FILE_TYPE_OFFSET = 1000;

    public static int sentFileType(int baseType, PrepareResult result) {
        return result == PrepareResult.CLEANED || result == PrepareResult.COPIED
                ? baseType + PREPARED_SENT_FILE_TYPE_OFFSET : baseType;
    }

    /**
     * One slot for both the probe and the ACK/put: the base type is widened to the video
     * namespace for GIF-like containers and offset by {@link #PREPARED_SENT_FILE_TYPE_OFFSET}
     * only for a prepared copy. Keeping the exact slot in {@code params} prevents the send path
     * from guessing the type again from {@code isVideo}/{@code isGif} after the ACK.
     */
    public static int sentFileType(int baseType, boolean gifLike, boolean prepared) {
        return baseType + (gifLike ? 1 : 0) + (prepared ? PREPARED_SENT_FILE_TYPE_OFFSET : 0);
    }

    /** GIF-like containers are stored in the video sent-file namespace by the ACK path. */
    public static boolean isGifLikeExtension(String extensionOrPath) {
        if (extensionOrPath == null) {
            return false;
        }
        String value = extensionOrPath;
        int dot = value.lastIndexOf('.');
        if (dot >= 0) {
            value = value.substring(dot + 1);
        } else if (value.startsWith(".")) {
            value = value.substring(1);
        }
        value = value.toLowerCase(Locale.US);
        return "gif".equals(value) || "mp4".equals(value) || "mov".equals(value)
                || "m4v".equals(value) || "webm".equals(value);
    }

    /** Slot identity: typed namespace plus the untouched original path (test/verification use). */
    public static String sentFileSlotKey(String originalPath, int baseType, PrepareResult result) {
        return sentFileType(baseType, result) + "|" + originalPath;
    }

    /**
     * Keeps a prepared attach path alive across sweep. Used when a pending send is restored from
     * storage after a restart: the original prepare call is gone, but the file is still
     * referenced. Paths outside {@code .../hgram_exif/<uuid>/<file>} are ignored.
     */
    public static void pinAttachPath(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return;
            }
            File file = new File(path);
            File parent = file.getAbsoluteFile().getParentFile();
            File root = parent == null ? null : parent.getParentFile();
            if (root == null || !CACHE_DIR_NAME.equals(root.getName())
                    || !isUuidName(parent.getName())) {
                return;
            }
            synchronized (LEASE_LOCK) {
                LEASED.add(absolutePath(file));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Drops the lease once the send finished, failed or was cancelled. */
    public static void releaseAttachPath(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return;
            }
            String key = absolutePath(new File(path));
            synchronized (LEASE_LOCK) {
                LEASED.remove(key);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Binds a prepared copy to the message that owns it. The path must already be leased and must
     * be a real artifact ({@code .../hgram_exif/<uuid>/<file>}); anything else is ignored.
     * Idempotent: owners are kept in a set keyed by account + dialog + message + slot, so two
     * messages that legitimately share a prepared path do not evict each other. A provisional
     * owner ({@code messageId == 0}) never replaces a real owner; a real owner upgrades (removes)
     * the provisional record for the same hand-off. Never throws.
     */
    public static void registerOwner(String path, int account, long dialogId, int messageId, String slot) {
        try {
            if (path == null || path.isEmpty()) {
                return;
            }
            File file = new File(path);
            if (!isPreparedArtifactPath(file)) {
                return;
            }
            String key = absolutePath(file);
            Owner owner = new Owner(account, dialogId, messageId, slot);
            synchronized (LEASE_LOCK) {
                if (!LEASED.contains(key)) {
                    return;
                }
                Set<Owner> owners = OWNERS.get(key);
                if (owners == null) {
                    owners = new HashSet<>();
                    OWNERS.put(key, owners);
                }
                if (messageId == 0) {
                    for (Owner existing : owners) {
                        if (existing.messageId != 0) {
                            // The real message already claimed this copy; keep it.
                            LEASED.add(key);
                            return;
                        }
                    }
                } else {
                    // The real local id replaces only this hand-off's provisional record.
                    owners.removeIf(candidate -> candidate.messageId == 0
                            && candidate.account == account
                            && candidate.dialogId == dialogId
                            && Objects.equals(candidate.slot, slot));
                }
                owners.add(owner);
                LEASED.add(key);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Releases every owner of {@code path} and its lease. With {@code deleteCopy} the file and its
     * empty UUID directory are removed too, but only for our own neutral artifact when no owner,
     * lease or in-flight prepare still references the path or a sibling. Idempotent, fail-open.
     */
    public static void releaseOwner(String path, boolean deleteCopy) {
        releaseOwnerInternal(path, null, deleteCopy);
    }

    /**
     * Address-targeted release: removes only the owner with the full identity
     * account + dialogId + messageId + slot. Other owners of the same path keep the file alive.
     */
    public static void releaseOwner(String path, int account, long dialogId, int messageId,
                                    String slot, boolean deleteCopy) {
        releaseOwnerInternal(path, new Owner(account, dialogId, messageId, slot), deleteCopy);
    }

    private static void releaseOwnerInternal(String path, Owner owner, boolean deleteCopy) {
        try {
            if (path == null || path.isEmpty()) {
                return;
            }
            File file = new File(path);
            synchronized (LEASE_LOCK) {
                String key = absolutePath(file);
                Set<Owner> owners = OWNERS.get(key);
                if (owner != null) {
                    if (owners == null) {
                        // Unknown targeted owner: never delete a path another pin may need.
                        return;
                    }
                    owners.remove(owner);
                    if (!owners.isEmpty()) {
                        LEASED.add(key);
                        return;
                    }
                }
                OWNERS.remove(key);
                LEASED.remove(key);
                deleteArtifactLocked(file, deleteCopy);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Drops the prepare-time provisional owner after a hand-off was rejected before the message
     * existed. A real owner of the same path is kept; when only provisional records existed the
     * copy and its UUID directory are removed. Fail-open and idempotent.
     */
    public static void releaseProvisional(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return;
            }
            File file = new File(path);
            synchronized (LEASE_LOCK) {
                String key = absolutePath(file);
                Set<Owner> owners = OWNERS.get(key);
                if (owners != null) {
                    owners.removeIf(candidate -> candidate.messageId == 0);
                    if (!owners.isEmpty()) {
                        LEASED.add(key);
                        return;
                    }
                    OWNERS.remove(key);
                }
                LEASED.remove(key);
                deleteArtifactLocked(file, true);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Releases every owner matching {@code account + dialogId + messageId}. Only real message ids
     * are considered ({@code messageId != 0}); a provisional owner is left to the send path.
     * Owners of the same path that do not match stay registered, so the file survives until the
     * last one is released. Called from message deletion on any thread, idempotent, and returns
     * how many owners were removed. A {@code null}/empty id list is a no-op.
     */
    public static int releaseOwnersForMessages(int account, long dialogId, Collection<Integer> messageIds, boolean deleteCopy) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        int released = 0;
        try {
            synchronized (LEASE_LOCK) {
                ArrayList<String> emptyPaths = new ArrayList<>();
                for (Map.Entry<String, Set<Owner>> entry : OWNERS.entrySet()) {
                    Set<Owner> owners = entry.getValue();
                    if (owners == null || owners.isEmpty()) {
                        emptyPaths.add(entry.getKey());
                        continue;
                    }
                    ArrayList<Owner> matched = new ArrayList<>();
                    for (Owner owner : owners) {
                        if (owner != null && owner.account == account && owner.dialogId == dialogId
                                && owner.messageId != 0 && messageIds.contains(owner.messageId)) {
                            matched.add(owner);
                        }
                    }
                    for (Owner owner : matched) {
                        if (owners.remove(owner)) {
                            released++;
                        }
                    }
                    if (owners.isEmpty()) {
                        emptyPaths.add(entry.getKey());
                    }
                }
                for (String path : emptyPaths) {
                    OWNERS.remove(path);
                    LEASED.remove(path);
                    deleteArtifactLocked(new File(path), deleteCopy);
                }
            }
        } catch (Throwable ignored) {
            return released;
        }
        return released;
    }

    /**
     * Terminal cleanup for a send that can never be retried (cancelled, deleted, rejected before
     * queueing): drops every owner/lease for the path and deletes the prepared file with its UUID
     * subdirectory. Only real artifacts inside {@code hgram_exif/<uuid>/} are touched; foreign
     * files, still-leased siblings and in-flight subdirectories are left alone, and the
     * subdirectory is removed only once it is empty. Fail-open.
     */
    public static void discardPrepared(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return;
            }
            File file = new File(path);
            synchronized (LEASE_LOCK) {
                String key = absolutePath(file);
                OWNERS.remove(key);
                LEASED.remove(key);
                deleteArtifactLocked(file, true);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Caller must hold {@link #LEASE_LOCK}. Only neutral own artifacts are ever deleted. */
    private static void deleteArtifactLocked(File file, boolean deleteCopy) {
        if (!deleteCopy || !isPreparedArtifactPath(file)
                || !isNeutralArtifactName(file.getName())) {
            return;
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (isInFlight(parent) || isLeased(file) || hasLeasedSibling(parent, file)) {
            return;
        }
        deleteQuietly(file);
        // delete() refuses to remove a non-empty directory, so a foreign entry keeps it.
        deleteQuietly(parent);
    }

    /**
     * Marks {@code account} as having pinned its restored pending attach paths and reports whether
     * the shared cache may be swept. Sweeps are deferred until every account that was activated at
     * the time has reported, so a sweep started by one account cannot delete prepared files that
     * another account has not pinned yet. Accounts that are never initialized here (SQL failure,
     * account added later) only delay the sweep, which is the safe direction; the 72h window in
     * {@link #sweepCache} still bounds growth once the barrier opens.
     */
    public static boolean registerPendingPins(int account, int activatedAccounts) {
        synchronized (LEASE_LOCK) {
            final int required = Math.max(1, activatedAccounts);
            if (required > pinRequiredAccounts) {
                pinRequiredAccounts = required;
            }
            PIN_READY_ACCOUNTS.add(account);
            return PIN_READY_ACCOUNTS.size() >= pinRequiredAccounts;
        }
    }

    /** Package-private reset so the barrier tests do not leak static state into each other. */
    static void resetPinBarrierForTests() {
        synchronized (LEASE_LOCK) {
            PIN_READY_ACCOUNTS.clear();
            pinRequiredAccounts = 0;
        }
    }

    /** Package-private probe used by the unit tests. */
    static boolean isLeasedForTests(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            String key = absolutePath(new File(path));
            synchronized (LEASE_LOCK) {
                return LEASED.contains(key);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Legacy flat target; {@link #prepareForSendDetailed} writes into a unique subdirectory. */
    public static File targetFile(File cacheDir, String neutralName) {
        File dir = new File(cacheDir, CACHE_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, neutralName);
    }

    public static boolean isSweepCandidate(File file, long now, long maxAgeMs) {
        return file != null && file.isFile() && isExpired(file, now, maxAgeMs);
    }

    /**
     * Array compatibility API for tests. Internally uses the same streaming strippers as the
     * production path; parse failures return the original array unchanged.
     */
    public static byte[] stripMetadata(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            if (isJpeg(data)) {
                try (InputStream in = new ByteArrayInputStream(data);
                     ByteArrayOutputStream out = new ByteArrayOutputStream(data.length)) {
                    if (!stripJpegStream(in, out)) {
                        return data;
                    }
                    return out.toByteArray();
                }
            }
            if (isPng(data)) {
                try (InputStream in = new ByteArrayInputStream(data);
                     ByteArrayOutputStream out = new ByteArrayOutputStream(data.length)) {
                    if (!stripPngStream(in, out)) {
                        return data;
                    }
                    return out.toByteArray();
                }
            }
            if (isWebp(data)) {
                return stripWebpBytes(data);
            }
        } catch (Throwable ignored) {
        }
        return data;
    }

    /**
     * Copies the source into a fresh unique cache subdirectory and strips metadata only for
     * formats with a dedicated streaming parser (JPEG/PNG/WebP). HEIC/HEIF and video containers
     * are copied byte-for-byte; they are only renamed.
     */
    public static Prepared prepareForSendDetailed(File source, File cacheDir) {
        if (source == null || cacheDir == null || !source.isFile()) {
            logFailure("HGRAM_EXIF " + PrepareResult.FAILED);
            return new Prepared(null, PrepareResult.FAILED);
        }
        String extension = extension(source.getName());
        File subdir = new File(rootDir(cacheDir), UUID.randomUUID().toString());
        String key = subdir.getAbsolutePath();
        File target = new File(subdir, neutralName(source.getName(), System.currentTimeMillis(),
                isImageExtension(extension)));
        synchronized (IN_FLIGHT) {
            IN_FLIGHT.add(key);
        }
        try {
            if (!subdir.isDirectory() && !subdir.mkdirs() && !subdir.isDirectory()) {
                logFailure("HGRAM_EXIF " + PrepareResult.FAILED);
                return new Prepared(null, PrepareResult.FAILED);
            }
            if (isStrippable(extension)) {
                if (stripToFile(extension, source, target)) {
                    target.setLastModified(System.currentTimeMillis());
                    lease(target);
                    return new Prepared(target, PrepareResult.CLEANED);
                }
                deleteQuietly(target);
                deleteQuietly(subdir);
                logFailure("HGRAM_EXIF " + PrepareResult.PARSE_FAILED);
                return new Prepared(null, PrepareResult.PARSE_FAILED);
            }
            copyFile(source, target);
            target.setLastModified(System.currentTimeMillis());
            lease(target);
            return new Prepared(target, PrepareResult.COPIED);
        } catch (Throwable t) {
            deleteQuietly(target);
            deleteQuietly(subdir);
            logFailure("HGRAM_EXIF " + PrepareResult.FAILED + ": "
                    + t.getClass().getSimpleName());
            return new Prepared(null, PrepareResult.FAILED);
        } finally {
            synchronized (IN_FLIGHT) {
                IN_FLIGHT.remove(key);
            }
        }
    }

    /** Compatibility wrapper: the prepared file or {@code null} on any failure. */
    public static File prepareForSend(File source, File cacheDir) {
        return prepareForSendDetailed(source, cacheDir).file;
    }

    public static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void logFailure(String message) {
        try {
            FileLog.e(message);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Removes expired prepared artifacts owned by this cache and returns how many files were
     * deleted. Two phases keep the shared lock short and let a lease taken by a restored pending
     * send always win: phase one walks the cache without {@link #LEASE_LOCK} and only collects
     * direct children of {@code rootDir}; phase two re-checks every candidate under the lock and
     * deletes it only while it still exists and is neither in flight, leased nor owned. Inside a UUID
     * subdirectory only neutral {@code IMG_*} / {@code FILE_*} files older than
     * {@link #SWEEP_MAX_AGE_MS} are candidates: unknown files are never deleted, and the
     * subdirectory is removed only once it is empty. Fail-open, and nothing outside {@code rootDir}
     * is touched.
     *
     * <p>Must only be reached after the barrier opened ({@link #registerPendingPins}): the media
     * cache is shared by every account, so a sweep may only delete files once all activated
     * accounts have pinned their restored sends.</p>
     */
    public static int sweepCache(File cacheDir, long now) {
        if (cacheDir == null) {
            return 0;
        }
        int deleted = 0;
        try {
            final File dir = rootDir(cacheDir);
            final ArrayList<File> candidates = new ArrayList<>();
            final ArrayList<File> parents = new ArrayList<>();
            // Phase 1, no LEASE_LOCK: collect owned expired files only. Foreign entries and
            // unknown files inside UUID subdirectories never become candidates.
            final File[] entries = dir.listFiles();
            if (entries == null) {
                return 0;
            }
            for (File entry : entries) {
                try {
                    if (entry == null || !isDirectChild(dir, entry)
                            || !isHgramExifArtifact(entry) || isInFlight(entry)) {
                        continue;
                    }
                    if (!entry.isDirectory()) {
                        if (isSweepCandidate(entry, now, SWEEP_MAX_AGE_MS)) {
                            candidates.add(entry);
                        }
                        continue;
                    }
                    if (!isExpired(entry, now, SWEEP_MAX_AGE_MS)) {
                        continue;
                    }
                    final File[] children = entry.listFiles();
                    if (children == null) {
                        continue;
                    }
                    for (File child : children) {
                        if (child != null && child.isFile()
                                && isNeutralArtifactName(child.getName())
                                && isExpired(child, now, SWEEP_MAX_AGE_MS)) {
                            candidates.add(child);
                            if (!parents.contains(entry)) {
                                parents.add(entry);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            // Phase 2, under LEASE_LOCK: re-check every candidate against the live lease, owner
            // and in-flight sets immediately before deleting it.
            synchronized (LEASE_LOCK) {
                for (File candidate : candidates) {
                    try {
                        if (!candidate.isFile() || isInFlight(candidate)
                                || isInFlight(candidate.getParentFile()) || isLeased(candidate)
                                || isOwned(candidate) || !isInside(dir, candidate)) {
                            continue;
                        }
                        if (deleteCounted(candidate)) {
                            deleted++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                for (File parent : parents) {
                    try {
                        if (!parent.isDirectory() || !isInside(dir, parent)) {
                            continue;
                        }
                        final File[] rest = parent.listFiles();
                        // A foreign file makes the directory non-empty, so delete() refuses it.
                        if (rest != null && rest.length == 0) {
                            deleteQuietly(parent);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return deleted;
    }

    /**
     * Manual entry point for the settings action: sweeps the prepared cache only when metadata
     * removal is enabled and every activated account has already pinned its restored pending
     * sends. Returns the number of deleted files, {@link #SWEEP_NOT_READY} when the barrier is
     * still closed, or {@link #SWEEP_DISABLED} when the feature is off.
     */
    public static int sweepPreparedIfEnabled(File cacheDir) {
        if (!HgramConfig.isExifCleanupEnabled()) {
            return SWEEP_DISABLED;
        }
        if (!pendingPinsCoverAll()) {
            return SWEEP_NOT_READY;
        }
        return sweepCache(cacheDir, System.currentTimeMillis());
    }

    /** True once every activated account reported its restored pending attach paths. */
    static boolean pendingPinsCoverAll() {
        synchronized (LEASE_LOCK) {
            return pinRequiredAccounts > 0 && PIN_READY_ACCOUNTS.size() >= pinRequiredAccounts;
        }
    }

    private static File rootDir(File cacheDir) {
        return CACHE_DIR_NAME.equals(cacheDir.getName())
                ? cacheDir
                : new File(cacheDir, CACHE_DIR_NAME);
    }

    private static boolean isInFlight(File file) {
        if (file == null) {
            return false;
        }
        synchronized (IN_FLIGHT) {
            return IN_FLIGHT.contains(file.getAbsolutePath());
        }
    }

    private static boolean isExpired(File file, long now, long maxAgeMs) {
        return file != null && now - file.lastModified() > maxAgeMs;
    }

    /** Flat cache artifacts share the neutral prefixes produced by {@link #neutralName}. */
    private static boolean isNeutralArtifactName(String name) {
        return name != null && (name.startsWith("IMG_") || name.startsWith("FILE_"));
    }

    /** Sweep may only treat our UUID subdirectories and flat neutral files as its own. */
    private static boolean isHgramExifArtifact(File file) {
        if (file == null) {
            return false;
        }
        try {
            if (file.isDirectory()) {
                return isUuidName(file.getName());
            }
            return file.isFile() && isNeutralArtifactName(file.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isUuidName(String name) {
        if (name == null || name.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void lease(File file) {
        if (file == null) {
            return;
        }
        try {
            String key = absolutePath(file);
            synchronized (LEASE_LOCK) {
                LEASED.add(key);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLeased(File file) {
        if (file == null) {
            return false;
        }
        try {
            String key = absolutePath(file);
            synchronized (LEASE_LOCK) {
                return LEASED.contains(key);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True while a message still owns this prepared copy; sweep must keep it. */
    private static boolean isOwned(File file) {
        if (file == null) {
            return false;
        }
        try {
            String key = absolutePath(file);
            synchronized (LEASE_LOCK) {
                return OWNERS.containsKey(key);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when the UUID directory also holds a leased prepared copy. */
    private static boolean hasLeasedSibling(File parent, File file) {
        if (parent == null) {
            return false;
        }
        File[] siblings = parent.listFiles();
        if (siblings == null) {
            return false;
        }
        for (File sibling : siblings) {
            if (sibling != null && !sibling.equals(file) && isLeased(sibling)) {
                return true;
            }
        }
        return false;
    }

    /** Only {@code .../hgram_exif/<uuid>/<file>} paths are eligible for ownership. */
    private static boolean isPreparedArtifactPath(File file) {
        if (file == null) {
            return false;
        }
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            File root = parent == null ? null : parent.getParentFile();
            return root != null && CACHE_DIR_NAME.equals(root.getName())
                    && isUuidName(parent.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Canonical form so pin/release/sweep compare the same path; falls back to absolute. */
    private static String absolutePath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Throwable ignored) {
            return file.getAbsolutePath();
        }
    }

    private static boolean isDirectChild(File parent, File child) {
        try {
            File childParent = child.getAbsoluteFile().getParentFile();
            return childParent != null && absolutePath(childParent).equals(absolutePath(parent));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isInside(File root, File candidate) {
        try {
            String rootPath = absolutePath(root);
            String candidatePath = absolutePath(candidate);
            return candidatePath.equals(rootPath)
                    || candidatePath.startsWith(rootPath + File.separator);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Deletes one file and reports whether it actually went away; fail-open. */
    private static boolean deleteCounted(File file) {
        if (file == null) {
            return false;
        }
        try {
            return file.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static boolean isImageExtension(String extension) {
        return "jpg".equals(extension) || "jpeg".equals(extension) || "png".equals(extension)
                || "webp".equals(extension) || "heic".equals(extension) || "heif".equals(extension);
    }

    private static boolean isStrippable(String extension) {
        return "jpg".equals(extension) || "jpeg".equals(extension) || "png".equals(extension)
                || "webp".equals(extension);
    }

    private static boolean stripToFile(String extension, File source, File target)
            throws IOException {
        if ("webp".equals(extension)) {
            return stripWebpFile(source, target);
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            boolean cleaned;
            if ("png".equals(extension)) {
                cleaned = stripPngStream(in, out);
            } else {
                cleaned = stripJpegStream(in, out);
            }
            out.flush();
            return cleaned;
        }
    }

    // ---------------------------------------------------------------- JPEG

    private static boolean stripJpegStream(InputStream in, OutputStream out) throws IOException {
        int soi0 = in.read();
        int soi1 = in.read();
        if (soi0 != 0xFF || soi1 != 0xD8) {
            return false;
        }
        out.write(0xFF);
        out.write(0xD8);
        while (true) {
            int markerStart = in.read();
            if (markerStart < 0) {
                return false;
            }
            if (markerStart != 0xFF) {
                return false;
            }
            int marker = in.read();
            while (marker == 0xFF) {
                marker = in.read();
            }
            if (marker < 0 || marker == 0x00 || marker == 0xD8) {
                return false;
            }
            if (marker == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                return true;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                out.write(0xFF);
                out.write(marker);
                continue;
            }
            int lengthHigh = in.read();
            int lengthLow = in.read();
            if (lengthHigh < 0 || lengthLow < 0) {
                return false;
            }
            int segmentLength = (lengthHigh << 8) | lengthLow;
            if (segmentLength < 2) {
                return false;
            }
            int payloadLength = segmentLength - 2;
            if (marker == 0xDA) {
                out.write(0xFF);
                out.write(0xDA);
                out.write(lengthHigh);
                out.write(lengthLow);
                if (!copyExact(in, out, payloadLength)) {
                    return false;
                }
                copyRemainder(in, out);
                return true;
            }
            if (marker == 0xE1) {
                // APP1 carries EXIF/XMP. Only a rebuilt orientation-only EXIF is preserved.
                byte[] payload = new byte[payloadLength];
                if (!readFully(in, payload, 0, payloadLength)) {
                    return false;
                }
                if (isExif(payload)) {
                    int orientation = readExifOrientation(payload);
                    if (orientation > 1) {
                        byte[] replacement = minimalExifApp1(orientation);
                        out.write(replacement, 0, replacement.length);
                    }
                }
                continue;
            }
            if (marker == 0xED || marker == 0xFE) {
                if (!skipFully(in, payloadLength)) {
                    return false;
                }
                continue;
            }
            out.write(0xFF);
            out.write(marker);
            out.write(lengthHigh);
            out.write(lengthLow);
            if (!copyExact(in, out, payloadLength)) {
                return false;
            }
        }
    }

    private static boolean isExif(byte[] payload) {
        return payload.length >= 6
                && payload[0] == 'E' && payload[1] == 'x' && payload[2] == 'i'
                && payload[3] == 'f' && payload[4] == 0 && payload[5] == 0;
    }

    private static int readExifOrientation(byte[] payload) {
        if (!isExif(payload) || payload.length < 14) {
            return 0;
        }
        int base = 6;
        boolean little;
        if (payload[base] == 'I' && payload[base + 1] == 'I') {
            little = true;
        } else if (payload[base] == 'M' && payload[base + 1] == 'M') {
            little = false;
        } else {
            return 0;
        }
        if (readU16(payload, base + 2, little) != 42) {
            return 0;
        }
        long ifdOffset = readU32(payload, base + 4, little);
        if (ifdOffset < 8 || ifdOffset > payload.length - base) {
            return 0;
        }
        int ifd = base + (int) ifdOffset;
        int count = readU16(payload, ifd, little);
        if (count < 0) {
            return 0;
        }
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > payload.length) {
                return 0;
            }
            if (readU16(payload, entry, little) != 0x0112) {
                continue;
            }
            int type = readU16(payload, entry + 2, little);
            long components = readU32(payload, entry + 4, little);
            if (components != 1) {
                return 0;
            }
            // Only 1..8 are valid EXIF orientation values; anything else counts as absent.
            if (type == 3) {
                int value = readU16(payload, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 0;
            }
            if (type == 4) {
                long value = readU32(payload, entry + 8, little);
                return value >= 1 && value <= 8 ? (int) value : 0;
            }
            return 0;
        }
        return 0;
    }

    /** Minimal little-endian EXIF APP1: TIFF header + IFD0 with a single Orientation entry. */
    private static byte[] minimalExifApp1(int orientation) {
        byte[] tiff = new byte[26];
        tiff[0] = 'I';
        tiff[1] = 'I';
        tiff[2] = 0x2A;
        tiff[3] = 0x00;
        tiff[4] = 0x08;
        tiff[8] = 0x01;              // one IFD entry
        tiff[10] = 0x12;             // tag 0x0112
        tiff[11] = 0x01;
        tiff[12] = 0x03;             // type SHORT
        tiff[14] = 0x01;             // component count 1
        tiff[18] = (byte) orientation;
        tiff[19] = (byte) (orientation >>> 8);

        byte[] out = new byte[2 + 2 + 6 + tiff.length];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xE1;
        int segmentLength = 2 + 6 + tiff.length;
        out[2] = (byte) (segmentLength >>> 8);
        out[3] = (byte) segmentLength;
        out[4] = 'E';
        out[5] = 'x';
        out[6] = 'i';
        out[7] = 'f';
        System.arraycopy(tiff, 0, out, 10, tiff.length);
        return out;
    }

    // ----------------------------------------------------------------- PNG

    private static boolean stripPngStream(InputStream in, OutputStream out) throws IOException {
        byte[] signature = new byte[8];
        if (!readFully(in, signature, 0, signature.length)) {
            return false;
        }
        if ((signature[0] & 0xFF) != 0x89 || signature[1] != 'P' || signature[2] != 'N'
                || signature[3] != 'G' || (signature[4] & 0xFF) != 0x0D
                || (signature[5] & 0xFF) != 0x0A || (signature[6] & 0xFF) != 0x1A
                || (signature[7] & 0xFF) != 0x0A) {
            return false;
        }
        out.write(signature);
        byte[] header = new byte[8];
        while (true) {
            if (!readFully(in, header, 0, header.length)) {
                return false;
            }
            long length = readU32BE(header, 0);
            if (length > 0x7FFFFFFFL) {
                return false;
            }
            boolean drop = fourcc(header, 4, 'e', 'X', 'I', 'f')
                    || fourcc(header, 4, 't', 'E', 'X', 't')
                    || fourcc(header, 4, 'i', 'T', 'X', 't')
                    || fourcc(header, 4, 'z', 'T', 'X', 't')
                    || fourcc(header, 4, 't', 'I', 'M', 'E');
            if (drop) {
                if (!skipFully(in, length + 4L)) {
                    return false;
                }
            } else {
                out.write(header);
                if (!copyExact(in, out, length + 4L)) {
                    return false;
                }
            }
            if (fourcc(header, 4, 'I', 'E', 'N', 'D')) {
                return true;
            }
        }
    }

    // ---------------------------------------------------------------- WebP

    private static final class FileWebpSink extends WebpSink {
        private final RandomAccessFile file;
        private long written;

        FileWebpSink(RandomAccessFile file) {
            this.file = file;
        }

        @Override
        public void write(int b) throws IOException {
            file.write(b);
            written++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            file.write(buffer, offset, length);
            written += length;
        }

        @Override
        void finish() throws IOException {
            long riffSize = written - 8L;
            file.seek(4L);
            file.write((int) (riffSize & 0xFF));
            file.write((int) ((riffSize >>> 8) & 0xFF));
            file.write((int) ((riffSize >>> 16) & 0xFF));
            file.write((int) ((riffSize >>> 24) & 0xFF));
            file.setLength(written);
        }
    }

    private abstract static class WebpSink extends OutputStream {
        abstract void finish() throws IOException;
    }

    private static boolean stripWebpFile(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source);
             RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
            raf.setLength(0L);
            FileWebpSink sink = new FileWebpSink(raf);
            if (!stripWebpStream(in, sink)) {
                return false;
            }
            sink.finish();
            return true;
        }
    }

    private static byte[] stripWebpBytes(byte[] data) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            WebpSink sink = new WebpSink() {
                @Override
                public void write(int b) {
                    out.write(b);
                }

                @Override
                public void write(byte[] buffer, int offset, int length) {
                    out.write(buffer, offset, length);
                }

                @Override
                void finish() {
                }
            };
            if (!stripWebpStream(new ByteArrayInputStream(data), sink)) {
                return data;
            }
            byte[] result = out.toByteArray();
            long riffSize = result.length - 8L;
            result[4] = (byte) riffSize;
            result[5] = (byte) (riffSize >>> 8);
            result[6] = (byte) (riffSize >>> 16);
            result[7] = (byte) (riffSize >>> 24);
            return result;
        } catch (Throwable t) {
            return data;
        }
    }

    private static boolean stripWebpStream(InputStream in, WebpSink sink) throws IOException {
        byte[] header = new byte[12];
        if (!readFully(in, header, 0, header.length)) {
            return false;
        }
        if (!fourcc(header, 0, 'R', 'I', 'F', 'F')
                || !fourcc(header, 8, 'W', 'E', 'B', 'P')) {
            return false;
        }
        long riffSize = readU32LE(header, 4);
        if (riffSize < 4) {
            return false;
        }
        long bodyRemaining = riffSize - 4L;
        sink.write(header, 0, header.length);
        byte[] chunkHeader = new byte[8];
        while (bodyRemaining > 0) {
            if (bodyRemaining < 8) {
                return false;
            }
            if (!readFully(in, chunkHeader, 0, chunkHeader.length)) {
                return false;
            }
            long chunkSize = readU32LE(chunkHeader, 4);
            if (chunkSize > 0x7FFFFFFFL) {
                return false;
            }
            long paddedSize = chunkSize + (chunkSize & 1L);
            long chunkTotal = 8L + paddedSize;
            if (chunkTotal > bodyRemaining) {
                return false;
            }
            boolean drop = fourcc(chunkHeader, 0, 'E', 'X', 'I', 'F')
                    || fourcc(chunkHeader, 0, 'X', 'M', 'P', ' ');
            if (drop) {
                if (!skipFully(in, chunkTotal - 8L)) {
                    return false;
                }
            } else if (fourcc(chunkHeader, 0, 'V', 'P', '8', 'X')) {
                sink.write(chunkHeader, 0, chunkHeader.length);
                if (chunkSize > 0) {
                    int flags = in.read();
                    if (flags < 0) {
                        return false;
                    }
                    sink.write(flags & ~0x0C);
                    if (!copyExact(in, sink, chunkSize - 1L)
                            || !copyExact(in, sink, paddedSize - chunkSize)) {
                        return false;
                    }
                }
            } else {
                sink.write(chunkHeader, 0, chunkHeader.length);
                if (!copyExact(in, sink, chunkTotal - 8L)) {
                    return false;
                }
            }
            bodyRemaining -= chunkTotal;
        }
        return true;
    }

    // ------------------------------------------------------------- helpers

    private static boolean isJpeg(byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }

    private static boolean isPng(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && (data[4] & 0xFF) == 0x0D && (data[5] & 0xFF) == 0x0A
                && (data[6] & 0xFF) == 0x1A && (data[7] & 0xFF) == 0x0A;
    }

    private static boolean isWebp(byte[] data) {
        return data.length >= 12
                && fourcc(data, 0, 'R', 'I', 'F', 'F')
                && fourcc(data, 8, 'W', 'E', 'B', 'P');
    }

    private static boolean readFully(InputStream in, byte[] buffer, int offset, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            int count = in.read(buffer, offset + read, length - read);
            if (count < 0) {
                return false;
            }
            read += count;
        }
        return true;
    }

    private static boolean skipFully(InputStream in, long length) throws IOException {
        if (length <= 0) {
            return true;
        }
        byte[] buffer = new byte[(int) Math.min(length, BUFFER_SIZE)];
        long remaining = length;
        while (remaining > 0) {
            int count = in.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (count < 0) {
                return false;
            }
            remaining -= count;
        }
        return true;
    }

    private static boolean copyExact(InputStream in, OutputStream out, long length)
            throws IOException {
        if (length <= 0) {
            return true;
        }
        byte[] buffer = new byte[(int) Math.min(length, BUFFER_SIZE)];
        long remaining = length;
        while (remaining > 0) {
            int count = in.read(buffer, 0, (int) Math.min(remaining, buffer.length));
            if (count < 0) {
                return false;
            }
            out.write(buffer, 0, count);
            remaining -= count;
        }
        return true;
    }

    private static void copyRemainder(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            out.flush();
        }
    }

    private static boolean fourcc(byte[] data, int offset, char a, char b, char c, char d) {
        return offset >= 0 && offset + 4 <= data.length
                && (data[offset] & 0xFF) == a
                && (data[offset + 1] & 0xFF) == b
                && (data[offset + 2] & 0xFF) == c
                && (data[offset + 3] & 0xFF) == d;
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

    private static long readU32BE(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24)
                | ((data[offset + 1] & 0xFFL) << 16)
                | ((data[offset + 2] & 0xFFL) << 8)
                | (data[offset + 3] & 0xFFL);
    }

    private static long readU32LE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }
}
