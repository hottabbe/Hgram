package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Per-account hidden dialog ids and the in-process secret-space state. */
public final class HgramSecretSpace {

    private static final String PREFS = "hgram_secret_space";
    // Same-process mirror of the active second space. It is deliberately never restored on a
    // cold start (see ensureActiveLoaded); kept so a short background/resume can recall the state.
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_HIDDEN_PREFIX = "hidden_";

    private static final String LEGACY_PREFS = "hgram_decoy_space";
    private static final String LEGACY_HIDDEN = "hidden_dialogs";

    private static final ConcurrentHashMap<Integer, Set<Long>> hiddenCache = new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    private static volatile boolean active;
    private static volatile boolean activeLoaded;
    private static volatile boolean migrationDone;
    private static volatile boolean lastTogglePersisted = true;

    private HgramSecretSpace() {
    }

    private static SharedPreferences preferences() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String hiddenKey(int account) {
        return KEY_HIDDEN_PREFIX + Math.max(0, account);
    }

    private static void migrateIfNeeded() {
        if (migrationDone) {
            return;
        }
        synchronized (lock) {
            if (migrationDone) {
                return;
            }
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            final SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
            final Set<String> old = legacy.getStringSet(LEGACY_HIDDEN, null);
            final SharedPreferences prefs = preferences();
            if (prefs == null) {
                return;
            }
            if (old == null) {
                migrationDone = true;
                return;
            }
            final HashSet<Integer> accounts = new HashSet<>();
            accounts.add(Math.max(0, UserConfig.selectedAccount));
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    accounts.add(a);
                }
            }
            final SharedPreferences.Editor editor = prefs.edit();
            for (Integer account : accounts) {
                final String key = hiddenKey(account);
                if (!prefs.contains(key)) {
                    editor.putStringSet(key, new HashSet<>(old));
                    hiddenCache.remove(account);
                }
            }
            if (!editor.commit()) {
                return;
            }
            legacy.edit().remove(LEGACY_HIDDEN).commit();
            migrationDone = true;
        }
    }

    private static Set<Long> load(int account) {
        final Integer key = Math.max(0, account);
        final Set<Long> cached = hiddenCache.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (lock) {
            final Set<Long> current = hiddenCache.get(key);
            if (current != null) {
                return current;
            }
            migrateIfNeeded();
            final SharedPreferences prefs = preferences();
            if (prefs == null) {
                return Collections.emptySet();
            }
            final HashSet<Long> ids = new HashSet<>();
            final Set<String> raw = prefs.getStringSet(hiddenKey(key), null);
            if (raw != null) {
                for (String value : raw) {
                    try {
                        ids.add(Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            final Set<Long> loaded = Collections.unmodifiableSet(ids);
            hiddenCache.put(key, loaded);
            return loaded;
        }
    }

    public static boolean isActive() {
        ensureActiveLoaded();
        if (active && SharedConfig.appLocked) {
            return false;
        }
        return active;
    }

    public static void setActive(boolean value) {
        final boolean safeValue = value && !SharedConfig.appLocked;
        active = safeValue;
        activeLoaded = true;
        if (safeValue) {
            warmUpAccounts();
        }
        final SharedPreferences prefs = preferences();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ACTIVE, safeValue).apply();
        }
    }

    private static void ensureActiveLoaded() {
        if (activeLoaded) {
            return;
        }
        synchronized (lock) {
            if (activeLoaded) {
                return;
            }
            // The second space must never be restored after a process death. The preference is
            // only a mirror for the current process; a cold start always begins in the main space.
            active = false;
            activeLoaded = true;
        }
    }

    public static void onAppLocked() {
        setActive(false);
    }

    /** Loads the selected account state without writing preferences. */
    public static void warmUp() {
        warmUpAccounts();
        ensureActiveLoaded();
    }

    /**
     * Loads hidden ids for every activated account, so the first notification or
     * dialogs-list check does not read preferences on a hot path.
     */
    private static void warmUpAccounts() {
        load(Math.max(0, UserConfig.selectedAccount));
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                load(a);
            }
        }
    }

    public static void forEachHidden(int account, Consumer<Long> consumer) {
        if (consumer == null) {
            return;
        }
        for (Long id : load(account)) {
            consumer.accept(id);
        }
    }

    private static Set<String> toStringSet(Set<Long> ids) {
        final HashSet<String> result = new HashSet<>(ids.size());
        for (Long id : ids) {
            result.add(Long.toString(id));
        }
        return result;
    }

    public static boolean isHidden(int account, long dialogId) {
        return load(account).contains(dialogId);
    }

    public static void setHidden(int account, long dialogId, boolean hidden) {
        synchronized (lock) {
            final Set<Long> current = new HashSet<>(load(account));
            if (hidden) {
                current.add(dialogId);
            } else {
                current.remove(dialogId);
            }
            final Set<Long> updated = Collections.unmodifiableSet(current);
            hiddenCache.put(Math.max(0, account), updated);
            final SharedPreferences prefs = preferences();
            if (prefs != null) {
                if (updated.isEmpty()) {
                    prefs.edit().remove(hiddenKey(account)).apply();
                } else {
                    prefs.edit().putStringSet(hiddenKey(account), toStringSet(updated)).apply();
                }
            }
        }
    }

    public static boolean toggle(int account, long dialogId) {
        synchronized (lock) {
            final Set<Long> previous = load(account);
            final boolean hidden = !previous.contains(dialogId);
            final Set<Long> current = new HashSet<>(previous);
            if (hidden) {
                current.add(dialogId);
            } else {
                current.remove(dialogId);
            }
            final Set<Long> updated = Collections.unmodifiableSet(current);
            hiddenCache.put(Math.max(0, account), updated);
            boolean persisted = prefsOrTrue();
            final SharedPreferences prefs = preferences();
            if (prefs != null) {
                final SharedPreferences.Editor editor = prefs.edit();
                if (updated.isEmpty()) {
                    editor.remove(hiddenKey(account));
                } else {
                    editor.putStringSet(hiddenKey(account), toStringSet(updated));
                }
                persisted = editor.commit();
            }
            FileLog.d("HgramSecretSpace toggle acc=" + account
                    + " did=" + dialogId
                    + " active=" + isActive()
                    + " hidden=" + hidden
                    + " ok=" + persisted);
            lastTogglePersisted = persisted;
            if (!persisted) {
                // Do not leave a UI-visible state that will disappear on the next launch.
                hiddenCache.put(Math.max(0, account), previous);
                return !hidden;
            }
            return hidden;
        }
    }

    private static boolean prefsOrTrue() {
        return preferences() == null;
    }

    public static boolean wasLastTogglePersisted() {
        return lastTogglePersisted;
    }

    public static int hiddenCount(int account) {
        return load(account).size();
    }

    public static List<Long> hiddenList(int account) {
        final Set<Long> hidden = load(account);
        if (hidden == null) {
            return Collections.emptyList();
        }
        final ArrayList<Long> list = new ArrayList<>(hidden);
        Collections.sort(list);
        return list;
    }

    public static void clear(int account) {
        synchronized (lock) {
            hiddenCache.remove(Math.max(0, account));
            hiddenCache.put(Math.max(0, account), Collections.emptySet());
            final SharedPreferences prefs = preferences();
            if (prefs != null) {
                prefs.edit().remove(hiddenKey(account)).apply();
            }
        }
    }

    public static void clearAll() {
        synchronized (lock) {
            hiddenCache.clear();
            final SharedPreferences prefs = preferences();
            if (prefs != null) {
                final HashSet<String> keys = new HashSet<>(prefs.getAll().keySet());
                final SharedPreferences.Editor editor = prefs.edit();
                for (String key : keys) {
                    if (key.startsWith(KEY_HIDDEN_PREFIX)) {
                        editor.remove(key);
                    }
                }
                editor.apply();
            }
            final Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                        .edit().remove(LEGACY_HIDDEN).apply();
            }
        }
    }

    /**
     * Hidden dialogs are excluded from the main space and appear only while the second space is active.
     */
    public static boolean isVisible(int account, long dialogId) {
        return isActive() || !isHidden(account, dialogId);
    }

    /**
     * Entry gate for every path that wants to present a dialog. Hidden dialogs do not exist in the main space,
     * but stay reachable while the second space is active.
     */
    public static boolean canOpenDialog(int account, long dialogId) {
        return dialogId == 0 || isVisible(account, dialogId);
    }

    /** Test-only reset of the in-process cache/state. */
    static void resetForTests() {
        synchronized (lock) {
            hiddenCache.clear();
            active = false;
            activeLoaded = false;
            migrationDone = false;
        }
    }
}
