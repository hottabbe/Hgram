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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
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
        awaitStops(current, stopsAfterRecognition + 1);
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
