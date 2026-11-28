package xyz.nextalone.nagram;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class NaConfigCustomTitleMigrationTest {

    private static final String KEY = "CustomTitle";
    private static final String VERSION_KEY = "CustomTitleLegacyVersion";
    private static final String TARGET = "Hgram";

    private SharedPreferences preferences;

    @Before
    public void setUp() {
        Application application = ApplicationProvider.getApplicationContext();
        preferences = application.getSharedPreferences("custom_title_migration_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    private void setTitle(String title) {
        preferences.edit().putString(KEY, title).putInt(VERSION_KEY, 0).commit();
    }

    private boolean normalize() {
        return CustomTitleMigration.normalize(preferences, KEY, TARGET, VERSION_KEY, 1);
    }

    @Test
    public void legacyBrandTitlesNormalizeToCurrentDefault() {
        for (String legacy : Arrays.asList("Nagram", "NekoX", "nekox", "hgram")) {
            setTitle(legacy);
            assertTrue("expected migration for " + legacy, normalize());
            assertEquals(TARGET, preferences.getString(KEY, null));
        }
        assertEquals(1, preferences.getInt(VERSION_KEY, 0));
    }

    @Test
    public void customTitleAndUserNameArePreserved() {
        preferences.edit().putBoolean("CustomTitleUserName", true).commit();
        setTitle("My private Hgram title");

        assertFalse(normalize());
        assertEquals("My private Hgram title", preferences.getString(KEY, null));
        assertTrue(preferences.getBoolean("CustomTitleUserName", false));
        assertEquals(1, preferences.getInt(VERSION_KEY, 0));
    }

    @Test
    public void secondCallIsNoOpAfterMigration() {
        setTitle("Nagram");
        assertTrue(normalize());
        String migrated = preferences.getString(KEY, null);

        assertFalse(normalize());
        assertEquals(migrated, preferences.getString(KEY, null));
        assertEquals(1, preferences.getInt(VERSION_KEY, 0));
    }

    @Test
    public void migrationIsNotRepeatedAfterAUserEdit() {
        setTitle("NekoX");
        assertTrue(normalize());
        preferences.edit().putString(KEY, "User title").commit();

        assertFalse(normalize());
        assertEquals("User title", preferences.getString(KEY, null));
    }

    @Test
    public void displayValueNormalizesLegacyAndKeepsCustomTitle() {
        assertEquals(TARGET, CustomTitleMigration.displayValue("Nagram", TARGET));
        assertEquals(TARGET, CustomTitleMigration.displayValue("NEKOX", TARGET));
        assertEquals("Мой клиент", CustomTitleMigration.displayValue("Мой клиент", TARGET));
        assertEquals(TARGET, CustomTitleMigration.displayValue(null, TARGET));
    }
}
