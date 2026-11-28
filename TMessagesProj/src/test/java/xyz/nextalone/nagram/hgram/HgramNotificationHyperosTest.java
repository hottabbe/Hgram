package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramNotificationHyperosTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
        HgramConfig.setNotificationImportanceBoostEnabled(true);
    }

    @After
    public void tearDown() {
        HgramConfig.reset();
    }

    @Test
    public void autoResolvesToSystemOnEveryFamily() {
        HgramConfig.setNotifMediaStyle(HgramConfig.NOTIF_MEDIA_STYLE_AUTO);
        assertEquals(HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM,
                HgramNotificationStyle.chosenPresentation());
    }

    @Test
    public void explicitModesArePreserved() {
        HgramConfig.setNotifMediaStyle(HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM);
        assertEquals(HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM,
                HgramNotificationStyle.chosenPresentation());
        HgramConfig.setNotifMediaStyle(HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM);
        assertEquals(HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM,
                HgramNotificationStyle.chosenPresentation());
    }

    @Test
    public void miuiPreviewUsesExactCanvasAndOwnsBitmap() {
        final Bitmap source = Bitmap.createBitmap(2000, 500, Bitmap.Config.ARGB_8888);
        final Bitmap prepared = HgramNotificationMedia.prepareMiuiPreview(source);
        assertNotNull(prepared);
        assertEquals(HgramNotificationMedia.MIUI_PREVIEW_WIDTH, prepared.getWidth());
        assertEquals(HgramNotificationMedia.MIUI_PREVIEW_HEIGHT, prepared.getHeight());
        assertTrue(source.isRecycled());
        assertFalse(prepared.isRecycled());
        prepared.recycle();
    }

    @Test
    public void defaultOffKeepsImportance() {
        HgramConfig.setNotificationImportanceBoostEnabled(false);
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,
                HgramNotificationChannels.effectiveImportance(
                        NotificationManager.IMPORTANCE_DEFAULT, false, true));
        HgramConfig.setNotificationImportanceBoostEnabled(true);
    }

    @Test
    public void effectiveChannelImportanceIsStableAndPreservesLowMin() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
                HgramNotificationChannels.effectiveImportance(
                        NotificationManager.IMPORTANCE_DEFAULT, false, true));
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
                HgramNotificationChannels.effectiveImportance(
                        NotificationManager.IMPORTANCE_HIGH, false, true));
        assertEquals(NotificationManager.IMPORTANCE_LOW,
                HgramNotificationChannels.effectiveImportance(
                        NotificationManager.IMPORTANCE_LOW, false, true));
        assertEquals(NotificationManager.IMPORTANCE_MIN,
                HgramNotificationChannels.effectiveImportance(
                        NotificationManager.IMPORTANCE_MIN, false, true));
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,
                HgramNotificationChannels.effectiveImportance(
                        NotificationManager.IMPORTANCE_DEFAULT, true, true));
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,
                HgramNotificationChannels.effectiveImportance(
                        NotificationManager.IMPORTANCE_DEFAULT, false, false));

        assertFalse(HgramNotificationChannels.shouldOverwriteInApp(
                NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_HIGH));
        assertTrue(HgramNotificationChannels.shouldOverwriteInApp(
                NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_DEFAULT));
        assertFalse(HgramNotificationChannels.shouldOverwriteInApp(
                NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_LOW));
        assertFalse(HgramNotificationChannels.shouldOverwriteInApp(
                NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MIN));
        assertFalse(HgramNotificationChannels.shouldOverwriteInApp(
                NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_NONE));
        assertFalse(HgramNotificationChannels.shouldOverwriteInApp(
                NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX));

        assertTrue(HgramNotificationChannels.acceptsSystemOverride(
                true, false, NotificationManager.IMPORTANCE_NONE));
        assertTrue(HgramNotificationChannels.acceptsSystemOverride(
                true, false, NotificationManager.IMPORTANCE_MAX));
        assertFalse(HgramNotificationChannels.acceptsSystemOverride(
                true, false, NotificationManager.IMPORTANCE_HIGH));
        assertFalse(HgramNotificationChannels.acceptsSystemOverride(
                true, true, NotificationManager.IMPORTANCE_LOW));
        assertFalse(HgramNotificationChannels.acceptsSystemOverride(
                false, false, NotificationManager.IMPORTANCE_LOW));

        final String created = HgramNotificationChannels.channelSettingsHash(
                new long[]{0, 0}, 0, null, NotificationManager.IMPORTANCE_HIGH, false);
        final String next = HgramNotificationChannels.channelSettingsHash(
                new long[]{0, 0}, 0, null,
                HgramNotificationChannels.effectiveImportance(
                        NotificationManager.IMPORTANCE_DEFAULT, false, true), false);
        assertEquals(created, next);
    }

    @Test
    public void systemLowMinSurviveTwoInAppValidations() {
        final int requestedImportance = HgramNotificationChannels.effectiveImportance(
                NotificationManager.IMPORTANCE_DEFAULT, false, true);
        final long[] vibration = new long[]{0, 0};
        final String requestedHash = HgramNotificationChannels.channelSettingsHash(
                vibration, 0, null, requestedImportance, false);

        for (int actualImportance : new int[]{
                NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_MIN}) {
            final String actualHash = HgramNotificationChannels.channelSettingsHash(
                    vibration, 0, null, actualImportance, false);
            assertFalse(actualHash.equals(requestedHash));
            assertFalse(HgramNotificationChannels.shouldOverwriteInApp(
                    requestedImportance, actualImportance));

            // First validation: the system override differs from the stored HIGH hash and is
            // accepted; the controller persists the actual hash and keeps the channel id.
            String storedHash = requestedHash;
            assertFalse(actualHash.equals(storedHash));
            storedHash = actualHash;
            String channelId = "stable-channel-id";

            // Second validation: the final hash must use the accepted actual settings, not HIGH.
            final String finalHash = HgramNotificationChannels.finalChannelSettingsHash(
                    true, false, actualImportance, actualHash,
                    vibration, 0, null, requestedImportance, false);
            assertEquals(storedHash, finalHash);
            assertFalse(finalHash.equals(requestedHash));
            if (!finalHash.equals(storedHash)) {
                channelId = "recreated";
            }
            assertEquals("stable-channel-id", channelId);
        }

        // Default in-app and background channels still rebuild from the requested settings.
        final String lowHash = HgramNotificationChannels.channelSettingsHash(
                vibration, 0, null, NotificationManager.IMPORTANCE_LOW, false);
        assertEquals(requestedHash, HgramNotificationChannels.finalChannelSettingsHash(
                true, true, NotificationManager.IMPORTANCE_LOW, lowHash,
                vibration, 0, null, requestedImportance, false));
        assertEquals(requestedHash, HgramNotificationChannels.finalChannelSettingsHash(
                false, false, NotificationManager.IMPORTANCE_LOW, lowHash,
                vibration, 0, null, requestedImportance, false));
    }

    @Test
    public void noneAndMaxKeepTheirAcceptedHash() {
        final long[] vibration = new long[]{0, 0};
        final String actualNone = HgramNotificationChannels.channelSettingsHash(
                vibration, 0, null, NotificationManager.IMPORTANCE_NONE, false);
        final String actualMax = HgramNotificationChannels.channelSettingsHash(
                vibration, 0, null, NotificationManager.IMPORTANCE_MAX, false);

        assertEquals(actualNone, HgramNotificationChannels.finalChannelSettingsHash(
                true, false, NotificationManager.IMPORTANCE_NONE, actualNone,
                vibration, 0, null, NotificationManager.IMPORTANCE_HIGH, false));
        assertEquals(actualMax, HgramNotificationChannels.finalChannelSettingsHash(
                true, false, NotificationManager.IMPORTANCE_MAX, actualMax,
                vibration, 0, null, NotificationManager.IMPORTANCE_HIGH, false));

        // Accepted system settings are carried as a whole, not only the importance.
        final String actualWithLed = HgramNotificationChannels.channelSettingsHash(
                vibration, 0x123456, null, NotificationManager.IMPORTANCE_LOW, false);
        assertEquals(actualWithLed, HgramNotificationChannels.finalChannelSettingsHash(
                true, false, NotificationManager.IMPORTANCE_LOW, actualWithLed,
                vibration, 0, null, NotificationManager.IMPORTANCE_HIGH, false));
    }

    @Test
    public void acceptedImportanceSurvivesSoundKeyChange() {
        final int requestedImportance = HgramNotificationChannels.effectiveImportance(
                NotificationManager.IMPORTANCE_DEFAULT, false, true);
        final long[] vibration = new long[]{0, 0};
        final Uri newSound = Uri.parse("content://hgram/new");

        for (int accepted : new int[]{
                NotificationManager.IMPORTANCE_LOW,
                NotificationManager.IMPORTANCE_MIN,
                NotificationManager.IMPORTANCE_MAX,
                NotificationManager.IMPORTANCE_NONE}) {
            // A changed sound URI misses the old lookup key and forces a new channel. The
            // replacement must inherit the accepted system importance instead of HIGH.
            assertEquals(accepted, HgramNotificationChannels.creationImportance(
                    true, false, requestedImportance, accepted));

            final String newHash = HgramNotificationChannels.channelSettingsHash(
                    vibration, 0, newSound, accepted, false);
            assertFalse(newHash.equals(HgramNotificationChannels.channelSettingsHash(
                    vibration, 0, newSound, requestedImportance, false)));
            assertEquals(newHash, HgramNotificationChannels.finalChannelSettingsHash(
                    true, false, accepted, newHash,
                    vibration, 0, newSound, requestedImportance, false));
        }

        // No accepted override: the ordinary sound-change path still creates the requested HIGH.
        assertEquals(requestedImportance, HgramNotificationChannels.creationImportance(
                true, false, requestedImportance, -1));
        // Default in-app and background channels never inherit a per-chat override.
        assertEquals(requestedImportance, HgramNotificationChannels.creationImportance(
                true, true, requestedImportance, NotificationManager.IMPORTANCE_LOW));
        assertEquals(requestedImportance, HgramNotificationChannels.creationImportance(
                false, false, requestedImportance, NotificationManager.IMPORTANCE_LOW));
        // Without a sound change the accepted channel stays on its existing path.
        assertEquals(NotificationManager.IMPORTANCE_LOW,
                HgramNotificationChannels.creationImportance(
                        true, false, requestedImportance, NotificationManager.IMPORTANCE_LOW));
    }

    @Test
    public void resetToDefaultClearsEffectiveOverride() {
        final int requestedImportance = HgramNotificationChannels.effectiveImportance(
                NotificationManager.IMPORTANCE_DEFAULT, false, true);
        final long[] vibration = new long[]{0, 0};
        final Uri sound = Uri.parse("content://hgram/reset");

        for (int stored : new int[]{
                NotificationManager.IMPORTANCE_LOW,
                NotificationManager.IMPORTANCE_MIN,
                NotificationManager.IMPORTANCE_MAX,
                NotificationManager.IMPORTANCE_NONE}) {
            // The real channel is DEFAULT after the user resets the override. The stale
            // per-chat value must not survive in memory or feed the recreate decision.
            final int localAccepted = HgramNotificationChannels.effectiveAcceptedImportance(
                    stored, NotificationManager.IMPORTANCE_DEFAULT);
            assertEquals(-1, localAccepted);
            assertEquals(requestedImportance, HgramNotificationChannels.creationImportance(
                    true, false, requestedImportance, localAccepted));

            final String actualHash = HgramNotificationChannels.channelSettingsHash(
                    vibration, 0, sound, NotificationManager.IMPORTANCE_DEFAULT, false);
            final String requestedHash = HgramNotificationChannels.channelSettingsHash(
                    vibration, 0, sound, requestedImportance, false);
            assertEquals(requestedHash, HgramNotificationChannels.finalChannelSettingsHash(
                    true, false, NotificationManager.IMPORTANCE_DEFAULT, actualHash,
                    vibration, 0, sound, requestedImportance, false));

            // Second validation after the prefs key is gone: the recreated channel is HIGH,
            // the local value stays unset and the hash/id decision is stable.
            assertEquals(-1, HgramNotificationChannels.effectiveAcceptedImportance(
                    -1, NotificationManager.IMPORTANCE_HIGH));
            assertEquals(requestedImportance, HgramNotificationChannels.creationImportance(
                    true, false, requestedImportance, -1));
            final String recreatedHash = HgramNotificationChannels.channelSettingsHash(
                    vibration, 0, sound, NotificationManager.IMPORTANCE_HIGH, false);
            assertEquals(requestedHash, recreatedHash);
            assertEquals(recreatedHash, HgramNotificationChannels.finalChannelSettingsHash(
                    true, false, NotificationManager.IMPORTANCE_HIGH, recreatedHash,
                    vibration, 0, sound, requestedImportance, false));
        }

        // Explicit system overrides and the sound-key inheritance path are not changed.
        assertEquals(NotificationManager.IMPORTANCE_LOW,
                HgramNotificationChannels.effectiveAcceptedImportance(
                        NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_LOW));
        assertEquals(NotificationManager.IMPORTANCE_LOW,
                HgramNotificationChannels.effectiveAcceptedImportance(
                        NotificationManager.IMPORTANCE_LOW, -1));
        assertEquals(-1, HgramNotificationChannels.effectiveAcceptedImportance(
                NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_UNSPECIFIED));
        assertTrue(HgramNotificationChannels.shouldOverwriteInApp(
                requestedImportance, NotificationManager.IMPORTANCE_UNSPECIFIED));
        assertEquals(-1, HgramNotificationChannels.effectiveAcceptedImportance(
                -1, NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Test
    public void catchupStartsOnlyAfterPublish() {
        final AtomicInteger started = new AtomicInteger();
        final HgramNotificationCatchup catchup = new HgramNotificationCatchup();
        catchup.enqueue(started::incrementAndGet);
        assertEquals(0, started.get());
        catchup.publish();
        assertEquals(1, started.get());
        catchup.publish();
        assertEquals(1, started.get());
        catchup.enqueue(started::incrementAndGet);
        assertEquals(2, started.get());

        final HgramNotificationCatchup canceled = new HgramNotificationCatchup();
        canceled.enqueue(started::incrementAndGet);
        canceled.cancel();
        canceled.publish();
        assertEquals(2, started.get());
    }

    @Test
    public void jpegStaysWithinMiuiBudget() {
        final Bitmap bitmap = Bitmap.createBitmap(
                HgramNotificationMedia.MIUI_PREVIEW_WIDTH,
                HgramNotificationMedia.MIUI_PREVIEW_HEIGHT,
                Bitmap.Config.ARGB_8888);
        final byte[] jpeg = HgramNotificationMedia.compressJpeg(bitmap);
        assertNotNull(jpeg);
        assertTrue(jpeg.length > 0);
        assertTrue(jpeg.length <= HgramNotificationMedia.MAX_MIUI_PREVIEW_BYTES);
        bitmap.recycle();
    }
}
