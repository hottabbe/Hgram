package tw.nekomimi.nekogram.settings;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramSettingsSearchTest {

    @Test
    public void emptyQueryMatchesEverything() {
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_ANTI_PEEK, "", ""));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_RESET_HGRAM_SETTINGS, "  ", ""));
    }

    @Test
    public void synonymsFindExpectedRows() {
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_SHORT_MERGE, "шум", ""));
        assertFalse(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_ANTI_PEEK, "шум", ""));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_GIGAAM_MODEL, "gigaam", ""));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_FRAME_THROTTLING, "fps", ""));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_FRAME_THROTTLING, "кадр", ""));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_DOWNLOAD_THREADS, "потоки", ""));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_UPLOAD_THREADS, "потоки", ""));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_RESET_HGRAM_SETTINGS, "сброс", ""));
    }


    @Test
    public void engineControlsModelRows() {
        assertTrue(HgramSettingsSearch.isRowVisible(15, "vosk"));
        assertFalse(HgramSettingsSearch.isRowVisible(15, "sherpa"));
        assertTrue(HgramSettingsSearch.isRowVisible(16, "sherpa"));
        assertTrue(HgramSettingsSearch.isRowVisible(42, "sherpa"));
        assertFalse(HgramSettingsSearch.isRowVisible(42, "vosk"));
        assertTrue(HgramSettingsSearch.isRowVisible(43, "gigaam"));
        assertFalse(HgramSettingsSearch.isRowVisible(43, "sherpa"));
        assertTrue(HgramSettingsSearch.isRowVisible(HgramSettingsSearch.ROW_ANTI_PEEK, "vosk"));
    }

    @Test
    public void descriptionRowsAreNotSearchResults() {
        assertFalse(HgramSettingsSearch.matches(48, "склейка", ""));
        assertFalse(HgramSettingsSearch.matches(54, "уведомления", ""));
    }


    @Test
    public void noiseNowMeansShortMessageMergeNotAntiPeek() {
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_SHORT_MERGE,
                "шум", "Склеивать короткие сообщения"));
        assertFalse(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_ANTI_PEEK,
                "шум", "Скрывать чаты от подглядывания"));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_SHORT_MERGE,
                "склей", "Склеивать короткие сообщения"));
    }

    @Test
    public void modelSingularFindsEngineRows() {
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_VOSK_MODELS,
                "модель", "Модели Vosk"));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_SHERPA_MODEL,
                "модель", "Модель sherpa-onnx"));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_GIGAAM_MODEL,
                "модель", "Модель GigaAM v3"));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_SHERPA_DEVICE,
                "устройств", "Устройство распознавания"));
    }

    @Test
    public void commonQueriesHitLocalizedTitles() {
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_NOTIFICATION_DEBOUNCE,
                "уведомл", "Задержка уведомлений"));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_ANTI_PEEK,
                "подгляд", "Скрывать чаты от подглядывания"));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_DECOY_PASSCODE,
                "пароль", "Ложный код-пароль"));
        assertTrue(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_AUTO_LOUDNESS,
                "громк", "Громкость"));
    }

    @Test
    public void unrelatedQueryDoesNotMatch() {
        assertFalse(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_ANTI_PEEK, "gigaam", ""));
        assertFalse(HgramSettingsSearch.matches(HgramSettingsSearch.ROW_FRAME_THROTTLING, "сброс", ""));
    }

    @Test
    public void hapticQueriesFindMasterAndAllProfiles() {
        for (String query : new String[]{"вибро", "вибра", "haptic", "сила", "длительность"}) {
            assertTrue(query, HgramSettingsSearch.matches(
                    HgramSettingsSearch.ROW_HAPTIC_TOGGLE, query, ""));
            for (int i = 0; i < 15; i++) {
                assertTrue(query + " row " + i, HgramSettingsSearch.matches(
                        HgramSettingsSearch.ROW_HAPTIC_BASE + i, query, ""));
            }
        }
    }

    @Test
    public void hapticPlaceNamesFindTheirRows() {
        assertTrue(HgramSettingsSearch.matches(
                HgramSettingsSearch.ROW_HAPTIC_BASE, "тумблер", ""));
        assertTrue(HgramSettingsSearch.matches(
                HgramSettingsSearch.ROW_HAPTIC_BASE + 6, "альбом", ""));
        assertTrue(HgramSettingsSearch.matches(
                HgramSettingsSearch.ROW_HAPTIC_BASE + 8, "звонк", ""));
        assertTrue(HgramSettingsSearch.matches(
                HgramSettingsSearch.ROW_HAPTIC_BASE + 14, "mini", ""));
    }
}
