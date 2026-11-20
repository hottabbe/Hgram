package xyz.nextalone.nagram.hgram;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cache-only media preview for system notifications. No network or large-original decoding:
 * only ImageLoader memory entries and already downloaded thumbnail files are used.
 */
public final class HgramNotificationMedia {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_PHOTO = 1;
    public static final int TYPE_VIDEO = 2;
    public static final int TYPE_GIF = 3;
    public static final int TYPE_STICKER = 4;

    private static final int MAX_PREVIEW_WIDTH = 1280;
    static final int MIUI_PREVIEW_WIDTH = 984;
    static final int MIUI_PREVIEW_HEIGHT = 450;
    static final int MIUI_JPEG_QUALITY = 85;
    static final int MAX_MIUI_PREVIEW_BYTES = 1024 * 1024;
    private static final int MIN_PREVIEW_WIDTH = 400;
    static final int MAX_INLINE_THUMB_BYTES = 512 * 1024;
    private static final long MAX_THUMB_FILE_BYTES = 8L * 1024L * 1024L;
    static final long FETCH_BUDGET_MS = 5000L;
    private static final long FETCH_POLL_MS = 250L;
    private static final String[] MEMORY_FILTERS = {"800_800", "200_200", "100_100", "50_50"};

    public static final class Preview {
        public final MessageObject message;
        public final Bitmap bitmap;

        public Preview(MessageObject message, Bitmap bitmap) {
            this.message = message;
            this.bitmap = bitmap;
        }
    }

    public interface PreviewCallback {
        void onPreview(String key, Bitmap bitmap);
    }

    static final class Source {
        final TLRPC.Photo photo;
        final TLRPC.Document document;
        final TLRPC.PhotoSize photoSize;
        final TLRPC.VideoSize videoSize;
        final byte[] inlineThumb;
        final boolean inlineStripped;

        Source(TLRPC.Photo photo, TLRPC.Document document, TLRPC.PhotoSize photoSize,
               TLRPC.VideoSize videoSize) {
            this(photo, document, photoSize, videoSize, null);
        }

        Source(TLRPC.Photo photo, TLRPC.Document document, TLRPC.PhotoSize photoSize,
               TLRPC.VideoSize videoSize, byte[] inlineThumb) {
            this(photo, document, photoSize, videoSize, inlineThumb, false);
        }

        Source(TLRPC.Photo photo, TLRPC.Document document, TLRPC.PhotoSize photoSize,
               TLRPC.VideoSize videoSize, byte[] inlineThumb, boolean inlineStripped) {
            this.photo = photo;
            this.document = document;
            this.photoSize = photoSize;
            this.videoSize = videoSize;
            this.inlineThumb = inlineThumb;
            this.inlineStripped = inlineStripped;
        }

        TLObject location() {
            if (photoSize != null) {
                return photoSize.location;
            }
            if (videoSize != null) {
                return videoSize.location;
            }
            return null;
        }

        ImageLocation imageLocation() {
            if (photo != null && photoSize != null) {
                return ImageLocation.getForPhoto(photoSize, photo);
            }
            if (document != null && photoSize != null) {
                return ImageLocation.getForDocument(photoSize, document);
            }
            if (document != null && videoSize != null) {
                return ImageLocation.getForDocument(videoSize, document);
            }
            return null;
        }
    }

    private static final ConcurrentHashMap<String, FetchRequest> REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> CURRENT_KEYS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Runnable> POSTED_CALLBACKS = new ConcurrentHashMap<>();

    private static final class FetchRequest implements Runnable {
        final String key;
        final int account;
        final long dialogId;
        final Source source;
        final PreviewCallback callback;
        final long deadline;
        final AtomicBoolean done = new AtomicBoolean();
        volatile boolean cancelled;

        FetchRequest(String key, int account, long dialogId, Source source,
                     PreviewCallback callback) {
            this.key = key;
            this.account = account;
            this.dialogId = dialogId;
            this.source = source;
            this.callback = callback;
            this.deadline = SystemClock.elapsedRealtime() + fetchBudgetForTest;
        }

        void start() {
            if (blockFetchForTests) {
                return;
            }
            try {
                final ImageLocation location = source.imageLocation();
                if (location != null) {
                    FileLoader.getInstance(account).loadFile(
                            location, null, null, FileLoader.PRIORITY_LOW, 1);
                }
            } catch (Throwable ignored) {
            }
            poll();
        }

