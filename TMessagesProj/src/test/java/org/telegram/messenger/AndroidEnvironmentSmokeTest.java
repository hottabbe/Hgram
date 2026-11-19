package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidEnvironmentSmokeTest {

    @Test
    public void applicationContextIsAvailable() {
        Context context = ApplicationProvider.getApplicationContext();
        assertNotNull(context);
        assertTrue(context.getPackageName().contains("hgram") || context.getPackageName().contains("telegram"));
    }

    @Test
    public void sharedPreferencesWork() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("android_environment_smoke", Context.MODE_PRIVATE);
        prefs.edit().putString("key", "value").apply();
        assertEquals("value", prefs.getString("key", null));
    }
}
