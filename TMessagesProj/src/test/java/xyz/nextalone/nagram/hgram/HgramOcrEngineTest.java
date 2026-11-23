package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramOcrEngineTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final class FakeApi implements HgramOcrEngine.Api {
        String datapath;
        String language;
        String text;
        Bitmap image;
        boolean initThrows;
        boolean textThrows;
        int initCalls;
        int stopCalls;
        int clearCalls;
        Runnable beforeText;
        Runnable beforeStop;

        @Override
        public void init(String datapath, String language) throws Exception {
            this.datapath = datapath;
            this.language = language;
            initCalls++;
            if (initThrows) {
                throw new IOException("init failed");
            }
        }

        @Override
        public void setImage(Bitmap bitmap) {
            image = bitmap;
        }

        @Override
        public String getUTF8Text() {
            if (beforeText != null) {
                beforeText.run();
            }
            if (textThrows) {
                throw new IllegalStateException("text failed");
            }
            return text;
        }

        @Override
        public void stop() {
            if (beforeStop != null) {
                beforeStop.run();
            }
            stopCalls++;
        }

        @Override
        public void clear() {
            clearCalls++;
        }
    }

    private static final class FakeBoxApi implements HgramOcrEngine.BoxApi {
        final FakeApi delegate = new FakeApi();
        HgramOcrLayout layout;
        boolean layoutThrows;
        int layoutCalls;

        @Override
        public void init(String datapath, String language) throws Exception {
            delegate.init(datapath, language);
        }

        @Override
        public void setImage(Bitmap bitmap) {
            delegate.setImage(bitmap);
        }

        @Override
        public String getUTF8Text() {
            return delegate.getUTF8Text();
        }

        @Override
        public HgramOcrLayout readPixelLayout() {
            layoutCalls++;
            if (layoutThrows) {
                throw new IllegalStateException("boxes failed");
            }
            return layout;
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }

    private static HgramOcrLayout layoutWithTwoLines(int width, int height) {
        final List<HgramOcrLayout.PixelLine> pixelLines = new ArrayList<>();
        final HgramOcrLayout.PixelLine first = new HgramOcrLayout.PixelLine(
                new int[]{0, 0, width, height / 2}, "First line");
        first.words.add(new HgramOcrLayout.PixelWord(
                new int[]{0, 0, width / 2, height / 2}, "First"));
        pixelLines.add(first);
        final HgramOcrLayout.PixelLine second = new HgramOcrLayout.PixelLine(
                new int[]{0, height / 2, width, height}, "Second line");
        second.words.add(new HgramOcrLayout.PixelWord(
                new int[]{0, height / 2, width / 2, height}, "Second"));
        pixelLines.add(second);
        return HgramOcrLayout.fromPixels(width, height,
                HgramOcrLayout.SPACE_DISPLAY, pixelLines);
    }

    private HgramOcrEngine newEngine(File filesDir) {
        return HgramOcrEngine.forTests(FakeApi::new, filesDir);
    }

    private static Bitmap bitmap(int width, int height) {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    @Test
    public void initUsesExactLanguageAndHgramDatapath() throws Exception {
        final File filesDir = temporary.newFolder("files");
        final HgramOcrEngine engine = newEngine(filesDir);
        final FakeApi fake = new FakeApi();
        fake.text = "text";

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertEquals("rus+eng", fake.language);
        assertEquals(new File(filesDir, "hgram_tessdata").getAbsolutePath(), fake.datapath);
        assertTrue(fake.datapath.endsWith("hgram_tessdata"));
        assertEquals(1, fake.initCalls);
    }

    @Test
    public void recognizeWithoutModelsReturnsNeedModels() throws Exception {
        final Application context = RuntimeEnvironment.getApplication();
        HgramOcrModels.delete(context);
        final AtomicInteger created = new AtomicInteger();
        final HgramOcrEngine engine = HgramOcrEngine.forTests(() -> {
            created.incrementAndGet();
            return new FakeApi();
        }, context.getFilesDir());
        final File file = temporary.newFile("photo.jpg");

        engine.beginRequest(7);
        final HgramOcrEngine.Result viaFile = engine.recognize(context, file, 7);
        final HgramOcrEngine.Result viaBitmap = engine.recognize(context, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.NEED_MODELS, viaFile.status);
        assertEquals(HgramOcrEngine.Status.NEED_MODELS, viaBitmap.status);
        assertFalse(viaFile.cancelled);
        assertFalse(viaBitmap.cancelled);
        assertEquals("", viaFile.text);
        assertEquals("no engine may be created before models are ready", 0, created.get());
    }

    @Test
    public void staleRequestIdIsDroppedAndOldCancelDoesNotStopNewRequest() throws Exception {
        final AtomicInteger created = new AtomicInteger();
        final HgramOcrEngine engine = HgramOcrEngine.forTests(() -> {
            created.incrementAndGet();
            return new FakeApi();
        }, temporary.newFolder("token-files"));
        final File file = temporary.newFile("photo.jpg");
        write(file, new byte[]{1, 2, 3});

        engine.beginRequest(2);
        final HgramOcrEngine.Result stale = engine.recognize(null, file, 1);

        assertEquals(HgramOcrEngine.Status.FAILED, stale.status);
        assertTrue(stale.cancelled);
        assertEquals("stale request must not create or init an engine", 0, created.get());

        final FakeApi current = new FakeApi();
        current.text = "new request";
        engine.recognize(current, bitmap(10, 10));
        final int stopsAfterRecognition = current.stopCalls;
        engine.cancel(1);
        assertEquals("old cancel must not stop the current engine", stopsAfterRecognition, current.stopCalls);
        engine.cancel(2);
        assertEquals("no native owner remains after recognition",
                stopsAfterRecognition, current.stopCalls);
    }

    @Test
    public void cancelBeforeWorkerStartsDoesNotCreateApiOrDecode() throws Exception {
        final AtomicInteger created = new AtomicInteger();
        final HgramOcrEngine engine = HgramOcrEngine.forTests(() -> {
            created.incrementAndGet();
            return new FakeApi();
        }, temporary.newFolder("cancel-before-files"));
        final File file = temporary.newFile("photo.jpg");
        write(file, new byte[]{1, 2, 3});

        final HgramOcrEngine.RequestToken token = engine.newRequest();
        engine.cancel(token);

        assertFalse(engine.isRequestActive(token));
        final HgramOcrEngine.Result viaFile = engine.recognize((Context) null, file, token);
        final HgramOcrEngine.Result viaBitmap = engine.recognize((Context) null, bitmap(10, 10), token);

        assertEquals(HgramOcrEngine.Status.FAILED, viaFile.status);
        assertEquals(HgramOcrEngine.Status.FAILED, viaBitmap.status);
        assertTrue(viaFile.cancelled);
        assertTrue(viaBitmap.cancelled);
        assertEquals("cancelled token must never create an engine", 0, created.get());
    }

    @Test
    public void cancelThenFinishStopsNativeBeforeReleasingState() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("cancel-finish"));
        final FakeApi fake = new FakeApi();
        fake.text = "late";
        final CountDownLatch textStarted = new CountDownLatch(1);
        final CountDownLatch releaseText = new CountDownLatch(1);
        final CountDownLatch stopEntered = new CountDownLatch(1);
        final CountDownLatch releaseStop = new CountDownLatch(1);
        final AtomicInteger stopAttempts = new AtomicInteger();
        fake.beforeText = () -> {
            textStarted.countDown();
            await(releaseText);
        };
        fake.beforeStop = () -> {
            if (stopAttempts.getAndIncrement() == 0) {
                stopEntered.countDown();
                await(releaseStop);
            }
        };
        final HgramOcrEngine.RequestToken token = engine.newRequest();
        final Thread worker = new Thread(
                () -> engine.recognize(fake, bitmap(10, 10), token),
                "ocr-token-worker");
        worker.start();
        assertTrue(textStarted.await(5, TimeUnit.SECONDS));

        engine.cancel(token);
        engine.finish(token);
        assertTrue("native stop must reach the control worker",
                stopEntered.await(5, TimeUnit.SECONDS));
        assertEquals("token state must survive until stop returns",
                1, engine.requestCountForTests());

        releaseStop.countDown();
        awaitRequestCount(engine, 0);
        releaseText.countDown();
        worker.join(5_000L);
        assertFalse(worker.isAlive());
    }

    @Test
    public void releaseWaitsForStartedNativeStopBeforeClear() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("release-barrier"));
        final FakeApi fake = new FakeApi();
        fake.text = "late";
        final CountDownLatch textStarted = new CountDownLatch(1);
        final CountDownLatch releaseText = new CountDownLatch(1);
        final CountDownLatch stopEntered = new CountDownLatch(1);
        final CountDownLatch releaseStop = new CountDownLatch(1);
        fake.beforeText = () -> {
            textStarted.countDown();
            await(releaseText);
        };
        fake.beforeStop = () -> {
            stopEntered.countDown();
            await(releaseStop);
        };
        final HgramOcrEngine.RequestToken token = engine.newRequest();
        final Thread worker = new Thread(
                () -> engine.recognize(fake, bitmap(10, 10), token),
                "ocr-release-worker");
        final CountDownLatch releaseDone = new CountDownLatch(1);
        final Thread releaser = new Thread(() -> {
            engine.release();
            releaseDone.countDown();
        }, "ocr-release");
        try {
            worker.start();
            assertTrue(textStarted.await(5, TimeUnit.SECONDS));
            engine.finish(token);
            releaseText.countDown();
            assertTrue("native stop must start", stopEntered.await(5, TimeUnit.SECONDS));
            releaser.start();
            assertEquals("release must wait for the native stop", 1, releaseDone.getCount());
            assertEquals("clear must not overtake stop", 0, fake.clearCalls);
        } finally {
            releaseStop.countDown();
            releaseText.countDown();
            worker.join(5_000L);
            releaser.join(5_000L);
        }
        assertTrue("release must complete after stop", releaseDone.await(5, TimeUnit.SECONDS));
        assertFalse(releaser.isAlive());
        assertTrue("API must be cleared after the stop", fake.clearCalls >= 1);
    }

    @Test
    public void legacyStopDoesNotAffectNextTokenOnSameApi() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("legacy-stop"));
        final FakeApi fake = new FakeApi();
        fake.text = "legacy";
        final AtomicInteger textCalls = new AtomicInteger();
        final CountDownLatch legacyText = new CountDownLatch(1);
        final CountDownLatch releaseLegacyText = new CountDownLatch(1);
        final CountDownLatch newText = new CountDownLatch(1);
        final CountDownLatch releaseNewText = new CountDownLatch(1);
        fake.beforeText = () -> {
            if (textCalls.incrementAndGet() == 1) {
                legacyText.countDown();
                await(releaseLegacyText);
            } else {
                newText.countDown();
                await(releaseNewText);
            }
        };
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        final AtomicReference<HgramOcrEngine.Result> newResult = new AtomicReference<>();
        final Thread legacyWorker = new Thread(
                () -> engine.recognize(fake, bitmap(10, 10)),
                "ocr-legacy");
        final HgramOcrEngine.RequestToken newToken = engine.newRequest();
        final Thread newWorker = new Thread(
                () -> newResult.set(engine.recognize(fake, bitmap(10, 10), newToken)),
                "ocr-new-after-legacy");
        try {
            legacyWorker.start();
            assertTrue(legacyText.await(5, TimeUnit.SECONDS));
            final CountDownLatch blockerEntered = new CountDownLatch(1);
            engine.submitControlTaskForTests(() -> {
                blockerEntered.countDown();
                await(releaseBlocker);
            });
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
            engine.cancel();
            releaseLegacyText.countDown();
            legacyWorker.join(5_000L);
            assertFalse(legacyWorker.isAlive());
            fake.stopCalls = 0;

            newWorker.start();
            assertTrue(newText.await(5, TimeUnit.SECONDS));
            final CountDownLatch marker = new CountDownLatch(1);
            engine.submitControlTaskForTests(marker::countDown);
            releaseBlocker.countDown();
            assertTrue(marker.await(5, TimeUnit.SECONDS));
            assertEquals("legacy stop must not stop the new token", 0, fake.stopCalls);
            assertTrue(engine.isRequestActive(newToken));
        } finally {
            releaseLegacyText.countDown();
            releaseNewText.countDown();
            releaseBlocker.countDown();
            legacyWorker.join(5_000L);
            newWorker.join(5_000L);
        }
        assertFalse(newWorker.isAlive());
        assertNotNull(newResult.get());
        assertEquals(HgramOcrEngine.Status.OK, newResult.get().status);
        assertEquals(1, fake.stopCalls);
    }

    @Test
    public void staleStopDoesNotAffectNextTokenOnSameApi() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("stale-stop"));
        final FakeApi fake = new FakeApi();
        fake.text = "late";
        final AtomicInteger textCalls = new AtomicInteger();
        final CountDownLatch oldText = new CountDownLatch(1);
        final CountDownLatch releaseOldText = new CountDownLatch(1);
        final CountDownLatch newText = new CountDownLatch(1);
        final CountDownLatch releaseNewText = new CountDownLatch(1);
        fake.beforeText = () -> {
            if (textCalls.incrementAndGet() == 1) {
                oldText.countDown();
                await(releaseOldText);
            } else {
                newText.countDown();
                await(releaseNewText);
            }
        };
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        final AtomicReference<HgramOcrEngine.Result> newResult = new AtomicReference<>();
        final HgramOcrEngine.RequestToken oldToken = engine.newRequest();
        final HgramOcrEngine.RequestToken newToken = engine.newRequest();
        final Thread oldWorker = new Thread(
                () -> engine.recognize(fake, bitmap(10, 10), oldToken),
                "ocr-old-token");
        final Thread newWorker = new Thread(
                () -> newResult.set(engine.recognize(fake, bitmap(10, 10), newToken)),
                "ocr-new-token");
        try {
            oldWorker.start();
            assertTrue(oldText.await(5, TimeUnit.SECONDS));

            final CountDownLatch blockerEntered = new CountDownLatch(1);
            engine.submitControlTaskForTests(() -> {
                blockerEntered.countDown();
                await(releaseBlocker);
            });
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));
            engine.cancel(oldToken);
            engine.finish(oldToken);
            releaseOldText.countDown();
            oldWorker.join(5_000L);
            assertFalse(oldWorker.isAlive());
            fake.stopCalls = 0;

            newWorker.start();
            assertTrue("new OCR must be inside getUTF8Text", newText.await(5, TimeUnit.SECONDS));
            assertTrue("new request must own the native phase",
                    engine.isRequestActive(newToken));

            final CountDownLatch marker = new CountDownLatch(1);
            engine.submitControlTaskForTests(marker::countDown);
            releaseBlocker.countDown();
            assertTrue("control worker must reach FIFO marker", marker.await(5, TimeUnit.SECONDS));
            assertEquals("stale stop must not touch the new owner", 0, fake.stopCalls);
            assertEquals("old state must be gone, new state retained",
                    1, engine.requestCountForTests());
            assertTrue(engine.isRequestActive(newToken));
        } finally {
            releaseOldText.countDown();
            releaseNewText.countDown();
            releaseBlocker.countDown();
            oldWorker.join(5_000L);
            newWorker.join(5_000L);
        }
        assertFalse(newWorker.isAlive());
        assertNotNull(newResult.get());
        assertEquals(HgramOcrEngine.Status.OK, newResult.get().status);
        assertEquals(1, fake.stopCalls);
    }

    @Test
    public void twoViewersCannotProduceSameEngineToken() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("two-viewers"));

        final HgramOcrEngine.RequestToken first = engine.newRequest();
        final HgramOcrEngine.RequestToken second = engine.newRequest();

        assertTrue(first.id > 0);
        assertTrue(second.id > 0);
        assertNotEquals(first.id, second.id);
        assertTrue(engine.isRequestActive(first));
        assertTrue(engine.isRequestActive(second));

        engine.cancel(first);

        assertFalse(engine.isRequestActive(first));
        assertTrue("one viewer cannot cancel another viewer's token", engine.isRequestActive(second));
    }

    @Test
    public void repeatRequestCannotPublishTwice() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("repeat-token"));
        final FakeApi fake = new FakeApi();
        fake.text = "once";
        final HgramOcrEngine.RequestToken token = engine.newRequest();

        final HgramOcrEngine.Result first = engine.recognize(fake, bitmap(10, 10), token);
        final HgramOcrEngine.Result second = engine.recognize(fake, bitmap(10, 10), token);

        assertEquals(HgramOcrEngine.Status.OK, first.status);
        assertEquals("once", first.text);
        assertEquals(HgramOcrEngine.Status.FAILED, second.status);
        assertTrue(second.cancelled);
        assertEquals("a consumed token must not run the engine again", 1, fake.initCalls);
    }

    @Test
    public void initFailureReturnsFailedWithoutThrowing() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());
        final FakeApi fake = new FakeApi();
        fake.initThrows = true;

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.FAILED, result.status);
        assertFalse(result.cancelled);
        assertEquals("", result.text);
    }

    @Test
    public void textFailureReturnsFailed() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());
        final FakeApi fake = new FakeApi();
        fake.textThrows = true;

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.FAILED, result.status);
        assertFalse(result.cancelled);
    }

    @Test
    public void blankTextReturnsEmpty() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());
        final String[] blanks = {null, "", "   ", "\n\t\n  \n"};
        for (String blank : blanks) {
            final FakeApi fake = new FakeApi();
            fake.text = blank;
            final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));
            assertEquals("for text " + blank, HgramOcrEngine.Status.EMPTY, result.status);
            assertEquals("", result.text);
        }
    }

    @Test
    public void okTextIsJoinedInReadingOrder() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());
        final FakeApi fake = new FakeApi();
        fake.text = "Первая строка\n\n  \nВторая строка\n";

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertEquals("Первая строка\nВторая строка", result.text);
        assertFalse(result.cancelled);
    }

    @Test
    public void boxApiLayoutIsReturnedWithOkText() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("box-layout"));
        final FakeBoxApi fake = new FakeBoxApi();
        fake.delegate.text = "First line\nSecond line";
        fake.layout = layoutWithTwoLines(100, 100);

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(100, 100));

        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertEquals("First line\nSecond line", result.text);
        assertSame(fake.layout, result.layout);
        assertEquals(2, result.layout.lines.size());
        assertEquals(100, result.layout.imageWidth);
        assertEquals(100, result.layout.imageHeight);
        assertEquals(HgramOcrLayout.SPACE_DISPLAY, result.layout.space);
        assertEquals(1, fake.layoutCalls);
        assertEquals(1, fake.delegate.stopCalls);
        assertEquals(1, fake.delegate.clearCalls);
    }

    @Test
    public void nullBoxLayoutKeepsOkResult() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("box-null"));
        final FakeBoxApi fake = new FakeBoxApi();
        fake.delegate.text = "text without boxes";
        fake.layout = null;

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertEquals("text without boxes", result.text);
        assertNull(result.layout);
        assertEquals(1, fake.layoutCalls);
    }

    @Test
    public void throwingBoxApiKeepsOkResult() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("box-throws"));
        final FakeBoxApi fake = new FakeBoxApi();
        fake.delegate.text = "text survives";
        fake.layoutThrows = true;

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertEquals("text survives", result.text);
        assertNull(result.layout);
        assertEquals(1, fake.layoutCalls);
    }

    @Test
    public void cancelBeforeBoxesSkipsLayout() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder("box-cancel"));
        final FakeBoxApi fake = new FakeBoxApi();
        fake.delegate.text = "should be dropped";
        fake.delegate.beforeText = engine::cancel;
        fake.layout = layoutWithTwoLines(10, 10);

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.FAILED, result.status);
        assertTrue(result.cancelled);
        assertNull(result.layout);
        assertEquals("cancelled request must not read boxes", 0, fake.layoutCalls);
    }

    @Test
    public void stopAndClearRunInFinallyForEveryStatus() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());

        final FakeApi ok = new FakeApi();
        ok.text = "line";
        engine.recognize(ok, bitmap(10, 10));

        final FakeApi empty = new FakeApi();
        empty.text = "  ";
        engine.recognize(empty, bitmap(10, 10));

        final FakeApi failed = new FakeApi();
        failed.initThrows = true;
        engine.recognize(failed, bitmap(10, 10));

        for (FakeApi fake : new FakeApi[]{ok, empty, failed}) {
            assertEquals(1, fake.stopCalls);
            assertEquals(1, fake.clearCalls);
        }
    }

    @Test
    public void largeBitmapIsDownscaledTo1600AndSourceIsKept() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());
        final FakeApi fake = new FakeApi();
        fake.text = "text";
        final Bitmap source = bitmap(4000, 3000);

        final HgramOcrEngine.Result result = engine.recognize(fake, source);

        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertNotSame(source, fake.image);
        assertEquals(1600, fake.image.getWidth());
        assertEquals(1200, fake.image.getHeight());
        assertTrue(fake.image.isRecycled());
        assertFalse(source.isRecycled());
        assertEquals(4000, source.getWidth());
        assertEquals(3000, source.getHeight());
    }

    @Test
    public void smallBitmapIsNotUpscaledAndIsNotRecycled() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());
        final FakeApi fake = new FakeApi();
        fake.text = "text";
        final Bitmap source = bitmap(1000, 800);

        engine.recognize(fake, source);

        assertSame(source, fake.image);
        assertFalse(source.isRecycled());
        assertEquals(1000, source.getWidth());
        assertEquals(800, source.getHeight());
    }

    @Test
    public void ocrTextHelpersKeepHgramDownscaleContract() {
        assertEquals(1600, HgramOcrText.scaledLongSide(4000, 3000, HgramOcrEngine.MAX_LONG_SIDE));
        assertEquals(1200, HgramOcrText.scaledShortSide(4000, 3000, HgramOcrEngine.MAX_LONG_SIDE));
        assertEquals(1000, HgramOcrText.scaledLongSide(1000, 800, HgramOcrEngine.MAX_LONG_SIDE));
        assertEquals(800, HgramOcrText.scaledShortSide(1000, 800, HgramOcrEngine.MAX_LONG_SIDE));
    }

    @Test
    public void cancelMarksResultCancelledAndStopsEngine() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());
        final FakeApi fake = new FakeApi();
        fake.text = "should be dropped";
        fake.beforeText = engine::cancel;

        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.FAILED, result.status);
        assertTrue(result.cancelled);
        assertEquals("", result.text);
        assertTrue("stop must be called on cancel", fake.stopCalls >= 1);
        assertEquals(1, fake.clearCalls);
    }

    @Test
    public void nextRecognitionClearsCancelledFlag() throws Exception {
        final HgramOcrEngine engine = newEngine(temporary.newFolder());
        engine.cancel();

        final FakeApi fake = new FakeApi();
        fake.text = "fresh text";
        final HgramOcrEngine.Result result = engine.recognize(fake, bitmap(10, 10));

        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertFalse(result.cancelled);
        assertEquals("fresh text", result.text);
    }

    private static void awaitStops(FakeApi fake, int expected) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (fake.stopCalls < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals("stop must be dispatched to the control worker", expected, fake.stopCalls);
    }

    private static void awaitRequestCount(HgramOcrEngine engine, int expected)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (engine.requestCountForTests() != expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals("request state must be released after native stop", expected,
                engine.requestCountForTests());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }
}
