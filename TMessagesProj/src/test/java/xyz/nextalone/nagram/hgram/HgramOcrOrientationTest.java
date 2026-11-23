package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HgramOcrOrientationTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final class FakeApi implements HgramOcrEngine.Api {
        String text = "ok";
        int width = -1;
        int height = -1;

        @Override
        public void init(String datapath, String language) {
        }

        @Override
        public void setImage(Bitmap bitmap) {
            width = bitmap.getWidth();
            height = bitmap.getHeight();
        }

        @Override
        public String getUTF8Text() {
            return text;
        }

        @Override
        public void stop() {
        }

        @Override
        public void clear() {
        }
    }

    private static int color(int x, int y) {
        return Color.rgb(16 + x * 32, 32 + y * 32, 96);
    }

    private static Bitmap source() {
        Bitmap bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 2; x++) {
                bitmap.setPixel(x, y, color(x, y));
            }
        }
        return bitmap;
    }

    @Test
    public void allEightExifOrientationsMapPixelsAndSwapBounds() {
        final Bitmap source = source();
        assertSame("normal orientation must not allocate", source,
                HgramOcrEngine.applyExifOrientation(source, ExifInterface.ORIENTATION_NORMAL));
        assertSame("missing orientation must not allocate", source,
                HgramOcrEngine.applyExifOrientation(source, ExifInterface.ORIENTATION_UNDEFINED));

        for (int orientation = 2; orientation <= 8; orientation++) {
            final Bitmap out = HgramOcrEngine.applyExifOrientation(source, orientation);
            assertNotSame("orientation " + orientation + " must allocate", source, out);
            final boolean swap = orientation >= ExifInterface.ORIENTATION_TRANSPOSE;
            final int expectedWidth = swap ? 3 : 2;
            final int expectedHeight = swap ? 2 : 3;
            assertEquals("width for " + orientation, expectedWidth, out.getWidth());
            assertEquals("height for " + orientation, expectedHeight, out.getHeight());
            for (int v = 0; v < expectedHeight; v++) {
                for (int u = 0; u < expectedWidth; u++) {
                    int sx;
                    int sy;
                    switch (orientation) {
                        case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                            sx = 1 - u;
                            sy = v;
                            break;
                        case ExifInterface.ORIENTATION_ROTATE_180:
                            sx = 1 - u;
                            sy = 2 - v;
                            break;
                        case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                            sx = u;
                            sy = 2 - v;
                            break;
                        case ExifInterface.ORIENTATION_TRANSPOSE:
                            sx = v;
                            sy = u;
                            break;
                        case ExifInterface.ORIENTATION_ROTATE_90:
                            sx = v;
                            sy = 2 - u;
                            break;
                        case ExifInterface.ORIENTATION_TRANSVERSE:
                            sx = 1 - v;
                            sy = 2 - u;
                            break;
                        case ExifInterface.ORIENTATION_ROTATE_270:
                        default:
                            sx = 1 - v;
                            sy = u;
                            break;
                    }
                    assertEquals("pixel " + u + "," + v + " for orientation " + orientation,
                            source.getPixel(sx, sy), out.getPixel(u, v));
                }
            }
            out.recycle();
        }
        source.recycle();
    }

    @Test
    public void pngWithoutExifIsNotRotated() throws Exception {
        final File png = writeImage("plain.png", Bitmap.CompressFormat.PNG, 40, 20, -1);
        assertEquals(ExifInterface.ORIENTATION_NORMAL,
                HgramOcrEngine.readJpegOrientation(png, "image/png"));

        final FakeApi fake = new FakeApi();
        final HgramOcrEngine.Result result = recognizeFile(png, fake);
        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertEquals(40, fake.width);
        assertEquals(20, fake.height);
    }

    @Test
    public void jpegWithoutExifIsNotRotated() throws Exception {
        final File jpeg = writeImage("plain.jpg", Bitmap.CompressFormat.JPEG, 40, 20, -1);
        assertEquals(ExifInterface.ORIENTATION_NORMAL,
                HgramOcrEngine.readJpegOrientation(jpeg, "image/jpeg"));

        final FakeApi fake = new FakeApi();
        final HgramOcrEngine.Result result = recognizeFile(jpeg, fake);
        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertEquals(40, fake.width);
        assertEquals(20, fake.height);
    }

    @Test
    public void rotatedJpegReachesRecognizerOriented() throws Exception {
        final File jpeg = writeImage("rotated.jpg", Bitmap.CompressFormat.JPEG, 40, 20,
                ExifInterface.ORIENTATION_ROTATE_90);
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90,
                HgramOcrEngine.readJpegOrientation(jpeg, "image/jpeg"));

        final FakeApi fake = new FakeApi();
        final HgramOcrEngine.Result result = recognizeFile(jpeg, fake);
        assertEquals(HgramOcrEngine.Status.OK, result.status);
        assertEquals("EXIF 90 must swap width/height before recognition", 20, fake.width);
        assertEquals(40, fake.height);
    }

    @Test
    public void sampleSizeUsesStoredBoundsAndScaleUsesOrientedLongSide() {
        assertEquals(1, HgramOcrEngine.sampleSize(1600, 900, 1600));
        assertEquals(1, HgramOcrEngine.sampleSize(1599, 900, 1600));
        assertEquals(2, HgramOcrEngine.sampleSize(1601, 900, 1600));
        assertEquals(4, HgramOcrEngine.sampleSize(4000, 3000, 1600));
        assertEquals(4, HgramOcrEngine.sampleSize(3000, 4000, 1600));
        assertEquals(2, HgramOcrEngine.sampleSize(1200, 3000, 1600));

        assertEquals(1600, HgramOcrText.scaledLongSide(4000, 3000, 1600));
        assertEquals(1200, HgramOcrText.scaledShortSide(4000, 3000, 1600));
        assertEquals(1600, HgramOcrText.scaledLongSide(3000, 4000, 1600));
        assertEquals(1200, HgramOcrText.scaledShortSide(3000, 4000, 1600));
        assertEquals("MAX_LONG_SIDE is unchanged: no evidence that 2048 helps",
                1600, HgramOcrText.MAX_LONG_SIDE);
    }

    private HgramOcrEngine.Result recognizeFile(File file, FakeApi fake) throws Exception {
        final HgramOcrEngine engine = HgramOcrEngine.forTests(() -> fake, temporary.newFolder("files"));
        engine.setModelsReadyForTests(true);
        final HgramOcrEngine.RequestToken token = engine.newRequest();
        try {
            final HgramOcrEngine.Result result =
                    engine.recognize(RuntimeEnvironment.getApplication(), file, token);
            assertNotEquals(0, token.getId());
            return result;
        } finally {
            engine.finish(token);
        }
    }

    private File writeImage(String name, Bitmap.CompressFormat format, int width, int height,
                            int orientation) throws Exception {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(200, 40 + height % 200, 40));
        final File file = new File(temporary.newFolder("image-" + name), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(format, 100, out));
        } finally {
            bitmap.recycle();
        }
        if (orientation >= 0) {
            final ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
            exif.saveAttributes();
        }
        return file;
    }
}
