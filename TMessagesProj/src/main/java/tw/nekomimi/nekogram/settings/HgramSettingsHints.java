package tw.nekomimi.nekogram.settings;

import org.telegram.messenger.R;

/** Long-press hint resources for Hgram settings rows. Row ids mirror HgramSettingsActivity. */
final class HgramSettingsHints {

    static final int ROW_SIMILAR_POST_DEDUPLICATION = 1;
    static final int ROW_CLEAN_FEED_FILTER = 3;
    static final int ROW_COMPACT_ALBUMS = 5;
    static final int ROW_AUTO_LOUDNESS = 8;
    static final int ROW_OFFLINE_TRANSCRIPTION = 12;
    static final int ROW_STT_ENGINE = 14;
    static final int ROW_VOSK_MODELS = 15;
    static final int ROW_SHERPA_MODEL = 16;
    static final int ROW_PIN_TO_SCREEN = 18;
    static final int ROW_ANTI_PEEK = 20;
    static final int ROW_ALLOW_SCREENSHOTS = 22;
    static final int ROW_DECOY_PASSCODE = 24;
    static final int ROW_NOTIFICATION_DEBOUNCE = 27;
    static final int ROW_FRAME_THROTTLING = 29;
    static final int ROW_FAST_HANDOVER = 31;
    static final int ROW_DOWNLOAD_THREADS = 33;
    static final int ROW_SHORT_MERGE = 35;
    static final int ROW_FEED_KEYWORDS = 41;
    static final int ROW_SHERPA_DEVICE = 42;
    static final int ROW_GIGAAM_MODEL = 43;
    static final int ROW_WEAK_NETWORK = 44;
    static final int ROW_UPLOAD_THREADS = 45;
    static final int ROW_NOTIF_MEDIA_STYLE = 46;
    static final int ROW_RESET_HGRAM_SETTINGS = 47;
    static final int ROW_HAPTIC_TOGGLE = 56;
    static final int ROW_HAPTIC_BASE = 57;
    static final int ROW_SUMMARIZER_ENABLE = 72;
    static final int ROW_SUMMARIZER_TRIGGER = 73;
    static final int ROW_SUMMARIZER_KEY = 74;
    static final int ROW_SUMMARIZER_MODEL = 75;
    static final int ROW_SUMMARIZER_PROMPT = 76;
    static final int ROW_EXIF_CLEAN = 78;
    static final int ROW_EXIF_CLEAR = 79;
    static final int ROW_OCR_LONG_PRESS = 80;
    static final int ROW_OCR_MODELS = 81;
    static final int ROW_HIGH_REFRESH_BOOST = 82;

    private HgramSettingsHints() {
    }

    static int hintRes(int row) {
        switch (row) {
            case ROW_SHORT_MERGE: return R.string.HgramHintShortMerge;
            case ROW_COMPACT_ALBUMS: return R.string.HgramHintCompactAlbums;
            case ROW_PIN_TO_SCREEN: return R.string.HgramHintPinToScreen;
            case ROW_SIMILAR_POST_DEDUPLICATION: return R.string.HgramHintDeduplication;
            case ROW_CLEAN_FEED_FILTER: return R.string.HgramHintCleanFeed;
            case ROW_FEED_KEYWORDS: return R.string.HgramHintFeedKeywords;
            case ROW_AUTO_LOUDNESS: return R.string.HgramHintAutoLoudness;
            case ROW_STT_ENGINE: return R.string.HgramHintSttEngine;
            case ROW_VOSK_MODELS: return R.string.HgramHintVoskModels;
            case ROW_SHERPA_MODEL: return R.string.HgramHintSherpaModel;
            case ROW_GIGAAM_MODEL: return R.string.HgramHintGigaAmModel;
            case ROW_SHERPA_DEVICE: return R.string.HgramHintSherpaDevice;
            case ROW_OFFLINE_TRANSCRIPTION: return R.string.HgramHintOfflineTranscription;
            case ROW_NOTIFICATION_DEBOUNCE: return R.string.HgramHintNotificationDebounce;
            case ROW_FRAME_THROTTLING: return R.string.HgramHintFrameThrottling;
            case ROW_FAST_HANDOVER: return R.string.HgramHintFastHandover;
            case ROW_DECOY_PASSCODE: return R.string.HgramHintDecoyPasscode;
            case ROW_ANTI_PEEK: return R.string.HgramHintAntiPeek;
            case ROW_ALLOW_SCREENSHOTS: return R.string.HgramHintAllowScreenshots;
            case ROW_DOWNLOAD_THREADS: return R.string.HgramHintDownloadThreads;
            case ROW_UPLOAD_THREADS: return R.string.HgramHintUploadThreads;
            case ROW_WEAK_NETWORK: return R.string.HgramHintWeakNetwork;
            case ROW_NOTIF_MEDIA_STYLE: return R.string.HgramHintNotifMediaStyle;
            case ROW_HAPTIC_TOGGLE: return R.string.HgramHintHapticEnabled;
            case ROW_HIGH_REFRESH_BOOST: return R.string.HgramHintHighRefreshBoost;
            case ROW_SUMMARIZER_ENABLE:
            case ROW_SUMMARIZER_TRIGGER:
            case ROW_SUMMARIZER_KEY:
            case ROW_SUMMARIZER_MODEL:
            case ROW_SUMMARIZER_PROMPT: return R.string.HgramHintSummarizer;
            case ROW_EXIF_CLEAN: return R.string.HgramExifCleanInfo;
            case ROW_EXIF_CLEAR: return R.string.HgramExifClearPreparedInfo;
            case ROW_OCR_LONG_PRESS: return R.string.HgramOcrLongPressInfo;
            case ROW_OCR_MODELS: return R.string.HgramOcrModelsTitle;
            case ROW_RESET_HGRAM_SETTINGS: return R.string.HgramHintResetSettings;
            default:
                if (row >= ROW_HAPTIC_BASE && row < ROW_HAPTIC_BASE + 15) {
                    return R.string.HgramHintHapticPlace;
                }
                return 0;
        }
    }
}
