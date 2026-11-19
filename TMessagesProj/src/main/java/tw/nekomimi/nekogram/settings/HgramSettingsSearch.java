package tw.nekomimi.nekogram.settings;

import java.util.Locale;

import xyz.nextalone.nagram.hgram.HgramConfig;

/**
 * Pure keyword helper for the Hgram settings search. Kept free of Android UI so it can be tested
 * without Robolectric. Row ids mirror HgramSettingsActivity.
 */
final class HgramSettingsSearch {

    static final int ROW_SIMILAR_POST_DEDUPLICATION = 1;
    static final int ROW_CLEAN_FEED_FILTER = 3;
    static final int ROW_COMPACT_ALBUMS = 5;
    static final int ROW_AUTO_LOUDNESS = 8;
    static final int ROW_OFFLINE_TRANSCRIPTION = 12;
    static final int ROW_STT_ENGINE = 14;
    static final int ROW_VOSK_MODELS = 15;
    static final int ROW_SHERPA_MODEL = 16;
    static final int ROW_SHERPA_DEVICE = 42;
    static final int ROW_PIN_TO_SCREEN = 18;
    static final int ROW_ANTI_PEEK = 20;
    static final int ROW_ALLOW_SCREENSHOTS = 22;
    static final int ROW_DECOY_PASSCODE = 24;
    static final int ROW_NOTIFICATION_DEBOUNCE = 27;
    static final int ROW_FRAME_THROTTLING = 29;
    static final int ROW_FAST_HANDOVER = 31;
    static final int ROW_DOWNLOAD_THREADS = 33;
    static final int ROW_SHORT_MERGE = 35;
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

    private static final String HAPTIC_COMMON =
            "вибра вибро вибрация haptic feedback сила длительность";
    private static final String[] HAPTIC_PLACE_KEYWORDS = {
            "тумблеры переключатели toggles",
            "списки меню пункты list items",
            "долгое нажатие long press",
            "сообщения реакции контекстное меню message actions",
            "голосовые запись voice record",
            "эмодзи стикеры gif emoji stickers",
            "альбомы фото свайп albums swipe",
            "истории stories",
            "звонки calls",
            "камера рисование camera paint",
            "код-пароль разблокировка passcode lock",
            "анти-подглядывание anti-peek маска anti peek",
            "скрытое пространство secret space",
            "курсор выделение текста text cursor",
            "mini apps miniapps боты"
    };

    private HgramSettingsSearch() {
    }

    static boolean isRowVisible(int row, String engine) {
        final String normalized = normalizeEngine(engine);
        if (row == HgramSettingsActivity.ROW_VOSK_MODELS) {
            return HgramConfig.STT_ENGINE_VOSK.equals(normalized);
        }
        if (row == HgramSettingsActivity.ROW_SHERPA_MODEL
                || row == HgramSettingsActivity.ROW_SHERPA_DEVICE) {
            return HgramConfig.STT_ENGINE_SHERPA.equals(normalized);
        }
        if (row == HgramSettingsActivity.ROW_GIGAAM_MODEL
                || row == HgramSettingsActivity.ROW_GIGAAM_INFO) {
            return HgramConfig.STT_ENGINE_GIGAAM.equals(normalized);
        }
        return true;
    }

    static String normalizeEngine(String engine) {
        if (HgramConfig.STT_ENGINE_GIGAAM.equals(engine)) return HgramConfig.STT_ENGINE_GIGAAM;
        if (HgramConfig.STT_ENGINE_SHERPA.equals(engine)) return HgramConfig.STT_ENGINE_SHERPA;
        return HgramConfig.STT_ENGINE_VOSK;
    }

    static boolean matches(int row, String query, String localizedText) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        final String needle = query.trim().toLowerCase(Locale.ROOT);
        if (localizedText != null && localizedText.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        final String keywords = keywords(row);
        return keywords != null && keywords.toLowerCase(Locale.ROOT).contains(needle);
    }

    static String keywords(int row) {
        if (row == ROW_HAPTIC_TOGGLE) {
            return HAPTIC_COMMON + " настройки вибрации тумблеры списки альбомы звонки mini apps";
        }
        if (row >= ROW_HAPTIC_BASE && row < ROW_HAPTIC_BASE + HAPTIC_PLACE_KEYWORDS.length) {
            return HAPTIC_COMMON + " " + HAPTIC_PLACE_KEYWORDS[row - ROW_HAPTIC_BASE];
        }
        switch (row) {
            case ROW_SUMMARIZER_ENABLE:
            case ROW_SUMMARIZER_TRIGGER:
            case ROW_SUMMARIZER_KEY:
            case ROW_SUMMARIZER_MODEL:
            case ROW_SUMMARIZER_PROMPT:
                return "суммар summary суммаризация пересказ openrouter ключ key модель model промпт prompt порог words слова ai";
            case ROW_ANTI_PEEK:
                return "antipeek peek anti-peek скрыть подглядывание подгляд маска переворот приватность privacy hide";
            case ROW_STT_ENGINE:
                return "stt распознавание расшифровка vosk sherpa gigaam голос speech transcription engine движок";
            case ROW_VOSK_MODELS:
                return "vosk модель model модели распознавание скачать download"; 
            case ROW_SHERPA_MODEL:
                return "sherpa модель model распознавание sherpa-onnx скачать download";
            case ROW_SHERPA_DEVICE:
                return "устройство device provider провайдер cpu nnapi xnnpack распознавание";
            case ROW_GIGAAM_MODEL:
                return "gigaam модель model распознавание скачать download 305";
            case ROW_NOTIFICATION_DEBOUNCE:
                return "debounce уведомления notification 30 задержка";
            case ROW_FRAME_THROTTLING:
                return "fps кадр throttle частота frame 60";
            case ROW_WEAK_NETWORK:
                return "слабая сеть weak network мобильная mobile всегда off";
            case ROW_NOTIF_MEDIA_STYLE:
                return "уведомление превью фото hyperos miui media notification style bigpicture";
            case ROW_DOWNLOAD_THREADS:
            case ROW_UPLOAD_THREADS:
                return "потоки threads загрузка скачивание upload download";
            case ROW_DECOY_PASSCODE:
                return "ложный pin пароль скрытые чаты decoy passcode hidden";
            case ROW_RESET_HGRAM_SETTINGS:
                return "сброс вернуть по умолчанию reset defaults";
            case ROW_OFFLINE_TRANSCRIPTION:
                return "офлайн offline расшифровка transcription голос voice";
            case ROW_ALLOW_SCREENSHOTS:
                return "скриншоты screenshots защита flag secure";
            case ROW_FAST_HANDOVER:
                return "переключение сети handover dc";
            case ROW_SHORT_MERGE:
                return "шум шумные шумный склейка склеивать цепочка цепочки короткие сообщения merge short messages";
            case ROW_COMPACT_ALBUMS:
                return "компактные альбомы compact albums";
            case ROW_PIN_TO_SCREEN:
                return "закреплять сообщение pin to screen";
            case ROW_AUTO_LOUDNESS:
                return "громкость loudness auto";
            case ROW_SIMILAR_POST_DEDUPLICATION:
                return "дубликаты dedup similar posts";
            case ROW_CLEAN_FEED_FILTER:
                return "фильтр ленты feed cleanup";
            default:
                return "";
        }
    }
}
