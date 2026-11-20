package xyz.nextalone.nagram.hgram;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HgramVoiceNotificationTextTest {

    @Before
    public void setup() {
        HgramVoiceNotificationText.clearCacheForTests();
    }

    @Test
    public void formatBigTextJoinsBaseAndTranscript() {
        assertEquals("Hello\n\nworld",
                HgramVoiceNotificationText.formatBigText("Hello", " world ", 900));
    }

    @Test
    public void formatBigTextSkipsEmptyParts() {
        assertEquals("text", HgramVoiceNotificationText.formatBigText(null, "text", 900));
        assertEquals("base", HgramVoiceNotificationText.formatBigText("base", "", 900));
        assertEquals("", HgramVoiceNotificationText.formatBigText("  ", " ", 900));
    }

    @Test
    public void truncateUsesEllipsisAndRespectsLimit() {
        String result = HgramVoiceNotificationText.truncate("abcdefghij", 5);
        assertEquals("abcd…", result);
        assertEquals(5, result.length());
        assertEquals("hi", HgramVoiceNotificationText.truncate("  hi  ", 900));
    }

    @Test
    public void truncateReturnsEmptyForNonPositiveLimit() {
        assertEquals("", HgramVoiceNotificationText.truncate("abc", 0));
        assertEquals("", HgramVoiceNotificationText.truncate("abc", -3));
    }

    @Test
    public void truncateDoesNotSplitSurrogatePair() {
        String emoji = "\uD83D\uDE00";
        String result = HgramVoiceNotificationText.truncate("abc" + emoji + "def", 5);
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 2)));
        assertTrue(result.length() <= 5);
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < result.length()
                        && Character.isLowSurrogate(result.charAt(i + 1)));
            } else if (Character.isLowSurrogate(c)) {
                assertTrue(i > 0 && Character.isHighSurrogate(result.charAt(i - 1)));
            }
        }
    }

    @Test
    public void canShowTextRequiresVisibleChat() {
        assertTrue(HgramVoiceNotificationText.canShowText(false, false));
        assertFalse(HgramVoiceNotificationText.canShowText(true, false));
        assertFalse(HgramVoiceNotificationText.canShowText(false, true));
        assertFalse(HgramVoiceNotificationText.canShowText(true, true));
    }

    @Test
    public void encryptedChatHidesText() {
        assertFalse(HgramVoiceNotificationText.canShowText(false, false, true));
        assertFalse(HgramVoiceNotificationText.canShowText(true, false, true));
        assertFalse(HgramVoiceNotificationText.canShowText(false, true, true));
        assertTrue(HgramVoiceNotificationText.canShowText(false, false, false));
    }

    @Test
    public void privacyDecisionIsRecomputedFromCurrentFlags() {
        String text = "secret";
        assertEquals("secret", HgramVoiceNotificationText.formatBigText("", text, 900));
        // The cached/formatted text is identical; only the privacy flags decide visibility.
        assertTrue(HgramVoiceNotificationText.canShowText(false, false, false));
        assertFalse(HgramVoiceNotificationText.canShowText(false, true, false));
    }

    @Test
    public void cacheRoundTripsAndRemovesEmpty() {
        HgramVoiceNotificationText.Key key = HgramVoiceNotificationText.key(1, 777L, 42);
        HgramVoiceNotificationText.cache(key, "x");
        assertEquals("x", HgramVoiceNotificationText.cached(key));
        HgramVoiceNotificationText.cache(key, "");
        assertNull(HgramVoiceNotificationText.cached(key));
        HgramVoiceNotificationText.cache(key, null);
        assertNull(HgramVoiceNotificationText.cached(key));
        assertNull(HgramVoiceNotificationText.cached(null));
    }

    @Test
    public void cacheIsBoundedAndEvictsOldest() {
        for (int i = 0; i < 40; i++) {
            HgramVoiceNotificationText.cache(
                    HgramVoiceNotificationText.key(1, 777L, i), "t" + i);
        }
        assertNull(HgramVoiceNotificationText.cached(
                HgramVoiceNotificationText.key(1, 777L, 0)));
        assertEquals("t39", HgramVoiceNotificationText.cached(
                HgramVoiceNotificationText.key(1, 777L, 39)));
    }

    @Test
    public void keysAreValueObjects() {
        HgramVoiceNotificationText.Key same = HgramVoiceNotificationText.key(1, 777L, 42);
        HgramVoiceNotificationText.Key expected = HgramVoiceNotificationText.key(1, 777L, 42);
        assertEquals(expected, same);
        assertEquals(expected.hashCode(), same.hashCode());
        assertNotEquals(expected, HgramVoiceNotificationText.key(2, 777L, 42));
        assertNotEquals(expected, HgramVoiceNotificationText.key(1, 778L, 42));
        assertNotEquals(HgramVoiceNotificationText.key(1, 0x1234567800000001L, 42),
                HgramVoiceNotificationText.key(1, 0x1234567800000002L, 42));
    }
}
