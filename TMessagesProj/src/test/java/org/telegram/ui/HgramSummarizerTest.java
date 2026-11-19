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
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageCustomParamsHelper;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

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
@Config(application = Application.class, sdk = 34, shadows = {HgramSummarizerTest.NoJni.class})
public class HgramSummarizerTest {
    @org.robolectric.annotation.Implements(org.telegram.tgnet.ConnectionsManager.class)
    public static class NoJni {
        @org.robolectric.annotation.Implementation
        protected static int native_isTestBackend(int instance) {
            return 0;
        }
    }

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
    public void secretChatNotSummarizable() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 1;
        raw.dialog_id = DialogObject.makeEncryptedDialogId(123);
        raw.message = "secret";
        MessageObject msg = new MessageObject(1, raw, false, false);
        assertTrue(HgramSummarizer.isSecretChatMessage(msg));
        assertFalse(HgramSummarizer.canSummarize(msg, null, true));
        assertFalse(HgramSummarizer.canSummarize(msg, null, false));
    }

    @Test
    public void longTextIsBoundedPerMessage() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 2;
        raw.dialog_id = 777;
        raw.message = "а".repeat(10000) + "\uD83D\uDE00\uD83D\uDE01";
        MessageObject msg = new MessageObject(1, raw, false, false);
        String out = HgramSummarizer.buildRequestText(msg, null);
        assertTrue(out.length() <= 500);
        assertFalse(out.isEmpty());
        assertNoLoneHighSurrogate(out);

        // A surrogate pair straddling the cut must be dropped, not split.
        TLRPC.TL_message boundary = new TLRPC.TL_message();
        boundary.id = 3;
        boundary.dialog_id = 777;
        boundary.message = "а".repeat(498) + "\uD83D\uDE00" + "б".repeat(100);
        String boundaryOut = HgramSummarizer.buildRequestText(
                new MessageObject(1, boundary, false, false), null);
        assertTrue(boundaryOut.length() <= 500);
        assertNoLoneHighSurrogate(boundaryOut);
    }

    @Test
    public void shortTextIsKeptAsIs() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 4;
        raw.dialog_id = 777;
        raw.message = "привет";
        MessageObject msg = new MessageObject(1, raw, false, false);
        assertEquals("привет", HgramSummarizer.buildRequestText(msg, null));
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
    public void systemPromptIsClampedTo2000() {
        HgramConfig.resetSummarizer();
        String longPrompt = "x".repeat(HgramSummarizer.MAX_SYSTEM_PROMPT_CHARS + 500);
        HgramConfig.setSummarizerSystemPrompt(longPrompt);
        assertEquals(HgramSummarizer.MAX_SYSTEM_PROMPT_CHARS, HgramConfig.getSummarizerSystemPrompt().length());
        // Empty prompt falls back to the built-in default instead of staying empty.
        HgramConfig.setSummarizerSystemPrompt(null);
        assertEquals(HgramSummarizer.DEFAULT_PROMPT, HgramConfig.getSummarizerSystemPrompt());
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
    public void requestKeyKeepsAccountAndFullDialogId() {
        HgramSummarizer.RequestKey key = new HgramSummarizer.RequestKey(1, 0x100000001L, 7);
        HgramSummarizer.RequestKey same = new HgramSummarizer.RequestKey(1, 0x100000001L, 7);
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());

        HgramSummarizer.RequestKey otherAccount = new HgramSummarizer.RequestKey(2, 0x100000001L, 7);
        assertNotEquals(key, otherAccount);
        // Dialog ids differing only above the 32-bit boundary must not collide.
        HgramSummarizer.RequestKey otherDialog = new HgramSummarizer.RequestKey(1, 0x200000001L, 7);
        assertNotEquals(key, otherDialog);
    }

    @Test
    public void sourceFingerprintTracksChainComposition() {
        HgramShortMessageMerger.Chain first = chainWith(102, "погода хорошая");
        HgramShortMessageMerger.Chain same = chainWith(102, "погода хорошая");
        HgramShortMessageMerger.Chain changed = chainWith(102, "погода испортилась");

        assertEquals(102, first.anchorId);
        assertEquals(first.anchorId, changed.anchorId);
        assertEquals(first.ids, changed.ids);
        assertEquals(HgramSummarizer.sourceFingerprint(null, first),
                HgramSummarizer.sourceFingerprint(null, same));
        assertNotEquals(HgramSummarizer.sourceFingerprint(null, first),
                HgramSummarizer.sourceFingerprint(null, changed));

        // Read counters are presentation state and must not invalidate a cached summary.
        HgramShortMessageMerger.Chain recounted = HgramShortMessageMerger.withReadCounts(first, 2, 1);
        assertNotSame(first, recounted);
        assertEquals(HgramSummarizer.sourceFingerprint(null, first),
                HgramSummarizer.sourceFingerprint(null, recounted));
    }

    @Test
    public void staleSummaryIsDroppedWhenChainChanges() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 7;
        raw.dialog_id = 777;
        raw.message = "исходный текст сообщения";
        MessageObject msg = new MessageObject(1, raw, false, false);

        HgramShortMessageMerger.Chain oldChain = chainWith(202, "старая погода");
        HgramShortMessageMerger.Chain newChain = chainWith(202, "новая погода испортилась");

        HgramSummarizer.apply(msg, "старый пересказ", oldChain);
        HgramSummarizer.invalidateStaleSummary(msg, newChain);
        assertNull(raw.summaryText);
        assertFalse(raw.summarizedOpen);
        assertFalse(msg.summarized);

        // A summary produced from the current chain survives the same call.
        HgramSummarizer.apply(msg, "новый пересказ", newChain);
        HgramSummarizer.invalidateStaleSummary(msg, newChain);
        assertNotNull(raw.summaryText);
        assertTrue(raw.summarizedOpen);
    }

    @Test
    public void chainSummaryWithoutEntryIsStale() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 41;
        raw.dialog_id = 777;
        raw.message = "a";
        MessageObject msg = new MessageObject(1, raw, false, false);
        msg.messageOwner.summaryText = new TLRPC.TL_textWithEntities();
        msg.messageOwner.summaryText.text = "old";
        msg.messageOwner.summarizedOpen = true;
        msg.summarized = true;

        // A single-message summary may be server-side, so it stays fresh on its own.
        assertTrue(HgramSummarizer.isSummaryFresh(msg, null));

        // The same text under a chain has no process-local fingerprint, so its source is
        // unverifiable and the stale summary must be dropped.
        HgramShortMessageMerger.Chain chain = chainWith(41, "старое сообщение");
        assertFalse(HgramSummarizer.isSummaryFresh(msg, chain));
        assertTrue(HgramSummarizer.invalidateStaleSummary(msg, chain));
        assertNull(msg.messageOwner.summaryText);
        assertFalse(msg.messageOwner.summarizedOpen);
        assertFalse(msg.summarized);
    }

    @Test
    public void chainSummaryIsDroppedOnDissolve() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 303;
        raw.dialog_id = 777;
        raw.message = "свёрнутое сообщение";
        MessageObject msg = new MessageObject(1, raw, false, false);

        HgramSummarizer.apply(msg, "x", chainWith(303, "предыдущее сообщение"));
        HgramSummarizer.onChainDissolved(1, 777, 303, msg);
        assertNull(raw.summaryText);
        assertFalse(raw.summarizedOpen);
        assertFalse(msg.summarized);
    }

    @Test
    public void dissolvedChainRestoresAnchorText() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 81;
        raw.dialog_id = 777;
        raw.message = "исходный текст";
        MessageObject msg = new MessageObject(1, raw, false, false);

        HgramSummarizer.apply(msg, "локальный пересказ", chainWith(81, "..."));
        assertTrue(msg.messageText.toString().contains("локальный пересказ"));

        HgramSummarizer.onChainDissolved(1, 777, 81, msg);
        assertFalse(msg.messageOwner.summarizedOpen);
        assertFalse(msg.summarized);
        assertEquals("исходный текст", msg.messageText.toString());
    }

    @Test
    public void cacheIsScopedToAnchor() {
        TLRPC.TL_message rawA = new TLRPC.TL_message();
        rawA.id = 21;
        rawA.dialog_id = 777;
        rawA.message = "первое сообщение";
        MessageObject msgA = new MessageObject(1, rawA, false, false);

        TLRPC.TL_message rawB = new TLRPC.TL_message();
        rawB.id = 22;
        rawB.dialog_id = 777;
        rawB.message = "второе сообщение";
        MessageObject msgB = new MessageObject(1, rawB, false, false);

        HgramSummarizer.apply(msgA, "пересказ A", chainWith(21, "старое сообщение"));
        HgramSummarizer.invalidateStaleSummary(msgB, chainWith(22, "другой"));
        assertNotNull(rawA.summaryText);
        assertTrue(rawA.summarizedOpen);

        HgramSummarizer.onChainDissolved(1, 777, 21, msgA);
        assertNull(rawA.summaryText);
        assertFalse(rawA.summarizedOpen);
        assertFalse(msgA.summarized);
    }

    @Test
    public void incomingAndOutgoingUseSameBubbleBounds() {
        final float cellWidth = 1080f;
        final float bubbleLeft = 200f;
        final float bubbleRight = 600f;
        final float bubbleTop = 100f;
        final float bubbleBottom = 500f;
        final int cellHeight = 800;
        final int size = AndroidUtilities.dp(32);
        final int gap = AndroidUtilities.dp(8);
        final float minTop = 2f;
        final android.graphics.RectF bubble =
                new android.graphics.RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom);

        android.graphics.RectF outgoing = new android.graphics.RectF();
        final int outgoingTier = HgramSummarizer.summarizeBoundsInBubble(outgoing, cellWidth,
                bubbleLeft, bubbleRight, bubbleTop, bubbleBottom, 0f, cellHeight,
                size, gap, minTop, true, 0f);
        assertFalse("tier " + outgoingTier + " rect " + outgoing,
                android.graphics.RectF.intersects(outgoing, bubble));
        assertTrue(outgoing.left >= 2f - 0.01f);
        assertTrue(outgoing.right <= cellWidth - 2f + 0.01f);
        if (outgoingTier == 1) {
            assertTrue("tier 1 rect " + outgoing, outgoing.right < bubbleLeft);
        }

        android.graphics.RectF incoming = new android.graphics.RectF();
        final int incomingTier = HgramSummarizer.summarizeBoundsInBubble(incoming, cellWidth,
                bubbleLeft, bubbleRight, bubbleTop, bubbleBottom, 0f, cellHeight,
                size, gap, minTop, false, 0f);
        assertFalse("tier " + incomingTier + " rect " + incoming,
                android.graphics.RectF.intersects(incoming, bubble));
        assertTrue(incoming.left >= 2f - 0.01f);
        assertTrue(incoming.right <= cellWidth - 2f + 0.01f);
        if (incomingTier == 1) {
            assertTrue("tier 1 rect " + incoming, incoming.left >= bubbleRight);
        }
    }

    @Test
    public void tier3NeverShrinksBelow24dp() {
        final float cellWidth = 1080f;
        final float bubbleLeft = AndroidUtilities.dp(27);
        final float bubbleRight = 600f;
        final float bubbleTop = 100f;
        final float bubbleBottom = 500f;
        final float minSize = AndroidUtilities.dp(24);
        final android.graphics.RectF bubble =
                new android.graphics.RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom);

        final android.graphics.RectF bounds = new android.graphics.RectF();
        final int tier = HgramSummarizer.summarizeBoundsInBubble(bounds, cellWidth,
                bubbleLeft, bubbleRight, bubbleTop, bubbleBottom, 0f, 800,
                AndroidUtilities.dp(32), AndroidUtilities.dp(8), 2f, true, 0f);
        assertTrue("tier " + tier + " rect " + bounds,
                tier != 3 || bounds.width() >= minSize - 0.01f);
        assertFalse("tier " + tier + " rect " + bounds,
                android.graphics.RectF.intersects(bounds, bubble));

        // No room on either side of the bubble: only tier 3 can run, and it must still be >= 24dp.
        final float edge = AndroidUtilities.dp(2);
        final float tightLeft = AndroidUtilities.dp(30);
        final float tightRight = cellWidth - edge;
        final android.graphics.RectF tightBubble =
                new android.graphics.RectF(tightLeft, bubbleTop, tightRight, bubbleBottom);
        final android.graphics.RectF tightBounds = new android.graphics.RectF();
        final int tightTier = HgramSummarizer.summarizeBoundsInBubble(tightBounds, cellWidth,
                tightLeft, tightRight, bubbleTop, bubbleBottom, 0f, 800,
                AndroidUtilities.dp(32), AndroidUtilities.dp(8), 2f, true, 0f);
        assertEquals("rect " + tightBounds, 3, tightTier);
        assertTrue("rect " + tightBounds, tightBounds.width() >= minSize - 0.01f);
        assertTrue("rect " + tightBounds, tightBounds.width() < AndroidUtilities.dp(32));
        assertFalse("rect " + tightBounds,
                android.graphics.RectF.intersects(tightBounds, tightBubble));
    }

    @Test
    public void summarizeBoundsWithNoOverlapNeverCoversBubble() {
        final float cellWidth = 1080f;
        final float bubbleWidth = 1000f;
        final int size = AndroidUtilities.dp(32);
        final int gap = AndroidUtilities.dp(8);

        android.graphics.RectF out = new android.graphics.RectF();
        final float top = AndroidUtilities.dp(300);
        final float bottom = AndroidUtilities.dp(900);
        final float left = HgramSummarizer.bubbleLeft(true, cellWidth, bubbleWidth, false);
        android.graphics.RectF bubble = new android.graphics.RectF(left, top, left + bubbleWidth, bottom);
        final int tier = HgramSummarizer.summarizeBoundsAdaptive(out, true, cellWidth, bubbleWidth, false,
                top, bottom, 0, AndroidUtilities.dp(1200), size, gap, 0f, 0f);
        assertFalse("tier " + tier + " rect " + out,
                android.graphics.RectF.intersects(out, bubble));

        // Incoming with an avatar has no room beside the bubble: tier 4 sits above/below it,
        // so an X overlap is fine as long as the button stays outside the bubble on Y.
        final float tightTop = 0f;
        final float tightBottom = AndroidUtilities.dp(200);
        final float tightLeft = HgramSummarizer.bubbleLeft(false, cellWidth, bubbleWidth, true);
        android.graphics.RectF tightBubble =
                new android.graphics.RectF(tightLeft, tightTop, tightLeft + bubbleWidth, tightBottom);
        final int tightTier = HgramSummarizer.summarizeBoundsAdaptive(out, false, cellWidth, bubbleWidth, true,
                tightTop, tightBottom, 0, AndroidUtilities.dp(600), size, gap, 0f, 0f);
        assertFalse("tier " + tightTier + " rect " + out,
                android.graphics.RectF.intersects(out, tightBubble));
        if (tightTier == 4) {
            assertTrue(out.bottom <= tightBubble.top + 0.01f || out.top >= tightBubble.bottom - 0.01f);
        }
    }

    @Test
    public void publicDialogIsNotHgramEligible() {
        HgramConfig.setSummarizerEnabled(true);
        HgramConfig.setSummarizerMinWords(50);

        TLRPC.TL_message publicRaw = new TLRPC.TL_message();
        publicRaw.id = 9;
        publicRaw.dialog_id = -1001234567890L;
        publicRaw.message = words(60);
        MessageObject publicMsg = new MessageObject(1, publicRaw, false, false);
        assertFalse(HgramSummarizer.canSummarize(publicMsg, null, false));

        TLRPC.TL_message privateRaw = new TLRPC.TL_message();
        privateRaw.id = 10;
        privateRaw.dialog_id = 777;
        privateRaw.message = words(60);
        MessageObject privateMsg = new MessageObject(1, privateRaw, false, false);
        assertTrue(HgramSummarizer.canSummarize(privateMsg, null, true));
    }

    @Test
    public void publicDialogToggleIsRejected() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 51;
        raw.dialog_id = -1001234567890L;
        raw.message = words(60);
        MessageObject msg = new MessageObject(1, raw, false, false);

        HgramConfig.setSummarizerKey("sk-or-test");
        HgramConfig.setSummarizerModel("test/model");

        final boolean[] updated = {false};
        assertFalse(HgramSummarizer.toggle(msg, null, () -> updated[0] = true));
        assertFalse(updated[0]);
    }

    @Test
    public void showPaidPersistsAndIsResetByResetSummarizer() {
        assertFalse(HgramConfig.isSummarizerShowPaid());
        HgramConfig.setSummarizerShowPaid(true);
        assertTrue(HgramConfig.isSummarizerShowPaid());
        HgramConfig.resetSummarizer();
        assertFalse(HgramConfig.isSummarizerShowPaid());
    }

    @Test
    public void summaryCacheRemainsBoundedAfterOpeningManyDialogs() {
        for (int i = 0; i < 160; i++) {
            TLRPC.TL_message raw = new TLRPC.TL_message();
            raw.id = 1000 + i;
            raw.dialog_id = 777;
            raw.message = "text" + i;
            MessageObject msg = new MessageObject(1, raw, false, false);
            HgramSummarizer.apply(msg, "summary" + i, null);
        }
        assertTrue(HgramSummarizer.cachedSummaryCount() > 0);
        assertTrue(HgramSummarizer.cachedSummaryCount() <= 128);
    }

    @Test
    public void openOutgoingSummaryClosesAfterThresholdIncreaseWithoutNetwork() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 61;
        raw.dialog_id = 777;
        raw.message = words(60);
        MessageObject msg = new MessageObject(1, raw, false, false);
        HgramShortMessageMerger.Chain chain = chainWith(61, "предыдущее сообщение");

        HgramSummarizer.apply(msg, "local", chain);
        HgramConfig.setSummarizerMinWords(500);
        // Closing must not depend on credentials: an open local summary closes regardless.
        HgramConfig.setSummarizerKey("");
        HgramConfig.setSummarizerModel("");
        assertTrue(HgramSummarizer.hasLocalSummary(msg, chain));
        assertTrue(HgramSummarizer.closeLocal(msg, chain));
        assertFalse(msg.messageOwner.summarizedOpen);
        assertFalse(HgramSummarizer.hasLocalSummary(msg, chain));
    }

    @Test
    public void upstreamSummaryIsNotClosedByLocalRoute() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 71;
        raw.dialog_id = 777;
        raw.message = "исходный";
        MessageObject msg = new MessageObject(1, raw, false, false);

        // Visible summary that this process never applied: there is no marker for it.
        msg.messageOwner.summaryText = new TLRPC.TL_textWithEntities();
        msg.messageOwner.summaryText.text = "старый пересказ";
        msg.messageOwner.summarizedOpen = true;
        msg.summarized = true;

        assertFalse(HgramSummarizer.hasLocalSummary(msg, null));
        assertFalse(HgramSummarizer.closeLocal(msg, null));
        // The upstream summary stays open: the local route must not close someone else's summary.
        assertTrue(msg.messageOwner.summarizedOpen);
        assertEquals("исходный", raw.message);
    }

    @Test
    public void localSummaryIsNotPersisted() {
        TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = 72;
        raw.dialog_id = 777;
        raw.message = "исходный";
        MessageObject msg = new MessageObject(1, raw, false, false);
        assertTrue(MessageCustomParamsHelper.isEmpty(raw));

        HgramSummarizer.apply(msg, "x", chainWith(72, "..."));

        // A locally produced summary lives only in this process and must not be persisted.
        assertTrue(MessageCustomParamsHelper.isEmpty(raw));

        // Without the local marker the same fields are persisted content.
        TLRPC.TL_message raw2 = new TLRPC.TL_message();
        raw2.id = 73;
        raw2.dialog_id = 777;
        raw2.message = "исходный";
        raw2.summaryText = new TLRPC.TL_textWithEntities();
        raw2.summaryText.text = "upstream";
        raw2.summarizedOpen = true;
        assertFalse(MessageCustomParamsHelper.isEmpty(raw2));
    }

    @Test
    public void clipTouchRectKeepsFreeStrip() {
        android.graphics.RectF hit = new android.graphics.RectF(0, 0, 100, 40);
        android.graphics.RectF blocker = new android.graphics.RectF(90, -10, 110, 50);
        HgramSummarizer.clipTouchRect(hit, blocker);
        assertEquals(90f, hit.right, 0.01f);
        assertFalse(hit.isEmpty());

        // Fully covered target has no free strip left and collapses to an empty rect.
        android.graphics.RectF inside = new android.graphics.RectF(10, 10, 50, 30);
        HgramSummarizer.clipTouchRect(inside, new android.graphics.RectF(0, 0, 100, 100));
        assertTrue(inside.isEmpty());
    }

    /** Chain of two messages whose older element carries {@code olderText}; the anchor stays fixed. */
    private static HgramShortMessageMerger.Chain chainWith(int anchorId, String olderText) {
        Map<Integer, HgramShortMessageMerger.Chain> chains = HgramShortMessageMerger.build(Arrays.asList(
                new HgramShortMessageMerger.Entry(anchorId - 1, 1000, 1, 7, olderText, true),
                new HgramShortMessageMerger.Entry(anchorId, 1100, 1, 7, "свежее сообщение", true)));
        return chains.get(anchorId);
    }

    private static void assertNoLoneHighSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isHighSurrogate(text.charAt(i))) {
                assertTrue(i + 1 < text.length());
                assertTrue(Character.isLowSurrogate(text.charAt(i + 1)));
                i++;
            }
        }
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