        void poll() {
            if (cancelled || done.get()) {
                return;
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                finish(null);
                return;
            }
            final Bitmap bitmap;
            try {
                bitmap = loadSource(account, source);
            } catch (Throwable t) {
                finish(null);
                return;
            }
            if (bitmap != null) {
                finish(bitmap);
                return;
            }
            Utilities.globalQueue.postRunnable(this, FETCH_POLL_MS);
        }

        @Override
        public void run() {
            poll();
        }

        void finish(Bitmap bitmap) {
            if (!done.compareAndSet(false, true)) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                return;
            }
            REQUESTS.remove(key, this);
            if (bitmap != null && SystemClock.elapsedRealtime() > deadline) {
                bitmap.recycle();
                bitmap = null;
            }
            if (cancelled || bitmap == null) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                return;
            }
            callback.onPreview(key, bitmap);
        }

        void cancel() {
            cancelled = true;
            finish(null);
        }
    }

    interface CachedPreviewLoader {
        Bitmap load(int account, MessageObject message);
    }

    private static volatile CachedPreviewLoader testLoader;
    static volatile boolean blockFetchForTests;
    static volatile long fetchBudgetForTest = FETCH_BUDGET_MS;

    static void setPreviewLoaderForTest(CachedPreviewLoader loader) {
        testLoader = loader;
    }

    static String[] memoryFiltersForTest() {
        return MEMORY_FILTERS.clone();
    }

    private HgramNotificationMedia() {
    }

    public static int classify(TLRPC.Message message) {
        if (message == null || message.media == null) {
            return TYPE_NONE;
        }
        if (message.media.photo != null) {
            return TYPE_PHOTO;
        }
        final TLRPC.Document document = message.media.document;
        if (document == null) {
            return TYPE_NONE;
        }
        if (MessageObject.isStickerDocument(document)) {
            return TYPE_STICKER;
        }
        if (MessageObject.isGifDocument(document)) {
            return TYPE_GIF;
        }
        if (MessageObject.isVideoDocument(document)) {
            return TYPE_VIDEO;
        }
        return TYPE_NONE;
    }

    public static TLRPC.PhotoSize findPreviewSize(TLRPC.Message message) {
        final int type = classify(message);
        if (type == TYPE_NONE) {
            return null;
        }
        if (type == TYPE_PHOTO) {
            final TLRPC.Photo photo = message.media.photo;
            if (photo == null || photo.sizes == null) {
                return null;
            }
            return choosePreviewSizeOrAny(photo.sizes);
        }
        final TLRPC.Document document = message.media.document;
        if (document == null || document.thumbs == null) {
            return null;
        }
        return choosePreviewSizeOrAny(document.thumbs);
    }

    static TLRPC.PhotoSize choosePreviewSize(List<TLRPC.PhotoSize> sizes) {
        if (sizes == null) {
            return null;
        }
        TLRPC.PhotoSize best = null;
        TLRPC.PhotoSize smallestAbove = null;
        for (int i = 0; i < sizes.size(); i++) {
            final TLRPC.PhotoSize size = sizes.get(i);
            if (size == null) {
                continue;
            }
            final int width = size.w > 0 ? size.w : size.h;
            if (width < MIN_PREVIEW_WIDTH) {
                continue;
            }
            if (width <= MAX_PREVIEW_WIDTH
                    && (best == null || width > (best.w > 0 ? best.w : best.h))) {
                best = size;
            } else if (smallestAbove == null
                    || width < (smallestAbove.w > 0 ? smallestAbove.w : smallestAbove.h)) {
                smallestAbove = size;
            }
        }
        return best != null ? best : smallestAbove;
    }

    /**
     * Strict rule first, then any usable thumbnail. The latter is important for GIF/stickers
     * whose only available preview is 320 px or an inline byte array: a small preview is better
     * than no preview. Photo callers still get the best 400-1280 candidate when one exists.
     */
    static TLRPC.PhotoSize choosePreviewSizeOrAny(List<TLRPC.PhotoSize> sizes) {
        final TLRPC.PhotoSize strict = choosePreviewSize(sizes);
        if (isUsablePreviewSize(strict)) {
            return strict;
        }
        return chooseFallbackPreviewSize(sizes);
    }

    static TLRPC.PhotoSize chooseFallbackPreviewSize(List<TLRPC.PhotoSize> sizes) {
        if (sizes == null) {
            return null;
        }
        TLRPC.PhotoSize best = null;
        for (int i = 0; i < sizes.size(); i++) {
            final TLRPC.PhotoSize size = sizes.get(i);
            if (size == null || !isUsablePreviewSize(size)) {
                continue;
            }
            final int width = size.w > 0 ? size.w : size.h;
            final int bestWidth = best == null ? -1 : (best.w > 0 ? best.w : best.h);
            if (best == null || width > bestWidth) {
                best = size;
            }
        }
        return best;
    }

    static boolean isInlineThumbAllowed(byte[] bytes) {
        return bytes != null && bytes.length > 0 && bytes.length <= MAX_INLINE_THUMB_BYTES;
    }

    /** Pure album rule used by tests and by callers that only have message owners. */
    public static TLRPC.Message findFirstPreviewMessage(List<TLRPC.Message> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = 0; i < messages.size(); i++) {
            final TLRPC.Message message = messages.get(i);
            if (classify(message) != TYPE_NONE) {
                return message;
            }
        }
        return null;
    }

    /** Album/group rule: the first element with a supported media type wins. */
    public static MessageObject findFirstPreview(List<MessageObject> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject message = messages.get(i);
            if (message != null && classify(message.messageOwner) != TYPE_NONE) {
                return message;
            }
        }
        return null;
    }

    /**
     * Series/album rule: album takes its first supported element; otherwise the newest media in
     * the batch wins. The list is normally newest-first, but date/id comparison makes the choice
     * independent of caller order. {@code lastMessage} supplies the visible text/heading, which may
     * differ from the media message.
     */
    public static MessageObject findNotificationPreview(List<MessageObject> messages,
                                                        MessageObject lastMessage) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        final MessageObject first = findFirstPreview(messages);
        if (messages.size() > 1 && first != null && first.messageOwner != null
                && first.messageOwner.grouped_id != 0) {
            final MessageObject newerOutsideAlbum =
                    findNewestPreview(messages, first.messageOwner.grouped_id);
            if (newerOutsideAlbum != null && isNewer(newerOutsideAlbum, first)) {
                return newerOutsideAlbum;
            }
            return first;
        }
        return findNewestPreview(messages, 0);
    }

    public static TLRPC.Message findNotificationPreviewMessage(List<TLRPC.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        final TLRPC.Message first = findFirstPreviewMessage(messages);
        if (messages.size() > 1 && first != null && first.grouped_id != 0) {
            final TLRPC.Message newerOutsideAlbum =
                    findNewestPreviewMessage(messages, first.grouped_id);
            if (newerOutsideAlbum != null && isNewer(newerOutsideAlbum, first)) {
                return newerOutsideAlbum;
            }
            return first;
        }
        return findNewestPreviewMessage(messages, 0);
    }

    private static MessageObject findNewestPreview(List<MessageObject> messages,
                                                   long excludedGroupedId) {
        MessageObject newest = null;
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject message = messages.get(i);
            if (message == null || message.messageOwner == null
                    || classify(message.messageOwner) == TYPE_NONE) {
                continue;
            }
            if (excludedGroupedId != 0 && message.messageOwner.grouped_id == excludedGroupedId) {
                continue;
            }
            if (newest == null || isNewer(message, newest)) {
                newest = message;
            }
        }
        return newest;
    }

    private static TLRPC.Message findNewestPreviewMessage(List<TLRPC.Message> messages,
                                                           long excludedGroupedId) {
        TLRPC.Message newest = null;
        for (int i = 0; i < messages.size(); i++) {
            final TLRPC.Message message = messages.get(i);
            if (message == null || classify(message) == TYPE_NONE) {
                continue;
            }
            if (excludedGroupedId != 0 && message.grouped_id == excludedGroupedId) {
                continue;
            }
            if (newest == null || isNewer(message, newest)) {
                newest = message;
            }
        }
        return newest;
    }

    private static boolean isNewer(MessageObject left, MessageObject right) {
        final TLRPC.Message a = left.messageOwner;
        final TLRPC.Message b = right.messageOwner;
        if (a.date != b.date) {
            return a.date > b.date;
        }
        return left.getId() > right.getId();
    }

    private static boolean isNewer(TLRPC.Message left, TLRPC.Message right) {
        if (left.date != right.date) {
            return left.date > right.date;
        }
        return left.id > right.id;
    }

    /** Album fallback: try up to three supported elements until one has a cached bitmap. */
    public static Preview loadFirstCachedPreview(int account, List<MessageObject> messages) {
        if (messages == null) {
            return null;
        }
        int attempts = 0;
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject message = messages.get(i);
            if (message == null || classify(message.messageOwner) == TYPE_NONE) {
                continue;
            }
            if (attempts++ >= 3) {
                break;
            }
            final Bitmap bitmap = loadCachedPreview(account, message);
            if (bitmap != null) {
                return new Preview(message, bitmap);
            }
        }
        return null;
    }

    /**
     * Blocking first-post preview for the notifications queue. Tries the memory/disk cache first,
     * then starts a low-priority thumb load and waits up to {@code budgetMs} (25 ms poll). This is
     * never called from the UI thread; the notification is posted with the image already attached,
     * which avoids relying on a silent style update on MIUI/HyperOS.
     */
    public static Preview previewForFirstPost(int account, MessageObject message, long budgetMs,
                                             BooleanSupplier allowed) {
        final long started = SystemClock.elapsedRealtime();
        if (message == null || message.messageOwner == null
                || !canPreview(account, message.getDialogId())) {
            logFirstPost(account, false, started, message, false, false);
            return null;
        }
        if (!isAllowed(allowed)) {
            logFirstPost(account, false, started, message, false, true);
            return null;
        }
        if (overBudget(started, budgetMs, SystemClock.elapsedRealtime())) {
            logFirstPost(account, false, started, message, true, false);
            return null;
        }
        final Bitmap cached = loadCachedPreview(account, message);
        if (cached != null) {
            final Bitmap checked = enforceAllowed(allowed, cached);
            if (checked == null) {
                logFirstPost(account, true, started, message, false, true);
                return null;
            }
            if (overBudget(started, budgetMs, SystemClock.elapsedRealtime())) {
                checked.recycle();
                logFirstPost(account, true, started, message, true, false);
                return null;
            }
            logFirstPost(account, true, started, message, false, false);
            return new Preview(message, checked);
        }
        if (overBudget(started, budgetMs, SystemClock.elapsedRealtime())) {
            logFirstPost(account, false, started, message, true, false);
            return null;
        }
        final Source source = findSource(message.messageOwner);
        if (source == null) {
            logFirstPost(account, false, started, message, false, false);
            return null;
        }
        final ImageLocation location = source.imageLocation();
        if (location != null) {
            try {
                FileLoader.getInstance(account).loadFile(
                        location, null, null, FileLoader.PRIORITY_LOW, 1);
            } catch (Throwable ignored) {
            }
        }
        boolean budgetExceeded = false;
        while (true) {
            if (overBudget(started, budgetMs, SystemClock.elapsedRealtime())) {
                budgetExceeded = true;
                break;
            }
            try {
                final Bitmap bitmap = loadSource(account, source);
                if (bitmap != null) {
                    final Bitmap checked = enforceAllowed(allowed, bitmap);
                    if (checked == null) {
                        logFirstPost(account, true, started, message, false, true);
                        return null;
                    }
                    if (overBudget(started, budgetMs, SystemClock.elapsedRealtime())) {
                        checked.recycle();
                        logFirstPost(account, true, started, message, true, false);
                        return null;
                    }
                    logFirstPost(account, true, started, message, false, false);
                    return new Preview(message, checked);
                }
            } catch (Throwable ignored) {
            }
            final long now = SystemClock.elapsedRealtime();
            if (overBudget(started, budgetMs, now)) {
                budgetExceeded = true;
                break;
            }
            try {
                Thread.sleep(Math.min(25L, Math.max(1L, started + budgetMs - now)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        logFirstPost(account, false, started, message, budgetExceeded, false);
        return null;
    }

    static boolean overBudget(long started, long budgetMs, long now) {
        return now - started > Math.max(0L, budgetMs);
    }

    static boolean isAllowed(BooleanSupplier allowed) {
        return allowed == null || allowed.getAsBoolean();
    }

    static Bitmap enforceAllowed(BooleanSupplier allowed, Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        if (!isAllowed(allowed)) {
            bitmap.recycle();
            return null;
        }
        return bitmap;
    }

    public static void logDenied(int account, MessageObject message) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        final String key = message == null ? "none"
                : (account + ":" + message.getDialogId() + ":" + message.getId());
        FileLog.d("HgramMedia firstPost denied=1 key=" + key);
    }

    private static void logFirstPost(int account, boolean sync, long started,
                                     MessageObject message, boolean overBudget,
                                     boolean denied) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        final long wait = SystemClock.elapsedRealtime() - started;
        final String key = message == null ? "none"
                : (account + ":" + message.getDialogId() + ":" + message.getId());
        FileLog.d("HgramMedia firstPost sync=" + (sync ? 1 : 0)
                + " overBudget=" + (overBudget ? 1 : 0)
                + " denied=" + (denied ? 1 : 0)
                + " waitMs=" + wait + " key=" + key);
    }

    public static boolean canPreview(int account, long dialogId) {
        if (HgramConfig.isAntiPeekActive()) {
            return false;
        }
        return !HgramSecretSpace.isHidden(account, dialogId);
    }

    /**
     * Starts one low-priority thumb download and one catch-up callback within a 5 s budget.
     * Returns false when there is no thumb source or a request for this message already exists.
     */
    public static boolean requestPreview(int account, long dialogId, MessageObject message,
                                         PreviewCallback callback) {
        return message != null && requestPreview(account, dialogId,
                message.messageOwner, message.getId(), callback);
    }

    static boolean requestPreview(int account, long dialogId, TLRPC.Message message,
                                  long messageId, PreviewCallback callback) {
        if (message == null || callback == null || !canPreview(account, dialogId)) {
            return false;
        }
        final Source source = findSource(message);
        if (source == null) {
            return false;
        }
        final String key = account + ":" + dialogId + ":" + messageId;
        final FetchRequest request = new FetchRequest(
                key, account, dialogId, source, callback);
        if (REQUESTS.putIfAbsent(key, request) != null) {
            return false;
        }
        CURRENT_KEYS.put(dialogKey(account, dialogId), key);
        request.start();
        return true;
    }

    /** Album/group fetch: try up to three supported elements with a valid thumb Source. */
    public static boolean requestPreview(int account, long dialogId, List<MessageObject> messages,
                                         PreviewCallback callback) {
        if (messages == null || callback == null || !canPreview(account, dialogId)) {
            return false;
        }
        if (messages.size() > 1) {
            final MessageObject first = findFirstPreview(messages);
            if (first == null || first.messageOwner.grouped_id == 0) {
                return false;
            }
        }
        int attempts = 0;
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject message = messages.get(i);
            if (message == null || classify(message.messageOwner) == TYPE_NONE) {
                continue;
            }
            if (attempts++ >= 3) {
                break;
            }
            if (requestPreview(account, dialogId, message.messageOwner,
                    message.getId(), callback)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCurrentPreview(String key) {
        if (key == null) {
            return false;
        }
        final int first = key.indexOf(':');
        final int second = key.indexOf(':', first + 1);
        if (first < 0 || second < 0) {
            return false;
        }
        return key.equals(CURRENT_KEYS.get(key.substring(0, second)));
    }

    static String currentPreviewKeyForTest(int account, long dialogId) {
        return CURRENT_KEYS.get(dialogKey(account, dialogId));
    }

    public static void registerPostedCallback(String key, Runnable runnable) {
        if (key != null && runnable != null) {
            POSTED_CALLBACKS.put(key, runnable);
        }
    }

    public static void unregisterPostedCallback(String key) {
        if (key != null) {
            POSTED_CALLBACKS.remove(key);
        }
    }

    private static String dialogKey(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    private static void cancelPostedCallbacks(String prefix) {
        for (String key : POSTED_CALLBACKS.keySet()) {
            if (key.startsWith(prefix)) {
                final Runnable runnable = POSTED_CALLBACKS.remove(key);
                if (runnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(runnable);
                }
            }
        }
    }

    public static void cancelPreviewsForDialog(int account, long dialogId) {
        final String prefix = account + ":" + dialogId + ":";
        for (String key : REQUESTS.keySet()) {
            if (key.startsWith(prefix)) {
                final FetchRequest request = REQUESTS.remove(key);
                if (request != null) {
                    request.cancel();
                }
            }
        }
        cancelPostedCallbacks(prefix);
        CURRENT_KEYS.remove(dialogKey(account, dialogId));
    }

    public static void cancelAllPreviews() {
        for (String key : REQUESTS.keySet()) {
            final FetchRequest request = REQUESTS.remove(key);
            if (request != null) {
                request.cancel();
            }
        }
        for (String key : POSTED_CALLBACKS.keySet()) {
            final Runnable runnable = POSTED_CALLBACKS.remove(key);
            if (runnable != null) {
                AndroidUtilities.cancelRunOnUIThread(runnable);
            }
        }
        CURRENT_KEYS.clear();
    }

    /**
     * Returns an owned bitmap from memory cache or a downloaded thumbnail file, or null.
     * A memory-cache bitmap is always copied, so the notification never holds a reference that
     * ImageLoader may recycle. The caller must recycle the returned bitmap after notify.
     */
    public static Bitmap loadCachedPreview(int account, MessageObject message) {
        final CachedPreviewLoader loader = testLoader;
        if (loader != null) {
            return ownCopy(loader.load(account, message));
        }
        try {
            return loadCachedPreviewInternal(account, message);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap loadCachedPreviewInternal(int account, MessageObject message) {
        if (message == null || message.messageOwner == null || message.messageOwner.media == null) {
            return null;
        }
        return loadSource(account, findSource(message.messageOwner));
    }

    static Source findSource(TLRPC.Message message) {
        final int type = classify(message);
        if (type == TYPE_NONE) {
            return null;
        }
        if (type == TYPE_PHOTO) {
            final TLRPC.Photo photo = message.media.photo;
            final TLRPC.PhotoSize size = findPreviewSize(message);
            return photo == null || size == null
                    ? null : new Source(photo, null, size, null, size.bytes,
                    size instanceof TLRPC.TL_photoStrippedSize);
        }
        final TLRPC.Document document = message.media.document;
        if (document == null) {
            return null;
        }
        // 1. Any usable document thumb wins, including small and inline previews.
        final TLRPC.PhotoSize any = choosePreviewSizeOrAny(document.thumbs);
        if (isUsablePreviewSize(any)) {
            return new Source(null, document, any, null,
                    isInlineThumbAllowed(any.bytes) ? any.bytes : null,
                    any instanceof TLRPC.TL_photoStrippedSize);
        }
        // 2. Video thumbs; decodeThumb validates that the file is really an image.
        if (document.video_thumbs != null) {
            for (int i = 0; i < document.video_thumbs.size(); i++) {
                final TLRPC.VideoSize videoSize = document.video_thumbs.get(i);
                if (videoSize != null && videoSize.location != null) {
                    return new Source(null, document, null, videoSize);
                }
            }
        }
        return null;
    }

    static boolean isUsablePreviewSize(TLRPC.PhotoSize size) {
        return size != null && (size.location != null || isInlineThumbAllowed(size.bytes));
    }

    static Bitmap loadSourceForTest(Source source) {
        return loadSource(0, source);
    }

    private static Bitmap loadSource(int account, Source source) {
        if (source == null) {
            return null;
        }
        if (source.location() != null) {
            final Bitmap memory = loadFromMemory(source.location());
            if (memory != null) {
                return memory;
            }
            try {
                final File file = source.photoSize != null
                        ? FileLoader.getInstance(account).getPathToAttach(source.photoSize, true)
                        : FileLoader.getInstance(account).getPathToAttach(source.videoSize, true);
                final Bitmap bitmap = decodeThumb(file);
                if (bitmap != null) {
                    return bitmap;
                }
            } catch (Throwable ignored) {
            }
        }
        if (source.inlineThumb != null && isInlineThumbAllowed(source.inlineThumb)) {
            Bitmap bitmap = null;
            try {
                if (source.inlineStripped) {
                    bitmap = ImageLoader.getStrippedPhotoBitmap(source.inlineThumb, "b");
                    if (bitmap != null && bitmap.getWidth() <= 1 && bitmap.getHeight() <= 1) {
                        bitmap = null;
                    }
                } else {
                    bitmap = BitmapFactory.decodeByteArray(
                            source.inlineThumb, 0, source.inlineThumb.length);
                }
            } catch (Throwable ignored) {
            }
            return bitmap == null ? null : downscale(bitmap);
        }
        return null;
    }

    private static Bitmap loadFromMemory(TLObject location) {
        for (String filter : MEMORY_FILTERS) {
            final BitmapDrawable drawable = ImageLoader.getInstance()
                    .getImageFromMemory(location, null, filter);
            if (drawable != null && drawable.getBitmap() != null) {
                final Bitmap copy = ownCopy(drawable.getBitmap());
                if (copy != null) {
                    return copy;
                }
            }
        }
        return null;
    }

    private static Bitmap ownCopy(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        try {
            final Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
            return copy == null ? null : downscale(copy);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap decodeThumb(File file) {
        if (file == null || !file.isFile() || file.length() <= 0
                || file.length() > MAX_THUMB_FILE_BYTES) {
            return null;
        }
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / sample > MAX_PREVIEW_WIDTH
                    || bounds.outHeight / sample > MAX_PREVIEW_WIDTH) {
                sample *= 2;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            final Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return bitmap == null ? null : downscale(bitmap);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap downscale(Bitmap source) {
        return HgramNotificationStyle.isMiuiFamily()
                ? prepareMiuiPreview(source) : downscaleAosp(source);
    }

    private static Bitmap downscaleAosp(Bitmap source) {
        if (source == null) {
            return null;
        }
        final int width = source.getWidth();
        final int height = source.getHeight();
        if (width <= MAX_PREVIEW_WIDTH && height <= MAX_PREVIEW_WIDTH) {
            return source;
        }
        final float scale = Math.min(MAX_PREVIEW_WIDTH / (float) width,
                MAX_PREVIEW_WIDTH / (float) height);
        final int targetWidth = Math.max(1, Math.round(width * scale));
        final int targetHeight = Math.max(1, Math.round(height * scale));
        final Bitmap result = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        if (result != source) {
            source.recycle();
        }
        return result;
    }

    /**
     * MIUI/HyperOS rich-picture geometry: 984x450, centered with letterbox and encoded as
     * JPEG q=85 under 1 MB. Only explicit CUSTOM layouts use the raw 984x450 bitmap; SYSTEM
     * notifications get the same prepared bitmap through BigPictureStyle.
     */
    static Bitmap prepareMiuiPreview(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        try {
            final int width = source.getWidth();
            final int height = source.getHeight();
            if (width <= 0 || height <= 0) {
                return source;
            }
            final float scale = Math.min(MIUI_PREVIEW_WIDTH / (float) width,
                    MIUI_PREVIEW_HEIGHT / (float) height);
            final int targetWidth = Math.max(1, Math.round(width * scale));
            final int targetHeight = Math.max(1, Math.round(height * scale));
            final Bitmap canvas = Bitmap.createBitmap(MIUI_PREVIEW_WIDTH,
                    MIUI_PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
            final Canvas graphics = new Canvas(canvas);
            graphics.drawColor(Color.BLACK);
            final Bitmap scaled = targetWidth == width && targetHeight == height
                    ? source : Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
            graphics.drawBitmap(scaled, (MIUI_PREVIEW_WIDTH - targetWidth) / 2f,
                    (MIUI_PREVIEW_HEIGHT - targetHeight) / 2f, null);
            if (scaled != source) {
                scaled.recycle();
            }
            source.recycle();

            final byte[] jpeg = compressJpeg(canvas);
            if (jpeg != null) {
                final Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (decoded != null) {
                    canvas.recycle();
                    return decoded;
                }
            }
            return canvas;
        } catch (Throwable t) {
            FileLog.e("HgramNotificationMedia: MIUI preview preparation failed");
            return source.isRecycled() ? null : downscaleAosp(source);
        }
    }

    static byte[] compressJpeg(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int quality = MIUI_JPEG_QUALITY;
        try {
            while (quality >= 50) {
                output.reset();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    return null;
                }
                if (output.size() <= MAX_MIUI_PREVIEW_BYTES) {
                    return output.toByteArray();
                }
                quality -= 5;
            }
            return output.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
