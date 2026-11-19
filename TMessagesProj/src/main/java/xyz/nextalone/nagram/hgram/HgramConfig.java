package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;

/** Preferences owned by hgram features. Kept separate so all additions are easy to disable/reset. */
public final class HgramConfig {

    private static final String PREFERENCES = "hgram_settings";
    private static final String SMART_NOTIFICATION_DEBOUNCE = "smart_notification_debounce";
    private static final String SIMILAR_POST_DEDUPLICATION = "similar_post_deduplication";
    private static final String CLEAN_FEED_FILTER = "clean_feed_filter";
    private static final String CUSTOM_FEED_KEYWORDS = "custom_feed_keywords";
    private static final String COMPACT_MEDIA_ALBUMS = "compact_media_albums";
    private static final String PIN_TO_SCREEN = "pin_to_screen";
    private static final String ANTI_PEEK_GESTURE = "anti_peek_gesture";
    private static final String OFFLINE_TRANSCRIPTION = "offline_transcription";
    private static final String EXIF_CLEANUP = "exif_cleanup";
    private static final String HAPTIC_ENABLED = "haptic_enabled";
    private static final String SUMMARIZER_ENABLED = "summarizer_enabled";
    private static final String SUMMARIZER_MIN_WORDS = "summarizer_min_words";
    private static final String SUMMARIZER_KEY = "summarizer_openrouter_key";
    private static final String SUMMARIZER_MODEL = "summarizer_model";
    private static final String SUMMARIZER_PROMPT = "summarizer_system_prompt";
    private static final String SUMMARIZER_SHOW_PAID = "summarizer_show_paid";
    private static volatile int summarizerVersion;
    private static final String AUTO_LOUDNESS = "auto_loudness";
    private static final String SMART_FRAME_THROTTLING = "smart_frame_throttling";
    private static final String FAST_NETWORK_HANDOVER = "fast_network_handover";
    private static final String ALLOW_SCREENSHOTS = "allow_screenshots";
    private static final String ANTI_PEEK_SENSITIVITY = "anti_peek_sensitivity";
    private static final String ANTI_PEEK_SHOW_TOAST = "anti_peek_show_toast";
    private static final String ANTI_PEEK_HAPTIC = "anti_peek_haptic";
    private static final String ANTI_PEEK_HOLD_MIN_MS = "anti_peek_hold_min_ms";
    private static final String ANTI_PEEK_HOLD_MAX_MS = "anti_peek_hold_max_ms";
    private static volatile Boolean similarPostDeduplicationEnabled;
    private static volatile Boolean cleanFeedFilterEnabled;
    private static volatile Boolean compactMediaAlbumsEnabled;
    private static volatile Boolean pinToScreenEnabled;
    private static volatile Boolean antiPeekGestureEnabled;
    private static volatile Boolean offlineTranscriptionEnabled;
    private static volatile Boolean autoLoudnessEnabled;
    private static volatile Boolean smartFrameThrottlingEnabled;
    private static volatile Boolean fastNetworkHandoverEnabled;
    private static volatile Boolean allowScreenshots;
    private static volatile int antiPeekSensitivity = -1;
    private static volatile Boolean antiPeekShowToast;
    private static volatile Boolean antiPeekHaptic;
    private static volatile int antiPeekHoldMin = -1;
    private static volatile int antiPeekHoldMax = -1;
    private static volatile int compactMediaAlbumsVersion;

    private HgramConfig() {
    }

    public static boolean areAppUpdatesEnabled() {
        return false;
    }

    private static volatile int downloadThreads;
    private static volatile int uploadThreads;
    private static volatile Boolean shortMessageMergeEnabled;
    private static volatile int shortMessageMergeVersion;
    private static volatile int shortMergeMaxChars = -1;
    private static volatile int shortMergeMaxWords = -1;
    private static volatile int shortMergeMaxWindow = -1;
    private static volatile int shortMergeMaxChain = -1;
    private static volatile Boolean shortMergeCollapseHintShown;

