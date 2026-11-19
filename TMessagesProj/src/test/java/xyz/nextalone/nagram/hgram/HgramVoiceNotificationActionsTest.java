package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramVoiceNotificationActionsTest {

    private Context context;

    @Before
    public void setup() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        context = ApplicationLoader.applicationContext;
        HgramConfig.reset();
    }

    @Test
    public void actionsOnlyForPerChatVoiceMessages() {
        assertTrue(HgramVoiceNotificationActions.shouldOffer(true, false, false));
        assertFalse(HgramVoiceNotificationActions.shouldOffer(true, true, false));
        assertFalse(HgramVoiceNotificationActions.shouldOffer(true, false, true));
        assertFalse(HgramVoiceNotificationActions.shouldOffer(false, false, false));
    }

    @Test
    public void listenPendingIntentTargetsPlaybackService() throws Exception {
        PendingIntent pending = HgramVoiceNotificationActions.listenIntent(
                context, 2, 123456789L, 77, "Alice", 1);
        assertNotNull(pending);
        Intent intent = Shadows.shadowOf(pending).getSavedIntent();
        assertEquals(HgramVoicePlaybackService.class.getName(), intent.getComponent().getClassName());
        assertEquals(HgramVoicePlaybackService.ACTION_TOGGLE, intent.getAction());
        assertEquals(123456789L, intent.getLongExtra(HgramVoicePlaybackService.EXTRA_DIALOG_ID, 0));
        assertEquals(77, intent.getIntExtra(HgramVoicePlaybackService.EXTRA_MESSAGE_ID, 0));
    }

    @Test
    public void textPendingIntentCarriesMessageAndService() {
        PendingIntent pending = HgramVoiceNotificationActions.transcribeIntent(
                context, 1, 42L, 9, "Bob", 2);
        Intent intent = Shadows.shadowOf(pending).getSavedIntent();
        assertEquals(HgramVoicePlaybackService.class.getName(), intent.getComponent().getClassName());
        assertEquals(HgramVoicePlaybackService.ACTION_TRANSCRIBE, intent.getAction());
        assertEquals(42L, intent.getLongExtra(HgramVoicePlaybackService.EXTRA_DIALOG_ID, 0));
        assertEquals(9, intent.getIntExtra(HgramVoicePlaybackService.EXTRA_MESSAGE_ID, 0));
    }

    @Test
    public void privacyHidesTranscriptionAndEngineComesFromConfig() {
        assertFalse(HgramVoiceNotificationActions.canTranscribe(true, true, false));
        assertFalse(HgramVoiceNotificationActions.canTranscribe(true, false, true));
        assertFalse(HgramVoiceNotificationActions.canTranscribe(false, false, false));
        assertTrue(HgramVoiceNotificationActions.canTranscribe(true, false, false));
        HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_SHERPA);
        assertEquals(HgramConfig.STT_ENGINE_SHERPA, HgramVoiceNotificationActions.engine());
        HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_GIGAAM);
        assertEquals(HgramConfig.STT_ENGINE_GIGAAM, HgramVoiceNotificationActions.engine());
    }
}
