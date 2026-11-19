package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramNotificationStyleTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void customBigContentBuildsWithCaption() {
        final Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        final RemoteViews views = HgramNotificationStyle.buildBigContentView(
                bitmap, "caption", null);
        assertNotNull(views);
        bitmap.recycle();
    }

    @Test
    public void customBigContentRejectsMissingBitmap() {
        assertNull(HgramNotificationStyle.buildBigContentView(null, "caption", null));
    }

    @Test
    public void mediaStyleKeepsExplicitLowAndMinPriority() {
        final Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
        for (int priority : new int[]{
                NotificationCompat.PRIORITY_LOW, NotificationCompat.PRIORITY_MIN}) {
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    ApplicationLoader.applicationContext);
            builder.setPriority(priority);
            HgramNotificationStyle.apply(builder, bitmap, 1, "caption", null);
            assertEquals(priority, builder.build().priority);
        }
        bitmap.recycle();
    }
}