    public static int getDownloadThreads() {
        int value = downloadThreads;
        if (value == 0) downloadThreads = value = Math.max(1, Math.min(16, preferences().getInt("download_threads", 4)));
        return value;
    }

    public static void setDownloadThreads(int value) {
        downloadThreads = Math.max(1, Math.min(16, value));
        preferences().edit().putInt("download_threads", downloadThreads).apply();
    }

    public static int getUploadThreads() {
        int value = uploadThreads;
        if (value == 0) uploadThreads = value = Math.max(1, Math.min(16, preferences().getInt("upload_threads", 4)));
        return value;
    }

    public static void setUploadThreads(int value) {
        uploadThreads = Math.max(1, Math.min(16, value));
        preferences().edit().putInt("upload_threads", uploadThreads).apply();
    }

    public static boolean isShortMessageMergeEnabled() {
        Boolean value = shortMessageMergeEnabled;
        if (value == null) shortMessageMergeEnabled = value = preferences().getBoolean("short_message_merge", false);
        return value;
    }

    public static boolean isShortMergeCollapseHintShown() {
        Boolean value = shortMergeCollapseHintShown;
        if (value == null) {
            shortMergeCollapseHintShown = value = preferences()
                    .getBoolean("short_merge_collapse_hint", false);
        }
        return value;
    }

    public static void setShortMergeCollapseHintShown(boolean shown) {
        preferences().edit().putBoolean("short_merge_collapse_hint", shown).apply();
        shortMergeCollapseHintShown = shown;
    }

    public static int getShortMessageMergeVersion() {
        return shortMessageMergeVersion;
    }

    public static int getShortMergeMaxChars() {
        if (shortMergeMaxChars < 0) {
            shortMergeMaxChars = preferences().getInt("short_merge_max_chars",
                    HgramShortMessageMerger.DEFAULT_MAX_CHARS);
        }
        return Math.max(20, Math.min(1000, shortMergeMaxChars));
    }

    public static int getShortMergeMaxWords() {
        if (shortMergeMaxWords < 0) {
            shortMergeMaxWords = preferences().getInt("short_merge_max_words",
                    HgramShortMessageMerger.DEFAULT_MAX_WORDS);
        }
        return Math.max(1, Math.min(50, shortMergeMaxWords));
    }

    public static int getShortMergeMaxWindowSeconds() {
        if (shortMergeMaxWindow < 0) {
            shortMergeMaxWindow = preferences().getInt("short_merge_max_window",
                    HgramShortMessageMerger.DEFAULT_MAX_WINDOW_SECONDS);
        }
        return Math.max(30, Math.min(3600, shortMergeMaxWindow));
    }

    public static int getShortMergeMaxChainSize() {
        if (shortMergeMaxChain < 0) {
            shortMergeMaxChain = preferences().getInt("short_merge_max_chain",
                    HgramShortMessageMerger.DEFAULT_MAX_CHAIN_SIZE);
        }
        return Math.max(2, Math.min(50, shortMergeMaxChain));
    }

    public static void setShortMergeMaxChars(int value) {
        shortMergeMaxChars = Math.max(20, Math.min(1000, value));
        preferences().edit().putInt("short_merge_max_chars", shortMergeMaxChars).apply();
        notifyShortMergeChanged();
    }

    public static void setShortMergeMaxWords(int value) {
        shortMergeMaxWords = Math.max(1, Math.min(50, value));
        preferences().edit().putInt("short_merge_max_words", shortMergeMaxWords).apply();
        notifyShortMergeChanged();
    }

    public static void setShortMergeMaxWindowSeconds(int value) {
        shortMergeMaxWindow = Math.max(30, Math.min(3600, value));
        preferences().edit().putInt("short_merge_max_window", shortMergeMaxWindow).apply();
        notifyShortMergeChanged();
    }

