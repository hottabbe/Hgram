package xyz.nextalone.nagram.hgram;

import android.app.Application;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertEquals(stopsAfterRecognition + 1, current.stopCalls);
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

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }
}
