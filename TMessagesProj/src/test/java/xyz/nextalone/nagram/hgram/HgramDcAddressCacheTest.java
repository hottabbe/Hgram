package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The native layer owns the DC addresses, so the cache must stay per account and must not push. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramDcAddressCacheTest {

    private static final String PREFERENCES = "hgram_dc_addresses";

    @Before
    public void setup() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.reset();
    }

    private SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES + "_" + account, Context.MODE_PRIVATE);
    }

    @Test
    public void cacheIsStoredPerAccount() {
        preferences(0).edit().putString("ip_2", "149.154.167.51").putInt("port_2", 443).apply();
        preferences(1).edit().putString("ip_2", "149.154.175.100").putInt("port_2", 443).apply();

        assertEquals("149.154.167.51", preferences(0).getString("ip_2", ""));
        assertEquals("149.154.175.100", preferences(1).getString("ip_2", ""));
    }

    @Test
    public void disabledFeatureKeepsTheCacheUntouched() {
        HgramConfig.setFastNetworkHandoverEnabled(false);
        preferences(0).edit().putString("ip_2", "149.154.167.51").putInt("port_2", 443).apply();

        HgramDcAddressCache.applyCached(0);

        assertEquals("149.154.167.51", preferences(0).getString("ip_2", ""));
    }

    @Test
    public void enabledFeatureKeepsRefreshTimestampScopedToTheAccount() {
        HgramConfig.setFastNetworkHandoverEnabled(true);
        preferences(1).edit().putLong("last_refresh", 1234L).apply();

        assertEquals(1234L, preferences(1).getLong("last_refresh", 0));
        assertEquals(0L, preferences(0).getLong("last_refresh", 0));
    }

    @Test
    public void ipv6DetectionUsesColonAsTheFamilyMarker() {
        assertTrue(HgramDcAddressCache.isIpv6ForTest("2001:b28:f23d:f001::a"));
        assertFalse(HgramDcAddressCache.isIpv6ForTest("149.154.167.51"));
    }
}
