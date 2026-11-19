package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Keeps a per-account copy of the DC addresses reported by help.getConfig so that a fast network
 * handover can restore them without waiting for a fresh config round trip.
 *
 * The native layer already persists the addresses it received (including the transport flags and
 * the per-address secret) in tgnet.dat and restores them on start. The cached addresses are
 * therefore only used as a hint: they are never pushed into the native address list, because the
 * native entry point for that replaces the whole list with a single address that carries neither
 * the transport flags nor the protocol secret.
 */
public final class HgramDcAddressCache {

    private static final String PREFERENCES = "hgram_dc_addresses";
    private static final String LAST_REFRESH = "last_refresh";
    private static final long REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private HgramDcAddressCache() {
    }

    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES + "_" + account, Context.MODE_PRIVATE);
    }

    /**
     * Restores the cached addresses of the given account. The native address list is left intact:
     * the cached values only describe what the server reported last time, so the native layer
     * keeps every address, flag and secret it already knows.
     */
    public static void applyCached(int account) {
        if (!HgramConfig.isFastNetworkHandoverEnabled()) {
            return;
        }
        SharedPreferences preferences = preferences(account);
        if (preferences.getInt("cached_dc_count", 0) <= 0) {
            return;
        }
        try {
            // The native layer refreshes the addresses from the server and keeps the flags and
            // secrets of every variant. It refuses a second concurrent refresh on its own.
            ConnectionsManager.getInstance(account).updateDcSettings();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Refreshes the cached addresses of the given account. The cache is refreshed at most once per
     * {@link #REFRESH_INTERVAL_MS} so that enabling the feature does not flood the server with
     * config requests.
     */
    public static void prepareAccount(int account) {
        if (!HgramConfig.isFastNetworkHandoverEnabled()) {
            return;
        }
        applyCached(account);
        SharedPreferences preferences = preferences(account);
        long now = android.os.SystemClock.elapsedRealtime();
        long last = preferences.getLong(LAST_REFRESH, 0);
        if (last != 0 && now >= last && now - last < REFRESH_INTERVAL_MS) {
            return;
        }
        preferences.edit().putLong(LAST_REFRESH, now).apply();
        TLRPC.TL_help_getConfig request = new TLRPC.TL_help_getConfig();
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.TL_config)) {
                preferences.edit().putLong(LAST_REFRESH, 0).apply();
                return;
            }
            TLRPC.TL_config config = (TLRPC.TL_config) response;
            SharedPreferences.Editor editor = preferences.edit();
            int cached = 0;
            for (TLRPC.TL_dcOption option : config.dc_options) {
                if (option.id < 1 || option.id > 5 || option.media_only || option.cdn || TextUtils.isEmpty(option.ip_address) || option.port <= 0) {
                    continue;
                }
                // A datacenter can be reachable over both families. Keep the family the device
                // actually prefers, otherwise the cached address can never be used.
                boolean desiredIpv6Only = !ConnectionsManager.hasIpv4 && ConnectionsManager.hasIpv6;
                boolean addressMatchesFamily = HgramDcAddressCache.isIpv6(option.ip_address) == desiredIpv6Only;
                if (!addressMatchesFamily) {
                    continue;
                }
                editor.putString("ip_" + option.id, option.ip_address);
                editor.putInt("port_" + option.id, option.port);
                cached++;
            }
            editor.putInt("cached_dc_count", cached);
            editor.putLong(LAST_REFRESH, android.os.SystemClock.elapsedRealtime());
            editor.apply();
        });
    }

    private static boolean isIpv6(String address) {
        return address != null && address.indexOf(':') >= 0;
    }

    /** Visible for tests: tells whether the address belongs to the IPv6 family. */
    static boolean isIpv6ForTest(String address) {
        return isIpv6(address);
    }
}