    public static void setShortMergeMaxChainSize(int value) {
        shortMergeMaxChain = Math.max(2, Math.min(50, value));
        preferences().edit().putInt("short_merge_max_chain", shortMergeMaxChain).apply();
        notifyShortMergeChanged();
    }

    public static void setShortMessageMergeEnabled(boolean enabled) {
        preferences().edit().putBoolean("short_message_merge", enabled).apply();
        shortMessageMergeEnabled = enabled;
        notifyShortMergeChanged();
    }

    private static void notifyShortMergeChanged() {
        shortMessageMergeVersion++;
        for (int account : SharedConfig.activeAccounts) {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, 0);
        }
        NotificationCenter.getInstance(UserConfig.selectedAccount)
                .postNotificationName(NotificationCenter.updateInterfaces, 0);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.updateInterfaces, 0);
    }

    static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static boolean isSmartNotificationDebounceEnabled() {
        return preferences().getBoolean(SMART_NOTIFICATION_DEBOUNCE, false);
    }

    public static void setSmartNotificationDebounceEnabled(boolean enabled) {
        preferences().edit().putBoolean(SMART_NOTIFICATION_DEBOUNCE, enabled).apply();
    }

    public static boolean isSimilarPostDeduplicationEnabled() {
        Boolean enabled = similarPostDeduplicationEnabled;
        if (enabled == null) {
            synchronized (HgramConfig.class) {
                enabled = similarPostDeduplicationEnabled;
                if (enabled == null) {
                    enabled = preferences().getBoolean(SIMILAR_POST_DEDUPLICATION, false);
                    similarPostDeduplicationEnabled = enabled;
                }
            }
        }
        return enabled;
    }

    public static void setSimilarPostDeduplicationEnabled(boolean enabled) {
        preferences().edit().putBoolean(SIMILAR_POST_DEDUPLICATION, enabled).apply();
        similarPostDeduplicationEnabled = enabled;
    }

    public static boolean isCleanFeedFilterEnabled() {
        Boolean enabled = cleanFeedFilterEnabled;
        if (enabled == null) {
            synchronized (HgramConfig.class) {
                enabled = cleanFeedFilterEnabled;
                if (enabled == null) {
                    enabled = preferences().getBoolean(CLEAN_FEED_FILTER, false);
                    cleanFeedFilterEnabled = enabled;
                }
            }
        }
        return enabled;
    }

    public static void setCleanFeedFilterEnabled(boolean enabled) {
        preferences().edit().putBoolean(CLEAN_FEED_FILTER, enabled).apply();
        cleanFeedFilterEnabled = enabled;
    }

    public static String getCustomFeedKeywords() {
        try {
            return preferences().getString(CUSTOM_FEED_KEYWORDS, "");
        } catch (Throwable e) {
            // Preferences are unavailable without an application context (e.g. in unit tests).
            return "";
        }
    }

    public static void setCustomFeedKeywords(String keywords) {
        preferences().edit().putString(CUSTOM_FEED_KEYWORDS, keywords == null ? "" : keywords.trim()).apply();
    }

    public static boolean isCompactMediaAlbumsEnabled() {
        Boolean enabled = compactMediaAlbumsEnabled;
        if (enabled == null) {
            synchronized (HgramConfig.class) {
                enabled = compactMediaAlbumsEnabled;
                if (enabled == null) {
                    enabled = preferences().getBoolean(COMPACT_MEDIA_ALBUMS, false);
                    compactMediaAlbumsEnabled = enabled;
                }
            }
        }
        return enabled;
    }

    public static void setCompactMediaAlbumsEnabled(boolean enabled) {
        boolean previous = isCompactMediaAlbumsEnabled();
        preferences().edit().putBoolean(COMPACT_MEDIA_ALBUMS, enabled).apply();
        compactMediaAlbumsEnabled = enabled;
        if (previous != enabled) {
            compactMediaAlbumsVersion++;
        }
    }

    public static int getCompactMediaAlbumsVersion() {
        return compactMediaAlbumsVersion;
    }

    public static boolean isPinToScreenEnabled() {
        Boolean enabled = pinToScreenEnabled;
        if (enabled == null) {
            synchronized (HgramConfig.class) {
                enabled = pinToScreenEnabled;
                if (enabled == null) {
                    enabled = preferences().getBoolean(PIN_TO_SCREEN, true);
                    pinToScreenEnabled = enabled;
                }
            }
        }
        return enabled;
    }

    public static void setPinToScreenEnabled(boolean enabled) {
        preferences().edit().putBoolean(PIN_TO_SCREEN, enabled).apply();
        pinToScreenEnabled = enabled;
    }

    public static boolean isAntiPeekGestureEnabled() {
        return readCachedBoolean(ANTI_PEEK_GESTURE, antiPeekGestureEnabled, value -> antiPeekGestureEnabled = value);
    }

    public static void setAntiPeekGestureEnabled(boolean enabled) {
        preferences().edit().putBoolean(ANTI_PEEK_GESTURE, enabled).apply();
        antiPeekGestureEnabled = enabled;
    }

    public static final int WEAK_NETWORK_AUTO = 0;
    public static final int WEAK_NETWORK_ALWAYS = 1;
    public static final int WEAK_NETWORK_OFF = 2;
    private static final String WEAK_NETWORK_MODE = "weak_network_mode";
    private static final String NOTIF_MEDIA_STYLE = "notif_media_style";
    private static volatile int weakNetworkMode = -1;
    private static volatile int notifMediaStyle = -1;

    /**
     * Controls the low-bandwidth behaviour. Auto enables it when the connection looks slow,
     * Always forces it (useful for testing and for users on very poor links that still
     * report a fast network type), Off disables it completely.
     */
    public static int getWeakNetworkMode() {
        int value = weakNetworkMode;
        if (value < 0) {
            value = preferences().getInt(WEAK_NETWORK_MODE, WEAK_NETWORK_AUTO);
            if (value != WEAK_NETWORK_ALWAYS && value != WEAK_NETWORK_OFF) {
                value = WEAK_NETWORK_AUTO;
            }
            weakNetworkMode = value;
        }
        return value;
    }

    public static void setWeakNetworkMode(int mode) {
        final int value = mode == WEAK_NETWORK_ALWAYS || mode == WEAK_NETWORK_OFF ? mode : WEAK_NETWORK_AUTO;
        preferences().edit().putInt(WEAK_NETWORK_MODE, value).apply();
        weakNetworkMode = value;
    }

    public static final int NOTIF_MEDIA_STYLE_AUTO = 0;
    public static final int NOTIF_MEDIA_STYLE_SYSTEM = 1;
    public static final int NOTIF_MEDIA_STYLE_CUSTOM = 2;

    /** 0 = platform default/auto, 1 = system BigPictureStyle, 2 = custom RemoteViews. */
    public static int getNotifMediaStyle() {
        int value = notifMediaStyle;
        if (value < 0) {
            value = preferences().getInt(NOTIF_MEDIA_STYLE, NOTIF_MEDIA_STYLE_AUTO);
            if (value != NOTIF_MEDIA_STYLE_SYSTEM && value != NOTIF_MEDIA_STYLE_CUSTOM) {
                value = NOTIF_MEDIA_STYLE_AUTO;
            }
            notifMediaStyle = value;
        }
        return value;
    }

    public static void setNotifMediaStyle(int style) {
        final int value = style == NOTIF_MEDIA_STYLE_SYSTEM || style == NOTIF_MEDIA_STYLE_CUSTOM
                ? style : NOTIF_MEDIA_STYLE_AUTO;
        preferences().edit().putInt(NOTIF_MEDIA_STYLE, value).apply();
        notifMediaStyle = value;
    }

    public static final String STT_ENGINE_VOSK = "vosk";
    public static final String STT_ENGINE_SHERPA = "sherpa";
    public static final String STT_ENGINE_GIGAAM = "gigaam";
    private static final String STT_ENGINE = "stt_engine";

    public static String getSttEngine() {
        String value = preferences().getString(STT_ENGINE, STT_ENGINE_VOSK);
        if (STT_ENGINE_SHERPA.equals(value)) {
            return STT_ENGINE_SHERPA;
        }
        if (STT_ENGINE_GIGAAM.equals(value)) {
            return STT_ENGINE_GIGAAM;
        }
        return STT_ENGINE_VOSK;
    }

    public static void setSttEngine(String engine) {
        final String value = STT_ENGINE_SHERPA.equals(engine) ? STT_ENGINE_SHERPA
                : STT_ENGINE_GIGAAM.equals(engine) ? STT_ENGINE_GIGAAM : STT_ENGINE_VOSK;
        preferences().edit().putString(STT_ENGINE, value).apply();
    }

    public static final String SHERPA_PROVIDER_CPU = "cpu";
    public static final String SHERPA_PROVIDER_XNNPACK = "xnnpack";
    public static final String SHERPA_PROVIDER_NNAPI = "nnapi";
    private static final String SHERPA_PROVIDER = "sherpa_provider";
    private static volatile String sherpaProvider;

    private static String preferredProvider() {
        return android.os.Build.VERSION.SDK_INT >= 27 ? SHERPA_PROVIDER_NNAPI : SHERPA_PROVIDER_CPU;
    }

    public static String getSherpaProvider() {
        String value = sherpaProvider;
        if (value == null) {
            value = preferences().getString(SHERPA_PROVIDER, preferredProvider());
            if (!SHERPA_PROVIDER_XNNPACK.equals(value) && !SHERPA_PROVIDER_NNAPI.equals(value)
                    && !SHERPA_PROVIDER_CPU.equals(value)) {
                value = preferredProvider();
            }
            sherpaProvider = value;
        }
        return value;
    }

    public static void setSherpaProvider(String provider) {
        String value = SHERPA_PROVIDER_XNNPACK.equals(provider) || SHERPA_PROVIDER_NNAPI.equals(provider)
                ? provider : SHERPA_PROVIDER_CPU;
        preferences().edit().putString(SHERPA_PROVIDER, value).apply();
        sherpaProvider = value;
    }

    public static boolean isHapticEnabled() {
        return preferences().getBoolean(HAPTIC_ENABLED, true);
    }

    public static boolean isSummarizerEnabled() {
        return preferences().getBoolean(SUMMARIZER_ENABLED, false);
    }

    public static void setSummarizerEnabled(boolean enabled) {
        preferences().edit().putBoolean(SUMMARIZER_ENABLED, enabled).apply();
        summarizerVersion++;
    }

    public static int getSummarizerVersion() {
        return summarizerVersion;
    }

    public static int getSummarizerMinWords() {
        return Math.max(10, Math.min(500, preferences().getInt(SUMMARIZER_MIN_WORDS, 50)));
    }

    public static void setSummarizerMinWords(int words) {
        preferences().edit().putInt(SUMMARIZER_MIN_WORDS, Math.max(10, Math.min(500, words))).apply();
        summarizerVersion++;
    }

    public static String getSummarizerKey() {
        return preferences().getString(SUMMARIZER_KEY, "");
    }

    public static void setSummarizerKey(String key) {
        preferences().edit().putString(SUMMARIZER_KEY, key == null ? "" : key.trim()).apply();
    }

    public static String getSummarizerModel() {
        return preferences().getString(SUMMARIZER_MODEL, "");
    }

    public static void setSummarizerModel(String model) {
        preferences().edit().putString(SUMMARIZER_MODEL, model == null ? "" : model.trim()).apply();
    }

    public static String getSummarizerSystemPrompt() {
        final String value = preferences().getString(SUMMARIZER_PROMPT, "");
        return value == null || value.trim().isEmpty() ? HgramSummarizer.DEFAULT_PROMPT : value;
    }

    public static void setSummarizerSystemPrompt(String prompt) {
        String value = prompt == null ? "" : prompt;
        if (value.length() > HgramSummarizer.MAX_SYSTEM_PROMPT_CHARS) {
            value = value.substring(0, HgramSummarizer.MAX_SYSTEM_PROMPT_CHARS);
        }
        preferences().edit().putString(SUMMARIZER_PROMPT, value).apply();
    }

    public static boolean isSummarizerShowPaid() {
        return preferences().getBoolean(SUMMARIZER_SHOW_PAID, false);
    }

    public static void setSummarizerShowPaid(boolean show) {
        preferences().edit().putBoolean(SUMMARIZER_SHOW_PAID, show).apply();
    }

    public static void resetSummarizer() {
        preferences().edit().remove(SUMMARIZER_ENABLED).remove(SUMMARIZER_MIN_WORDS)
                .remove(SUMMARIZER_KEY).remove(SUMMARIZER_MODEL).remove(SUMMARIZER_PROMPT)
                .remove(SUMMARIZER_SHOW_PAID).apply();
    }

    public static void setHapticEnabled(boolean enabled) {
        preferences().edit().putBoolean(HAPTIC_ENABLED, enabled).apply();
    }

    public static boolean isExifCleanupEnabled() {
        return preferences().getBoolean(EXIF_CLEANUP, true);
    }

    public static void setExifCleanupEnabled(boolean enabled) {
        preferences().edit().putBoolean(EXIF_CLEANUP, enabled).apply();
    }

    public static boolean isOfflineTranscriptionEnabled() {
        return readCachedBoolean(OFFLINE_TRANSCRIPTION, offlineTranscriptionEnabled, value -> offlineTranscriptionEnabled = value);
    }

    public static void setOfflineTranscriptionEnabled(boolean enabled) {
        preferences().edit().putBoolean(OFFLINE_TRANSCRIPTION, enabled).apply();
        offlineTranscriptionEnabled = enabled;
    }

    public static boolean isAutoLoudnessEnabled() {
        return readCachedBoolean(AUTO_LOUDNESS, autoLoudnessEnabled, value -> autoLoudnessEnabled = value);
    }

    public static void setAutoLoudnessEnabled(boolean enabled) {
        preferences().edit().putBoolean(AUTO_LOUDNESS, enabled).apply();
        autoLoudnessEnabled = enabled;
    }

    public static boolean isSmartFrameThrottlingEnabled() {
        return readCachedBoolean(SMART_FRAME_THROTTLING, smartFrameThrottlingEnabled, value -> smartFrameThrottlingEnabled = value);
    }

    public static void setSmartFrameThrottlingEnabled(boolean enabled) {
        preferences().edit().putBoolean(SMART_FRAME_THROTTLING, enabled).apply();
        smartFrameThrottlingEnabled = enabled;
    }

    public static boolean isFastNetworkHandoverEnabled() {
        return readCachedBoolean(FAST_NETWORK_HANDOVER, fastNetworkHandoverEnabled, value -> fastNetworkHandoverEnabled = value);
    }

    public static void setFastNetworkHandoverEnabled(boolean enabled) {
        preferences().edit().putBoolean(FAST_NETWORK_HANDOVER, enabled).apply();
        fastNetworkHandoverEnabled = enabled;
    }

    public static boolean isAllowScreenshotsEnabled() {
        Boolean enabled = allowScreenshots;
        if (enabled == null) {
            synchronized (HgramConfig.class) {
                enabled = allowScreenshots;
                if (enabled == null) {
                    enabled = preferences().getBoolean(ALLOW_SCREENSHOTS, true);
                    allowScreenshots = enabled;
                }
            }
        }
        return enabled;
    }

    public static void setAllowScreenshotsEnabled(boolean enabled) {
        preferences().edit().putBoolean(ALLOW_SCREENSHOTS, enabled).apply();
        allowScreenshots = enabled;
    }

    private static boolean readCachedBoolean(String key, Boolean cached, java.util.function.Consumer<Boolean> setter) {
        if (cached != null) {
            return cached;
        }
        synchronized (HgramConfig.class) {
            cached = preferences().getBoolean(key, false);
            setter.accept(cached);
        }
        return cached;
    }

    public static boolean isAntiPeekActive() {
        return HgramAntiPeek.isActive();
    }

    public static void setAntiPeekActive(boolean active) {
        HgramAntiPeek.setActive(active);
    }

    public static final int ANTI_PEEK_SENSITIVITY_LOW = 0;
    public static final int ANTI_PEEK_SENSITIVITY_MEDIUM = 1;
    public static final int ANTI_PEEK_SENSITIVITY_HIGH = 2;

    public static final int DEFAULT_ANTI_PEEK_HOLD_MIN_MS = 200;
    public static final int DEFAULT_ANTI_PEEK_HOLD_MAX_MS = 1200;
    private static final int ANTI_PEEK_HOLD_MIN_LIMIT_MS = 100;
    private static final int ANTI_PEEK_HOLD_MAX_LIMIT_MS = 3000;

    public static boolean isValidAntiPeekHoldWindow(int minMs, int maxMs) {
        return minMs >= ANTI_PEEK_HOLD_MIN_LIMIT_MS
                && maxMs <= ANTI_PEEK_HOLD_MAX_LIMIT_MS
                && minMs < maxMs;
    }

    /**
     * Imported settings can contain a string/boolean under a numeric key. SharedPreferences
     * throws ClassCastException in that case, so read through a safe helper and rewrite the
     * key with a sanitized integer.
     */
    private static int readIntPreference(android.content.SharedPreferences prefs,
                                         String key, int fallback) {
        try {
            return prefs.getInt(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    private static void loadAntiPeekHoldWindow() {
        if (antiPeekHoldMin > 0 && antiPeekHoldMax > 0) {
            return;
        }
        final android.content.SharedPreferences prefs = preferences();
        final int missing = Integer.MIN_VALUE;
        int minMs = readIntPreference(prefs, ANTI_PEEK_HOLD_MIN_MS, missing);
        int maxMs = readIntPreference(prefs, ANTI_PEEK_HOLD_MAX_MS, missing);
        if (minMs == missing || maxMs == missing
                || !isValidAntiPeekHoldWindow(minMs, maxMs)) {
            minMs = DEFAULT_ANTI_PEEK_HOLD_MIN_MS;
            maxMs = DEFAULT_ANTI_PEEK_HOLD_MAX_MS;
            prefs.edit()
                    .putInt(ANTI_PEEK_HOLD_MIN_MS, minMs)
                    .putInt(ANTI_PEEK_HOLD_MAX_MS, maxMs)
                    .apply();
        }
        antiPeekHoldMin = minMs;
        antiPeekHoldMax = maxMs;
    }

    public static int getAntiPeekHoldMinMs() {
        loadAntiPeekHoldWindow();
        return antiPeekHoldMin;
    }

    public static int getAntiPeekHoldMaxMs() {
        loadAntiPeekHoldWindow();
        return antiPeekHoldMax;
    }

    public static void setAntiPeekHoldWindow(int minMs, int maxMs) {
        if (!isValidAntiPeekHoldWindow(minMs, maxMs)) {
            minMs = DEFAULT_ANTI_PEEK_HOLD_MIN_MS;
            maxMs = DEFAULT_ANTI_PEEK_HOLD_MAX_MS;
        }
        preferences().edit()
                .putInt(ANTI_PEEK_HOLD_MIN_MS, minMs)
                .putInt(ANTI_PEEK_HOLD_MAX_MS, maxMs)
                .apply();
        antiPeekHoldMin = minMs;
        antiPeekHoldMax = maxMs;
    }

    /**
     * Older builds exposed a hold window (300/500/800 ms) that mapped to a sensitivity
     * preset. Legacy 300 ms migrates to HIGH, 800 ms to LOW, and every other value
     * (including 500 ms) to MEDIUM.
     */
    public static int getAntiPeekSensitivity() {
        int value = antiPeekSensitivity;
        if (value < 0) {
            final android.content.SharedPreferences prefs = preferences();
            value = readIntPreference(prefs, ANTI_PEEK_SENSITIVITY, -1);
            if (value != ANTI_PEEK_SENSITIVITY_LOW && value != ANTI_PEEK_SENSITIVITY_MEDIUM && value != ANTI_PEEK_SENSITIVITY_HIGH) {
                final int legacyMin = readIntPreference(prefs, "anti_peek_hold_min_ms", 500);
                if (legacyMin == 300) {
                    value = ANTI_PEEK_SENSITIVITY_HIGH;
                } else if (legacyMin == 800) {
                    value = ANTI_PEEK_SENSITIVITY_LOW;
                } else {
                    value = ANTI_PEEK_SENSITIVITY_MEDIUM;
                }
                prefs.edit().putInt(ANTI_PEEK_SENSITIVITY, value).apply();
            }
            antiPeekSensitivity = value;
        }
        return value;
    }

    public static void setAntiPeekSensitivity(int value) {
        final int safe = value == ANTI_PEEK_SENSITIVITY_LOW || value == ANTI_PEEK_SENSITIVITY_HIGH
                ? value : ANTI_PEEK_SENSITIVITY_MEDIUM;
        preferences().edit().putInt(ANTI_PEEK_SENSITIVITY, safe).apply();
        antiPeekSensitivity = safe;
    }

    public static boolean isAntiPeekShowToast() {
        Boolean value = antiPeekShowToast;
        if (value == null) {
            value = preferences().getBoolean(ANTI_PEEK_SHOW_TOAST, true);
            antiPeekShowToast = value;
        }
        return value;
    }

    public static void setAntiPeekShowToast(boolean enabled) {
        preferences().edit().putBoolean(ANTI_PEEK_SHOW_TOAST, enabled).apply();
        antiPeekShowToast = enabled;
    }

    public static boolean isAntiPeekHapticEnabled() {
        Boolean value = antiPeekHaptic;
        if (value == null) {
            value = preferences().getBoolean(ANTI_PEEK_HAPTIC, true);
            antiPeekHaptic = value;
        }
        return value;
    }

    public static void setAntiPeekHapticEnabled(boolean enabled) {
        preferences().edit().putBoolean(ANTI_PEEK_HAPTIC, enabled).apply();
        antiPeekHaptic = enabled;
    }

    public static void reset() {
        boolean compactWasEnabled = isCompactMediaAlbumsEnabled();
        preferences().edit().clear().apply();
        downloadThreads = 0;
        notifMediaStyle = 0;
        shortMessageMergeEnabled = false;
        shortMergeMaxChars = -1;
        shortMergeMaxWords = -1;
        shortMergeMaxWindow = -1;
        shortMergeMaxChain = -1;
        shortMessageMergeVersion++;
        shortMergeCollapseHintShown = false;
        similarPostDeduplicationEnabled = false;
        cleanFeedFilterEnabled = false;
        compactMediaAlbumsEnabled = false;
        pinToScreenEnabled = true;
        antiPeekGestureEnabled = false;
        antiPeekSensitivity = -1;
        antiPeekShowToast = true;
        antiPeekHaptic = true;
        antiPeekHoldMin = -1;
        antiPeekHoldMax = -1;
        HgramHapticProfile.resetAll();
        resetSummarizer();
        HgramAntiPeek.stop();
        HgramAntiPeek.setActive(false);
        offlineTranscriptionEnabled = false;
        autoLoudnessEnabled = false;
        smartFrameThrottlingEnabled = false;
        fastNetworkHandoverEnabled = false;
        allowScreenshots = true;
        sherpaProvider = null;
        if (compactWasEnabled) {
            compactMediaAlbumsVersion++;
        }
    }
}
