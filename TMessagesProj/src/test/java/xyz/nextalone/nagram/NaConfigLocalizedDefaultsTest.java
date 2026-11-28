package xyz.nextalone.nagram;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, shadows = NaConfigLocalizedDefaultsTest.ShadowLocaleController.class)
public class NaConfigLocalizedDefaultsTest {

    @Before
    public void setUp() {
        Application application = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = application;
        NaConfig.INSTANCE.getCustomChannelLabel().value = "";
        NaConfig.INSTANCE.getCustomGreat().value = "";
        NaConfig.INSTANCE.getCustomPoor().value = "";
        NaConfig.INSTANCE.getPreferences().edit()
                .remove("CustomChannelLabel")
                .remove("CustomGreat")
                .remove("CustomPoor")
                .commit();
    }

    @Test
    public void defaultsFollowCurrentLocaleWithoutBeingPersisted() {
        ShadowLocaleController.channelLabel = "channel";
        ShadowLocaleController.great = "Great";
        ShadowLocaleController.poor = "Poor";
        assertEquals("channel", NaConfig.INSTANCE.getCustomChannelLabelText());
        assertEquals("Great", NaConfig.INSTANCE.getCustomGreatText());
        assertEquals("Poor", NaConfig.INSTANCE.getCustomPoorText());

        ShadowLocaleController.channelLabel = "канал";
        ShadowLocaleController.great = "Отлично";
        ShadowLocaleController.poor = "Плохо";

        assertEquals("канал", NaConfig.INSTANCE.getCustomChannelLabelText());
        assertEquals("Отлично", NaConfig.INSTANCE.getCustomGreatText());
        assertEquals("Плохо", NaConfig.INSTANCE.getCustomPoorText());
        assertEquals("", NaConfig.INSTANCE.getCustomChannelLabel().defaultValue);
        assertEquals("", NaConfig.INSTANCE.getCustomGreat().defaultValue);
        assertEquals("", NaConfig.INSTANCE.getCustomPoor().defaultValue);
    }

    @Test
    public void customValuesAreNotReplacedByLocale() {
        NaConfig.INSTANCE.getCustomChannelLabel().value = "Мой канал";
        NaConfig.INSTANCE.getCustomGreat().value = "Супер";
        NaConfig.INSTANCE.getCustomPoor().value = "Ужасно";

        assertEquals("Мой канал", NaConfig.INSTANCE.getCustomChannelLabelText());
        assertEquals("Супер", NaConfig.INSTANCE.getCustomGreatText());
        assertEquals("Ужасно", NaConfig.INSTANCE.getCustomPoorText());
    }

    @Implements(LocaleController.class)
    public static class ShadowLocaleController {
        static String channelLabel = "channel";
        static String great = "Great";
        static String poor = "Poor";

        @Implementation
        protected static String getString(int resourceId) {
            if (resourceId == org.telegram.messenger.R.string.channelLabel) {
                return channelLabel;
            }
            if (resourceId == org.telegram.messenger.R.string.Great) {
                return great;
            }
            if (resourceId == org.telegram.messenger.R.string.Poor) {
                return poor;
            }
            return "";
        }
    }
}
