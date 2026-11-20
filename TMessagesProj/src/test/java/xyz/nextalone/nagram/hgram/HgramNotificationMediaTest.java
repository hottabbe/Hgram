package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramNotificationMediaTest {

    private Application context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        SharedConfig.passcodeHash = "";
        HgramSecretSpace.setHidden(0, 42, false);
        HgramConfig.reset();
    }

    @After
    public void tearDown() {
        HgramNotificationMedia.setPreviewLoaderForTest(null);
        HgramNotificationMedia.blockFetchForTests = false;
        HgramNotificationMedia.fetchBudgetForTest = HgramNotificationMedia.FETCH_BUDGET_MS;
        HgramConfig.reset();
        HgramSecretSpace.setHidden(0, 42, false);
    }

    private static TLRPC.TL_message photoMessage() {
        final TLRPC.TL_message message = new TLRPC.TL_message();
        final TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
        media.photo = new TLRPC.TL_photo();
        media.photo.sizes = new ArrayList<>();
        message.media = media;
        return message;
    }

    private static TLRPC.TL_message documentMessage(int attr) {
        final TLRPC.TL_message message = new TLRPC.TL_message();
        final TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = new TLRPC.TL_document();
        media.document.attributes = new ArrayList<>();
        if (attr == 1) {
            media.document.attributes.add(new TLRPC.TL_documentAttributeVideo());
        } else if (attr == 2) {
            media.document.mime_type = "image/gif";
        } else if (attr == 3) {
            media.document.attributes.add(new TLRPC.TL_documentAttributeSticker());
            media.document.mime_type = "image/webp";
        }
        message.media = media;
        return message;
    }

    private static TLRPC.TL_photoSize cacheSize() {
        final TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.location = new TLRPC.TL_fileLocationToBeDeprecated();
        size.location.volume_id = 11;
        size.location.local_id = 22;
        size.w = 800;
        size.h = 600;
        size.size = 128;
        return size;
    }

    @Test
    public void classifiesSupportedAndUnsupportedMessages() {
        assertEquals(HgramNotificationMedia.TYPE_PHOTO,
                HgramNotificationMedia.classify(photoMessage()));
        assertEquals(HgramNotificationMedia.TYPE_VIDEO,
                HgramNotificationMedia.classify(documentMessage(1)));
        assertEquals(HgramNotificationMedia.TYPE_GIF,
                HgramNotificationMedia.classify(documentMessage(2)));
        assertEquals(HgramNotificationMedia.TYPE_STICKER,
                HgramNotificationMedia.classify(documentMessage(3)));
        assertEquals(HgramNotificationMedia.TYPE_NONE,
                HgramNotificationMedia.classify(documentMessage(0)));
        assertEquals(HgramNotificationMedia.TYPE_NONE,
                HgramNotificationMedia.classify(new TLRPC.TL_message()));
    }

    @Test
    public void albumTakesFirstPreviewElement() {
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        messages.add(new TLRPC.TL_message());
        messages.add(documentMessage(0));
        messages.add(photoMessage());
        messages.add(documentMessage(1));
        assertNotNull(HgramNotificationMedia.findFirstPreviewMessage(messages));
        assertEquals(HgramNotificationMedia.TYPE_PHOTO,
                HgramNotificationMedia.classify(HgramNotificationMedia.findFirstPreviewMessage(messages)));
        assertNull(HgramNotificationMedia.findFirstPreviewMessage(Collections.emptyList()));
    }

    @Test
    public void photoWithoutCacheAndDocumentWithoutThumbReturnNull() {
        assertNull(HgramNotificationMedia.findPreviewSize(photoMessage()));
        assertNull(HgramNotificationMedia.findPreviewSize(documentMessage(1)));
    }

    @Test
    public void cachedPhotoAndStickerThumbAreUsed() {
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        HgramNotificationMedia.setPreviewLoaderForTest((account, message) -> {
            calls.incrementAndGet();
            return null;
        });
        final TLRPC.TL_message photo = photoMessage();
        photo.media.photo.sizes.add(cacheSize());
        assertNotNull(HgramNotificationMedia.findPreviewSize(photo));
        HgramNotificationMedia.loadCachedPreview(0, null);

        final TLRPC.TL_message sticker = documentMessage(3);
        sticker.media.document.thumbs = new ArrayList<>();
        sticker.media.document.thumbs.add(cacheSize());
        assertNotNull(HgramNotificationMedia.findPreviewSize(sticker));
        HgramNotificationMedia.loadCachedPreview(0, null);
        assertEquals(2, calls.get());
        HgramNotificationMedia.setPreviewLoaderForTest(null);
    }


    @Test
    public void previewBitmapIsOwnedAndSurvivesCacheRecycle() {
        final Bitmap source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        HgramNotificationMedia.setPreviewLoaderForTest((account, message) -> source);
        final Bitmap owned = HgramNotificationMedia.loadCachedPreview(0, null);
        assertNotNull(owned);
        assertNotSame(source, owned);
        source.recycle();
        assertFalse(owned.isRecycled());
        assertTrue(owned.getWidth() > 0);
    }

    @Test
    public void memoryFiltersPreferLargerImages() {
        final String[] filters = HgramNotificationMedia.memoryFiltersForTest();
        assertEquals("800_800", filters[0]);
        assertEquals("50_50", filters[filters.length - 1]);
    }

    @Test
    public void passcodeDoesNotDisablePreviewGlobally() {
        SharedConfig.passcodeHash = "hash";
        assertTrue(HgramNotificationMedia.canPreview(0, 42));
        SharedConfig.passcodeHash = "";
    }

    @Test
    public void fetchRequestsAreDeduplicatedAndCancelled() {
        HgramNotificationMedia.blockFetchForTests = true;
        final TLRPC.TL_message photo = photoMessage();
        photo.media.photo.sizes.add(cacheSize());
        assertTrue(HgramNotificationMedia.requestPreview(0, 43, photo, 100, (key, bitmap) -> {}));
        assertFalse(HgramNotificationMedia.requestPreview(0, 43, photo, 100, (key, bitmap) -> {}));
        HgramNotificationMedia.cancelPreviewsForDialog(0, 43);
        assertTrue(HgramNotificationMedia.requestPreview(0, 43, photo, 100, (key, bitmap) -> {}));
        HgramNotificationMedia.cancelAllPreviews();
        HgramNotificationMedia.blockFetchForTests = false;
    }


    @Test
    public void stalePreviewKeyIsRejected() {
        HgramNotificationMedia.blockFetchForTests = true;
        final TLRPC.TL_message photo = photoMessage();
        photo.media.photo.sizes.add(cacheSize());
        assertTrue(HgramNotificationMedia.requestPreview(0, 45, photo, 200, (key, bitmap) -> {}));
        final String first = HgramNotificationMedia.currentPreviewKeyForTest(0, 45);
        assertTrue(HgramNotificationMedia.isCurrentPreview(first));
        assertTrue(HgramNotificationMedia.requestPreview(0, 45, photo, 201, (key, bitmap) -> {}));
        final String second = HgramNotificationMedia.currentPreviewKeyForTest(0, 45);
        assertNotSame(first, second);
        assertFalse(HgramNotificationMedia.isCurrentPreview(first));
        assertTrue(HgramNotificationMedia.isCurrentPreview(second));
        HgramNotificationMedia.cancelPreviewsForDialog(0, 45);
        assertNull(HgramNotificationMedia.currentPreviewKeyForTest(0, 45));
        HgramNotificationMedia.blockFetchForTests = false;
    }


    @Test
    public void chooseLargestCachedPhotoSize() {
        final TLRPC.TL_photoSize small = cacheSize();
        small.w = 90;
        small.h = 90;
        final TLRPC.TL_photoSize medium = cacheSize();
        medium.w = 320;
        medium.h = 240;
        final TLRPC.TL_photoSize large = cacheSize();
        large.w = 800;
        large.h = 600;
        final ArrayList<TLRPC.PhotoSize> sizes = new ArrayList<>();
        sizes.add(small);
        sizes.add(medium);
        sizes.add(large);
        assertEquals(800, HgramNotificationMedia.choosePreviewSize(sizes).w);
    }


    @Test
    public void smallGifThumbAndInlineStickerStillProducePreview() {
        final TLRPC.TL_message gif = documentMessage(2);
        gif.media.document.thumbs = new ArrayList<>();
        final TLRPC.TL_photoSize smallGif = new TLRPC.TL_photoSize();
        smallGif.location = new TLRPC.TL_fileLocationToBeDeprecated();
        smallGif.location.volume_id = 1;
        smallGif.location.local_id = 2;
        smallGif.w = 320;
        smallGif.h = 180;
        smallGif.size = 64;
        gif.media.document.thumbs.add(smallGif);
        final TLRPC.PhotoSize gifPreview = HgramNotificationMedia.findPreviewSize(gif);
        assertNotNull(gifPreview);
        assertEquals(320, gifPreview.w);

        final TLRPC.TL_message sticker = documentMessage(3);
        sticker.media.document.thumbs = new ArrayList<>();
        final TLRPC.TL_photoSize inline = new TLRPC.TL_photoSize();
        inline.w = 100;
        inline.h = 100;
        inline.bytes = new byte[]{1, 2, 3, 4};
        sticker.media.document.thumbs.add(inline);
        assertNotNull(HgramNotificationMedia.findPreviewSize(sticker));
        assertTrue(HgramNotificationMedia.isInlineThumbAllowed(inline.bytes));
        assertFalse(HgramNotificationMedia.isInlineThumbAllowed(
                new byte[HgramNotificationMedia.MAX_INLINE_THUMB_BYTES + 1]));
    }

    @Test
    public void animatedMp4PrefersUsableThumbAndFallsBackToVideoThumb() {
        final TLRPC.TL_message withThumb = documentMessage(0);
        withThumb.media.document.mime_type = "video/mp4";
        withThumb.media.document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        withThumb.media.document.thumbs = new ArrayList<>();
        withThumb.media.document.thumbs.add(cacheSize());
        withThumb.media.document.video_thumbs = new ArrayList<>();
        final TLRPC.TL_videoSize video = new TLRPC.TL_videoSize();
        video.location = new TLRPC.TL_fileLocationToBeDeprecated();
        video.w = 320;
        video.h = 180;
        video.size = 64;
        withThumb.media.document.video_thumbs.add(video);
        final HgramNotificationMedia.Source thumbSource =
                HgramNotificationMedia.findSource(withThumb);
        assertNotNull(thumbSource);
        assertNotNull(thumbSource.photoSize);
        assertNull(thumbSource.videoSize);

        final TLRPC.TL_message videoOnly = documentMessage(0);
        videoOnly.media.document.mime_type = "video/mp4";
        videoOnly.media.document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        videoOnly.media.document.thumbs = new ArrayList<>();
        final TLRPC.TL_photoSize unusable = new TLRPC.TL_photoSize();
        unusable.w = 800;
        unusable.h = 600;
        videoOnly.media.document.thumbs.add(unusable);
        videoOnly.media.document.video_thumbs = new ArrayList<>();
        videoOnly.media.document.video_thumbs.add(video);
        final HgramNotificationMedia.Source videoSource =
                HgramNotificationMedia.findSource(videoOnly);
        assertNotNull(videoSource);
        assertNull(videoSource.photoSize);
        assertNotNull(videoSource.videoSize);
    }

    @Test
    public void smallDocumentThumbWinsOverVideoThumb() {
        final TLRPC.TL_message gif = documentMessage(2);
        gif.media.document.thumbs = new ArrayList<>();
        final TLRPC.TL_photoSize small = new TLRPC.TL_photoSize();
        small.location = new TLRPC.TL_fileLocationToBeDeprecated();
        small.w = 320;
        small.h = 180;
        small.size = 64;
        gif.media.document.thumbs.add(small);
        gif.media.document.video_thumbs = new ArrayList<>();
        final TLRPC.TL_videoSize video = new TLRPC.TL_videoSize();
        video.location = new TLRPC.TL_fileLocationToBeDeprecated();
        video.w = 800;
        video.h = 450;
        video.size = 128;
        gif.media.document.video_thumbs.add(video);

        final HgramNotificationMedia.Source source = HgramNotificationMedia.findSource(gif);
        assertNotNull(source);
        assertNotNull(source.photoSize);
        assertNull(source.videoSize);
    }

    @Test
    public void strippedAndInvalidInlineThumbsFallBackWithoutBitmap() {
        final TLRPC.TL_message gif = documentMessage(2);
        gif.media.document.thumbs = new ArrayList<>();
        final TLRPC.TL_photoStrippedSize stripped = new TLRPC.TL_photoStrippedSize();
        stripped.bytes = new byte[]{1, 2, 3, 4};
        gif.media.document.thumbs.add(stripped);
        final HgramNotificationMedia.Source source = HgramNotificationMedia.findSource(gif);
        assertNotNull(source);
        assertTrue(source.inlineStripped);
        assertNull(HgramNotificationMedia.loadSourceForTest(source));

        final HgramNotificationMedia.Source invalid = new HgramNotificationMedia.Source(
                null, null, null, null, new byte[]{9, 9, 9}, true);
        assertNull(HgramNotificationMedia.loadSourceForTest(invalid));
    }

    @Test
    public void seriesFindsMediaAnywhereAndKeepsLastTextForContent() {
        final ArrayList<TLRPC.Message> photoThenText = new ArrayList<>();
        photoThenText.add(photoMessage());
        photoThenText.add(new TLRPC.TL_message());
        assertEquals(HgramNotificationMedia.TYPE_PHOTO,
                HgramNotificationMedia.classify(
                        HgramNotificationMedia.findNotificationPreviewMessage(photoThenText)));

        final ArrayList<TLRPC.Message> textThenPhoto = new ArrayList<>();
        textThenPhoto.add(new TLRPC.TL_message());
        textThenPhoto.add(photoMessage());
        assertEquals(HgramNotificationMedia.TYPE_PHOTO,
                HgramNotificationMedia.classify(
                        HgramNotificationMedia.findNotificationPreviewMessage(textThenPhoto)));

        final ArrayList<TLRPC.Message> photoTextText = new ArrayList<>();
        photoTextText.add(photoMessage());
        photoTextText.add(new TLRPC.TL_message());
        photoTextText.add(new TLRPC.TL_message());
        assertEquals(HgramNotificationMedia.TYPE_PHOTO,
                HgramNotificationMedia.classify(
                        HgramNotificationMedia.findNotificationPreviewMessage(photoTextText)));
    }

    @Test
    public void photoPreviewStillPrefersLargeCandidateAndUsesSmallFallback() {
        final TLRPC.TL_photoSize onlySmall = cacheSize();
        onlySmall.w = 90;
        onlySmall.h = 90;
        final ArrayList<TLRPC.PhotoSize> smallOnly = new ArrayList<>();
        smallOnly.add(onlySmall);
        assertEquals(90, HgramNotificationMedia.findPreviewSize(
                photoWithSizes(smallOnly)).w);

        final TLRPC.TL_photoSize fourHundred = cacheSize();
        fourHundred.w = 400;
        fourHundred.h = 400;
        assertEquals(400, HgramNotificationMedia.choosePreviewSize(
                java.util.Collections.singletonList(fourHundred)).w);

        final TLRPC.TL_photoSize huge = cacheSize();
        huge.w = 1600;
        huge.h = 1600;
        assertEquals(1600, HgramNotificationMedia.choosePreviewSize(
                java.util.Collections.singletonList(huge)).w);
    }

    private static TLRPC.TL_message photoWithSizes(List<TLRPC.PhotoSize> sizes) {
        final TLRPC.TL_message message = photoMessage();
        message.media.photo.sizes = new ArrayList<>(sizes);
        return message;
    }

    @Test
    public void seriesPreviewUsesLastMessageMediaOrAlbumFirst() {
        final ArrayList<TLRPC.Message> series = new ArrayList<>();
        series.add(documentMessage(0));
        series.add(documentMessage(0));
        series.add(photoMessage());
        assertEquals(HgramNotificationMedia.TYPE_PHOTO,
                HgramNotificationMedia.classify(
                        HgramNotificationMedia.findNotificationPreviewMessage(series)));

        final ArrayList<TLRPC.Message> textOnly = new ArrayList<>();
        textOnly.add(new TLRPC.TL_message());
        textOnly.add(new TLRPC.TL_message());
        assertNull(HgramNotificationMedia.findNotificationPreviewMessage(textOnly));

        final TLRPC.TL_message albumFirst = photoMessage();
        albumFirst.grouped_id = 77;
        final TLRPC.TL_message albumLast = documentMessage(1);
        albumLast.grouped_id = 77;
        final ArrayList<TLRPC.Message> album = new ArrayList<>();
        album.add(albumFirst);
        album.add(albumLast);
        assertEquals(albumFirst, HgramNotificationMedia.findNotificationPreviewMessage(album));
    }


    @Test
    public void seriesChoosesNewestMediaRegardlessOfListOrder() {
        final TLRPC.TL_message fresh = photoMessage();
        fresh.id = 30;
        fresh.date = 300;
        final TLRPC.TL_message old = photoMessage();
        old.id = 10;
        old.date = 100;
        final TLRPC.TL_message text = new TLRPC.TL_message();
        text.id = 20;
        text.date = 200;

        final ArrayList<TLRPC.Message> newestFirst = new ArrayList<>();
        newestFirst.add(fresh);
        newestFirst.add(text);
        newestFirst.add(old);
        assertEquals(30, HgramNotificationMedia.findNotificationPreviewMessage(newestFirst).id);

        final ArrayList<TLRPC.Message> textFirst = new ArrayList<>();
        textFirst.add(text);
        textFirst.add(fresh);
        textFirst.add(old);
        assertEquals(30, HgramNotificationMedia.findNotificationPreviewMessage(textFirst).id);

        final ArrayList<TLRPC.Message> oldestFirst = new ArrayList<>();
        oldestFirst.add(old);
        oldestFirst.add(text);
        oldestFirst.add(fresh);
        assertEquals(30, HgramNotificationMedia.findNotificationPreviewMessage(oldestFirst).id);
    }

    @Test
    public void albumKeepsItsFirstElementAndLosesOnlyToNewerSeparateMedia() {
        final TLRPC.TL_message albumFirst = photoMessage();
        albumFirst.id = 10;
        albumFirst.date = 100;
        albumFirst.grouped_id = 77;
        final TLRPC.TL_message albumSecond = photoMessage();
        albumSecond.id = 20;
        albumSecond.date = 200;
        albumSecond.grouped_id = 77;
        final ArrayList<TLRPC.Message> album = new ArrayList<>();
        album.add(albumFirst);
        album.add(albumSecond);
        assertEquals(10, HgramNotificationMedia.findNotificationPreviewMessage(album).id);

        final TLRPC.TL_message separate = photoMessage();
        separate.id = 30;
        separate.date = 300;
        album.add(0, separate);
        assertEquals(30, HgramNotificationMedia.findNotificationPreviewMessage(album).id);
    }

    @Test
    public void hiddenAndAntiPeekDisablePreview() {
        assertTrue(HgramNotificationMedia.canPreview(0, 42));
        HgramConfig.setAntiPeekActive(true);
        assertFalse(HgramNotificationMedia.canPreview(0, 42));
        HgramConfig.setAntiPeekActive(false);

        HgramSecretSpace.toggle(0, 42);
        assertFalse(HgramNotificationMedia.canPreview(0, 42));
    }
    @Test
    public void overBudgetUsesInclusiveDeadline() {
        assertFalse(HgramNotificationMedia.overBudget(1000, 400, 1400));
        assertTrue(HgramNotificationMedia.overBudget(1000, 400, 1401));
        assertTrue(HgramNotificationMedia.overBudget(1000, 0, 1001));
    }

    @Test
    public void privacyRecheckRecyclesOnlyDeniedBitmap() {
        final android.graphics.Bitmap allowed = android.graphics.Bitmap.createBitmap(1, 1,
                android.graphics.Bitmap.Config.ARGB_8888);
        assertSame(allowed, HgramNotificationMedia.enforceAllowed(() -> true, allowed));
        assertFalse(allowed.isRecycled());

        final android.graphics.Bitmap denied = android.graphics.Bitmap.createBitmap(1, 1,
                android.graphics.Bitmap.Config.ARGB_8888);
        assertNull(HgramNotificationMedia.enforceAllowed(() -> false, denied));
        assertTrue(denied.isRecycled());
    }

    @Test
    public void fallbackSkipsEmptyAndOversizedInlineThumbs() {
        final TLRPC.TL_photoSize empty = new TLRPC.TL_photoSize();
        empty.w = 1000;
        empty.bytes = new byte[0];
        empty.location = null;

        final TLRPC.TL_photoSize oversized = new TLRPC.TL_photoSize();
        oversized.w = 900;
        oversized.bytes = new byte[HgramNotificationMedia.MAX_INLINE_THUMB_BYTES + 1];

        final TLRPC.TL_photoSize valid = new TLRPC.TL_photoSize();
        valid.w = 100;
        valid.bytes = new byte[]{1, 2, 3};

        assertSame(valid, HgramNotificationMedia.chooseFallbackPreviewSize(
                Arrays.asList(empty, oversized, valid)));
        assertNull(HgramNotificationMedia.chooseFallbackPreviewSize(
                Arrays.asList(empty, oversized)));

        assertFalse(HgramNotificationMedia.isInlineThumbAllowed(null));
        assertFalse(HgramNotificationMedia.isInlineThumbAllowed(new byte[0]));
        assertTrue(HgramNotificationMedia.isInlineThumbAllowed(new byte[]{1}));
        assertFalse(HgramNotificationMedia.isInlineThumbAllowed(
                new byte[HgramNotificationMedia.MAX_INLINE_THUMB_BYTES + 1]));

        // Same rejection at load time: empty and broken inline bytes never yield a bitmap.
        assertNull(HgramNotificationMedia.loadSourceForTest(new HgramNotificationMedia.Source(
                null, null, null, null, new byte[0], true)));
        assertNull(HgramNotificationMedia.loadSourceForTest(new HgramNotificationMedia.Source(
                null, null, null, null, new byte[]{9, 9, 9}, true)));
    }

    @Test
    public void strippedJpegIsSelectedAndDecoded() {
        final Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
        final byte[] jpeg = out.toByteArray();
        assertTrue(jpeg.length > 4);

        final byte[] strippedBytes = new byte[jpeg.length - 4 + 3];
        strippedBytes[0] = 0;
        strippedBytes[1] = 1;
        strippedBytes[2] = 1;
        System.arraycopy(jpeg, 2, strippedBytes, 3, jpeg.length - 4);

        final TLRPC.TL_message gif = documentMessage(2);
        gif.media.document.thumbs = new ArrayList<>();
        final TLRPC.TL_photoStrippedSize stripped = new TLRPC.TL_photoStrippedSize();
        stripped.bytes = strippedBytes;
        gif.media.document.thumbs.add(stripped);

        final HgramNotificationMedia.Source source = HgramNotificationMedia.findSource(gif);
        assertNotNull(source);
        assertTrue(source.inlineStripped);
        assertSame(stripped, source.photoSize);
        assertNotNull(HgramNotificationMedia.loadSourceForTest(source));
        bitmap.recycle();
    }

    @Test
    public void smallDocumentThumbLocationIsSelected() {
        final TLRPC.TL_photoSize small = new TLRPC.TL_photoSize();
        small.location = new TLRPC.TL_fileLocationToBeDeprecated();
        small.w = 64;
        small.h = 64;
        small.size = 32;

        final TLRPC.TL_message gif = documentMessage(2);
        gif.media.document.thumbs = new ArrayList<>();
        gif.media.document.thumbs.add(small);

        final HgramNotificationMedia.Source source = HgramNotificationMedia.findSource(gif);
        assertNotNull(source);
        assertSame(small, source.photoSize);
        assertNull(source.inlineThumb);
    }

}
