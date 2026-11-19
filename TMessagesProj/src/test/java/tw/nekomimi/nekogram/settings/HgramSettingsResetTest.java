package tw.nekomimi.nekogram.settings;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import xyz.nextalone.nagram.hgram.HgramAntiPeek;
import xyz.nextalone.nagram.hgram.HgramConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramSettingsResetTest {

    private static final class Host implements HgramSettingsReset.Host {
        int notifyCount;
        int watcherCallbacks;
        boolean fieldCleared;
        boolean suppressed;
        String query = "old";
        int[] rows;

        @Override public void resetConfig() { HgramConfig.reset(); }
        @Override public void applyAntiPeek() { HgramAntiPeek.applySettingChange(); }
        @Override public String currentEngine() { return HgramConfig.getSttEngine(); }
        @Override public void clearSearchQuery() { query = ""; }
        @Override public void setSearchCallbackSuppressed(boolean value) { suppressed = value; }
        @Override public void clearSearchField() {
            fieldCleared = true;
            // Simulate the real TextWatcher: with suppression it must not filter again.
            if (!suppressed) watcherCallbacks++;
        }
        @Override public void applyVisibleRows(int[] value) { rows = value; }
        @Override public void notifyAdapterOnce() { notifyCount++; }
    }

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
    }

    private static boolean contains(int[] rows, int id) {
        for (int row : rows) if (row == id) return true;
        return false;
    }

    @Test
    public void hintFlagShowsOnceAndResetsWithSettings() {
        HgramConfig.setShortMergeCollapseHintShown(false);
        assertFalse(HgramConfig.isShortMergeCollapseHintShown());
        HgramConfig.setShortMergeCollapseHintShown(true);
        assertTrue(HgramConfig.isShortMergeCollapseHintShown());
        HgramConfig.reset();
        assertFalse(HgramConfig.isShortMergeCollapseHintShown());
    }

    @Test
    public void resetPathRebuildsEngineRowsOnceWithoutWatcherLoop() {
        HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_SHERPA);
        final Host host = new Host();

        HgramSettingsReset.run(host);

        assertEquals(HgramConfig.STT_ENGINE_VOSK, HgramConfig.getSttEngine());
        assertNotNull(host.rows);
        assertTrue(contains(host.rows, HgramSettingsActivity.ROW_VOSK_MODELS));
        assertFalse(contains(host.rows, HgramSettingsActivity.ROW_SHERPA_MODEL));
        assertFalse(contains(host.rows, HgramSettingsActivity.ROW_SHERPA_DEVICE));
        assertEquals("", host.query);
        assertTrue(host.fieldCleared);
        assertEquals(0, host.watcherCallbacks);
        assertEquals(1, host.notifyCount);

        final int[] first = host.rows.clone();
        HgramSettingsReset.run(host);
        assertEquals(2, host.notifyCount);
        assertEquals(0, host.watcherCallbacks);
        assertEquals(first.length, host.rows.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], host.rows[i]);
        }
        assertFalse(contains(host.rows, HgramSettingsActivity.ROW_GIGAAM_MODEL));
    }
}
