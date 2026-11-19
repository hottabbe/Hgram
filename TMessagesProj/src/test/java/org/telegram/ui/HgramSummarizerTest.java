package org.telegram.ui;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.nextalone.nagram.hgram.HgramConfig;
import xyz.nextalone.nagram.hgram.HgramShortMessageMerger;
import xyz.nextalone.nagram.hgram.HgramSummarizer;
import xyz.nextalone.nagram.hgram.HgramSummarizerModel;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class HgramSummarizerTest {
    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramConfig.resetSummarizer();
    }

    @Test
    public void wordThresholdBoundariesAndPunctuation() {
        assertEquals(0, HgramSummarizer.wordCount("…!?"));
        assertEquals(49, HgramSummarizer.wordCount(words(49)));
        assertEquals(50, HgramSummarizer.wordCount(words(50)));
        assertEquals(51, HgramSummarizer.wordCount(words(51)));
        assertEquals(4, HgramSummarizer.wordCount("Привет, мир — 12.5!"));
        assertEquals(50, HgramConfig.getSummarizerMinWords());
        HgramConfig.setSummarizerMinWords(5);
        assertEquals(10, HgramConfig.getSummarizerMinWords());
        HgramConfig.setSummarizerMinWords(5000);
        assertEquals(500, HgramConfig.getSummarizerMinWords());
    }

    @Test
    public void chainTextCarriesEveryLineLabel() {
        Map<Integer, HgramShortMessageMerger.Chain> chains = HgramShortMessageMerger.build(Arrays.asList(
                new HgramShortMessageMerger.Entry(1, 1000, 1, 7, "первая", true),
                new HgramShortMessageMerger.Entry(2, 1100, 1, 7, "вторая", true),
                new HgramShortMessageMerger.Entry(3, 1200, 1, 7, "третья", true)));
        String request = HgramSummarizer.buildRequestText(null, chains.get(1));
        assertTrue(request.startsWith("[1] первая\n[2] вторая\n[3] третья"));
    }

    @Test
    public void disabledByDefaultAndKeyStartsEmpty() {
        assertFalse(HgramConfig.isSummarizerEnabled());
        assertEquals("", HgramConfig.getSummarizerKey());
        assertEquals("", HgramConfig.getSummarizerModel());
        assertEquals(HgramSummarizer.DEFAULT_PROMPT, HgramConfig.getSummarizerSystemPrompt());
        HgramConfig.setSummarizerKey("sk-or-test-1234");
        assertEquals("sk-or-test-1234", HgramConfig.getSummarizerKey());
    }

    @Test
    public void buttonThresholdNineTenElevenAndChainAlways() {
        HgramConfig.setSummarizerEnabled(true);
        HgramConfig.setSummarizerMinWords(10);
        assertFalse(HgramSummarizer.isEligibleText(words(9)));
        assertTrue(HgramSummarizer.isEligibleText(words(10)));
        assertTrue(HgramSummarizer.isEligibleText(words(11)));

        Map<Integer, HgramShortMessageMerger.Chain> chains = HgramShortMessageMerger.build(Arrays.asList(
                new HgramShortMessageMerger.Entry(1, 1000, 1, 7, "a", true),
                new HgramShortMessageMerger.Entry(2, 1100, 1, 7, "b", true),
                new HgramShortMessageMerger.Entry(3, 1200, 1, 7, "c", true)));
        HgramConfig.setSummarizerMinWords(500);
        assertFalse(HgramSummarizer.isEligibleText(words(10)));
        assertTrue(HgramSummarizer.isEligibleChain(chains.get(1)));

        HgramConfig.setSummarizerEnabled(false);
        assertFalse(HgramSummarizer.isEligibleText(words(100)));
        assertFalse(HgramSummarizer.isEligibleChain(chains.get(1)));
    }

    @Test
    public void freeModelFilterKeepsOnlyTextToText() {
        final String[] freeIds = {
                "inclusionai/ling-3.2-flash:free",
                "baidu/ernie-5.5-flash-preview:free",
                "thinkingmachines/inkling-2-small:free",
                "liquid/lfm-3-1.7b:free",
                "particle-ai/particle-1:free",
                "recursive/flint-v1.0:free",
                "nvidia/nemotron-3.5-super-vl:free",
                "dots-studio/dots-3-note-preview:free",
                "nvidia/nemotron-3.5-lightning:free",
                "cohere/north-mini-code:free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
                "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
                "google/gemma-4-26b-a4b-it:free",
                "google/gemma-4-31b-it:free",
                "openrouter/free"
        };
        for (String id : freeIds) {
            assertTrue(id, HgramSummarizerModel.isFreeModel(id, 0.0, 0.0));
            assertTrue(id, HgramSummarizer.acceptsModel(true, true, true, true, id));
        }
        assertFalse(HgramSummarizer.acceptsModel(true, true, true, false, "google/lyria-3-pro-preview")); // lyria: audio output
        assertFalse(HgramSummarizer.acceptsModel(true, true, true, false, "google/lyria-3-clip-preview"));
        assertFalse(HgramSummarizer.acceptsModel(true, false, true, true, "any/model"));
        assertTrue(HgramSummarizer.acceptsModel(false, false, false, false, "meta-llama/llama-3-8b-instruct:free")); // missing fields: keep
    }

    @Test
    public void freeModelFilterRejectsNonTextNamesWhenArchitectureMissing() {
        assertFalse(HgramSummarizer.acceptsModel(false, false, false, false, "google/lyria-3-pro-preview"));
        assertFalse(HgramSummarizer.acceptsModel(false, false, false, false, "Google/LYRIA-3-Pro-Preview"));
        assertFalse(HgramSummarizer.acceptsModel(false, false, false, false, "openai/whisper-large-v3"));
        assertFalse(HgramSummarizer.acceptsModel(false, false, false, false, "openai/gpt-4o-audio-preview"));
        assertTrue(HgramSummarizer.acceptsModel(false, false, false, false, "meta-llama/llama-3-8b-instruct:free"));
    }

    @Test
    public void outgoingWideBubbleShrinksAndKeepsGap() {
        android.graphics.RectF out = new android.graphics.RectF();
        final float cellWidth = AndroidUtilities.dp(360);
        final float bubbleLeft = AndroidUtilities.dp(40);
        final float bubbleWidth = cellWidth - AndroidUtilities.dp(12) - bubbleLeft;
        final int size = AndroidUtilities.dp(32);
        final int tier = HgramSummarizer.summarizeBoundsAdaptive(out, true, cellWidth, bubbleWidth, false,
                0, AndroidUtilities.dp(500), 0, AndroidUtilities.dp(600),
                size, AndroidUtilities.dp(8), AndroidUtilities.dp(2));
        assertEquals(1, tier);
        assertEquals(size, out.width(), 0.01f);
        assertEquals(AndroidUtilities.dp(4), bubbleLeft - out.right, 0.01f);
        assertTrue(out.right <= bubbleLeft - AndroidUtilities.dp(2) + 0.01f);
        assertTrue(out.left >= AndroidUtilities.dp(2) - 0.01f);
    }

    @Test
    public void outgoingEdgeGapTiersReduceGapBeforeShrink() {
        android.graphics.RectF out = new android.graphics.RectF();
        final float cellWidth = AndroidUtilities.dp(360);
        final float bubbleLeft = AndroidUtilities.dp(36);
        final float bubbleWidth = cellWidth - AndroidUtilities.dp(12) - bubbleLeft;
        final int size = AndroidUtilities.dp(32);
        final int tier = HgramSummarizer.summarizeBoundsAdaptive(out, true, cellWidth, bubbleWidth, false,
                0, AndroidUtilities.dp(500), 0, AndroidUtilities.dp(600),
                size, AndroidUtilities.dp(8), AndroidUtilities.dp(2));
        assertEquals(1, tier);
        assertEquals(size, out.width(), 0.01f);
        assertEquals(AndroidUtilities.dp(2), bubbleLeft - out.right, 0.01f);
        // summarizeBounds delegates to the adaptive policy and keeps physical LTR edges.
        final int delegatedTier = HgramSummarizer.summarizeBounds(out, true, cellWidth, bubbleWidth, false,
                AndroidUtilities.dp(500), 0, 0, AndroidUtilities.dp(600),
                size, AndroidUtilities.dp(8), AndroidUtilities.dp(2));
        assertEquals(1, delegatedTier);
        assertTrue(out.left >= AndroidUtilities.dp(2) - 0.01f);
        assertTrue(out.right <= cellWidth - AndroidUtilities.dp(2) + 0.01f);
    }

    @Test
    public void outgoingAllowsAtMost8dpOverlap() {
        android.graphics.RectF out = new android.graphics.RectF();
        final float cellWidth = AndroidUtilities.dp(360);
        final float bubbleLeft = AndroidUtilities.dp(32);
        final float bubbleWidth = cellWidth - AndroidUtilities.dp(12) - bubbleLeft;
        final int size = AndroidUtilities.dp(32);
        final int tier = HgramSummarizer.summarizeBoundsAdaptive(out, true, cellWidth, bubbleWidth, false,
                0, AndroidUtilities.dp(500), 0, AndroidUtilities.dp(600),
                size, AndroidUtilities.dp(8), AndroidUtilities.dp(2));
        assertEquals(2, tier);
        assertEquals(size, out.width(), 0.01f);
        final float overlap = out.right - bubbleLeft;
        assertTrue(overlap > 0);
        assertEquals(AndroidUtilities.dp(8), overlap, 0.01f);
        assertTrue(overlap <= AndroidUtilities.dp(8) + 0.01f);
        assertTrue(out.left >= AndroidUtilities.dp(2) - 0.01f);
    }

    @Test
    public void outgoingShrinkFlooredAt24dp() {
        android.graphics.RectF out = new android.graphics.RectF();
        final float cellWidth = AndroidUtilities.dp(360);
        final float bubbleLeft = AndroidUtilities.dp(28);
        final float bubbleWidth = cellWidth - AndroidUtilities.dp(12) - bubbleLeft;
        final int size = AndroidUtilities.dp(48);
        final int tier = HgramSummarizer.summarizeBoundsAdaptive(out, true, cellWidth, bubbleWidth, false,
                0, AndroidUtilities.dp(500), 0, AndroidUtilities.dp(600),
                size, AndroidUtilities.dp(8), AndroidUtilities.dp(2));
        assertEquals(3, tier);
        assertEquals(AndroidUtilities.dp(24), out.width(), 0.01f);
        assertTrue(out.left >= AndroidUtilities.dp(2) - 0.01f);
    }

    @Test
    public void incomingMirrorsOutgoing() {
        android.graphics.RectF out = new android.graphics.RectF();
        final float cellWidth = AndroidUtilities.dp(360);
        final float bubbleRight = cellWidth - AndroidUtilities.dp(36);
        final float bubbleWidth = bubbleRight - AndroidUtilities.dp(12);
        final int size = AndroidUtilities.dp(32);
        final int tier = HgramSummarizer.summarizeBoundsAdaptive(out, false, cellWidth, bubbleWidth, false,
                0, AndroidUtilities.dp(500), 0, AndroidUtilities.dp(600),
                size, AndroidUtilities.dp(8), AndroidUtilities.dp(2));
        assertEquals(1, tier);
        assertEquals(size, out.width(), 0.01f);
        assertTrue(out.left >= bubbleRight + AndroidUtilities.dp(2) - 0.01f);
        assertTrue(out.right <= cellWidth - AndroidUtilities.dp(2) + 0.01f);
        // summarizeBounds keeps the same incoming edge and gap through the delegate.
        final int delegatedTier = HgramSummarizer.summarizeBounds(out, false, cellWidth, bubbleWidth, false,
                AndroidUtilities.dp(500), 0, 0, AndroidUtilities.dp(600),
                size, AndroidUtilities.dp(8), AndroidUtilities.dp(2));
        assertEquals(1, delegatedTier);
        assertTrue(out.left >= bubbleRight + AndroidUtilities.dp(2) - 0.01f);
        assertTrue(out.right <= cellWidth - AndroidUtilities.dp(2) + 0.01f);
    }

    @Test
    public void outgoingNoRoomFallsBackAboveBubble() {
        android.graphics.RectF out = new android.graphics.RectF();
        final float cellWidth = AndroidUtilities.dp(360);
        final float bubbleLeft = AndroidUtilities.dp(20);
        final float bubbleWidth = cellWidth - AndroidUtilities.dp(12) - bubbleLeft;
        final float bubbleTop = AndroidUtilities.dp(100);
        final float bubbleBottom = AndroidUtilities.dp(700);
        final int size = AndroidUtilities.dp(32);
        final int tier = HgramSummarizer.summarizeBoundsAdaptive(out, true, cellWidth, bubbleWidth, false,
                bubbleTop, bubbleBottom, 0, AndroidUtilities.dp(800),
                size, AndroidUtilities.dp(8), AndroidUtilities.dp(2));
        assertEquals(4, tier);
        assertEquals(size, out.width(), 0.01f);
        assertTrue(out.left >= AndroidUtilities.dp(2) - 0.01f);
        assertTrue(out.right <= cellWidth - AndroidUtilities.dp(2) + 0.01f);
        assertTrue(out.bottom <= bubbleTop + 0.01f || out.top >= bubbleBottom - 0.01f);
    }

    @Test
    public void tapInsideBoundsTriggersToggle() {
        android.graphics.RectF bounds = new android.graphics.RectF(100, 200, 180, 280);
        assertTrue(HgramSummarizer.isInsideButton(bounds, 140, 240, 4));
        assertTrue(HgramSummarizer.isInsideButton(bounds, 96, 196, 4));
    }

    @Test
    public void tapOutsideBoundsDoesNothing() {
        android.graphics.RectF bounds = new android.graphics.RectF(100, 200, 180, 280);
        assertFalse(HgramSummarizer.isInsideButton(bounds, 80, 240, 4));
        assertFalse(HgramSummarizer.isInsideButton(bounds, 140, 300, 4));
        assertFalse(HgramSummarizer.isInsideButton(new android.graphics.RectF(), 140, 240, 4));
    }

    @Test
    public void parsePricePerMillionHandlesRawOpenRouterStrings() {
        assertEquals(0.0, HgramSummarizerModel.parsePricePerMillion("0"), 1e-9);
        assertEquals(0.27, HgramSummarizerModel.parsePricePerMillion("0.00000027"), 1e-9);
        assertEquals(1.1, HgramSummarizerModel.parsePricePerMillion("0.0000011"), 1e-9);
        assertNull(HgramSummarizerModel.parsePricePerMillion("-1"));
        assertNull(HgramSummarizerModel.parsePricePerMillion("-0.5"));
        assertNull(HgramSummarizerModel.parsePricePerMillion(null));
        assertNull(HgramSummarizerModel.parsePricePerMillion(" "));
        assertNull(HgramSummarizerModel.parsePricePerMillion("n/a"));
    }

    @Test
    public void formatPriceSwitchesBetweenTwoAndThreeDecimals() {
        assertEquals("0.27 / 1.10", HgramSummarizerModel.formatPrice(0.27, 1.10));
        assertEquals("0.003 / —", HgramSummarizerModel.formatPrice(0.003, null));
        assertEquals("—", HgramSummarizerModel.formatPrice(null, null));
        assertEquals("0.00 / 0.00", HgramSummarizerModel.formatPrice(0.0, 0.0));
        assertEquals("—", HgramSummarizerModel.formatPrice(-1d, -1d));
        assertEquals("—", HgramSummarizerModel.formatPrice(
                HgramSummarizerModel.parsePricePerMillion("-1"),
                HgramSummarizerModel.parsePricePerMillion("-1")));
    }

    @Test
    public void sortPutsFreeFirstThenCheapestPaidWithUnknownPriceLast() {
        List<HgramSummarizerModel> models = new ArrayList<>(Arrays.asList(
                new HgramSummarizerModel("b/paid", "Zeta", 1.5, 2.0, false, true),
                new HgramSummarizerModel("z/free", "Alpha free", 0.0, 0.0, true, true),
                new HgramSummarizerModel("v/variable", "Omega variable",
                        HgramSummarizerModel.parsePricePerMillion("-1"),
                        HgramSummarizerModel.parsePricePerMillion("-1"), false, true),
                new HgramSummarizerModel("m/paid", "", 0.1, 0.2, false, true),
                new HgramSummarizerModel("a/free", "Beta free", 0.0, 0.0, true, true)));
        HgramSummarizerModel.sort(models);
        List<String> ids = new ArrayList<>();
        for (HgramSummarizerModel model : models) ids.add(model.id);
        // Free models first, then paid by price; variable (negative -> unknown) prices go last.
        assertEquals(Arrays.asList("z/free", "a/free", "m/paid", "b/paid", "v/variable"), ids);
        assertNull(models.get(4).promptPricePer1M);
        assertNull(models.get(4).completionPricePer1M);
    }

    @Test
    public void estimateSummariesPerMillionMatchesRussianBaseline() {
        String prompt = HgramSummarizer.DEFAULT_PROMPT;
        assertEquals(3170, HgramSummarizer.estimateSummariesPerMillion(10, prompt, Locale.forLanguageTag("ru")));
        assertEquals(2630, HgramSummarizer.estimateSummariesPerMillion(50, prompt, Locale.forLanguageTag("ru")));
        assertEquals(900, HgramSummarizer.estimateSummariesPerMillion(500, prompt, Locale.forLanguageTag("ru")));
        assertTrue(HgramSummarizer.estimateSummariesPerMillion(10, prompt, Locale.forLanguageTag("ru"))
                > HgramSummarizer.estimateSummariesPerMillion(50, prompt, Locale.forLanguageTag("ru")));
        assertTrue(HgramSummarizer.estimateSummariesPerMillion(50, prompt, Locale.forLanguageTag("ru"))
                > HgramSummarizer.estimateSummariesPerMillion(500, prompt, Locale.forLanguageTag("ru")));
        StringBuilder longer = new StringBuilder(prompt);
        for (int i = 0; i < 4000; i++) longer.append('x');
        assertTrue(HgramSummarizer.estimateSummariesPerMillion(50, longer.toString(), Locale.forLanguageTag("ru"))
                < HgramSummarizer.estimateSummariesPerMillion(50, prompt, Locale.forLanguageTag("ru")));
    }

    @Test
    public void estimateSummariesPerMillionLatinLocaleGivesMore() {
        int ru = HgramSummarizer.estimateSummariesPerMillion(50, HgramSummarizer.DEFAULT_PROMPT, Locale.forLanguageTag("ru"));
        int us = HgramSummarizer.estimateSummariesPerMillion(50, HgramSummarizer.DEFAULT_PROMPT, Locale.US);
        assertEquals(2890, us);
        assertTrue(us > ru);
    }

    @Test
    public void showPaidPersistsAndIsResetByResetSummarizer() {
        assertFalse(HgramConfig.isSummarizerShowPaid());
        HgramConfig.setSummarizerShowPaid(true);
        assertTrue(HgramConfig.isSummarizerShowPaid());
        HgramConfig.resetSummarizer();
        assertFalse(HgramConfig.isSummarizerShowPaid());
    }

    private static String words(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(' ');
            out.append("слово").append(i);
        }
        return out.toString();
    }
}
