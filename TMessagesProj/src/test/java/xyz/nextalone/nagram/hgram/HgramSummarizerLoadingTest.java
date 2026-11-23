package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import tw.nekomimi.nekogram.NekoConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Loading state of the ✨ button: the request seam keeps the network out, while toggle() itself
 * remains the only place that decides when a request starts and when the UI is notified.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34, shadows = {HgramSummarizerLoadingTest.NoJni.class})
public class HgramSummarizerLoadingTest {

    @org.robolectric.annotation.Implements(org.telegram.tgnet.ConnectionsManager.class)
    public static class NoJni {
        @org.robolectric.annotation.Implementation
        protected static int native_isTestBackend(int instance) {
            return 0;
        }
    }

    private final List<Runnable> queuedRequests = new ArrayList<>();
    private String resultText;
    private Throwable failure;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        // Robolectric exposes only two root directories; pin the media cache before Theme loads.
        NekoConfig.cachePath.setConfigString(
                ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath());
        HgramConfig.resetSummarizer();
        HgramConfig.setSummarizerEnabled(true);
        HgramConfig.setSummarizerMinWords(50);
        HgramConfig.setSummarizerKey("sk-or-test");
        HgramConfig.setSummarizerModel("test/model");
        queuedRequests.clear();
        resultText = null;
        failure = null;
        HgramSummarizer.setRequestLauncherForTests(queuedRequests::add);
        HgramSummarizer.setRequestCompletionForTests((key, model, systemPrompt, userPrompt) -> {
            if (failure != null) {
                throw failure;
            }
            return resultText;
        });
    }

    @After
    public void tearDown() {
        HgramSummarizer.resetRequestHooksForTests();
        ShadowToast.reset();
        idleMainLooper();
    }

    @Test
    public void tapStartsLoadingAndNotifiesUiBeforeNetwork() {
        final MessageObject message = message(101, words(60));
        final int[] updates = {0};

        assertTrue(HgramSummarizer.toggle(message, null, () -> updates[0]++));

        // The button state is inFlight, and the UI already knows about it while the task is queued.
        assertTrue(HgramSummarizer.isInFlight(message));
        assertEquals(1, updates[0]);
        assertEquals(1, queuedRequests.size());
        assertFalse(message.messageOwner.summarizedOpen);
        assertTrue(HgramSummaryButtonState.isLoading(true, HgramSummarizer.isInFlight(message), false));
    }

    @Test
    public void repeatTapWhileLoadingKeepsOneRequestAndRedraws() {
        final MessageObject message = message(102, words(60));
        final int[] updates = {0};

        assertTrue(HgramSummarizer.toggle(message, null, () -> updates[0]++));
        final int updatesAfterStart = updates[0];
        assertTrue(HgramSummarizer.toggle(message, null, () -> updates[0]++));

        assertEquals(1, queuedRequests.size());
        assertTrue(HgramSummarizer.isInFlight(message));
        assertEquals(updatesAfterStart + 1, updates[0]);
        assertFalse(message.messageOwner.summarizedOpen);
    }

    @Test
    public void successEndsLoadingWithOpenSummary() {
        final MessageObject message = message(103, words(60));
        final int[] updates = {0};
        resultText = "краткий пересказ";

        assertTrue(HgramSummarizer.toggle(message, null, () -> updates[0]++));
        runQueuedRequest();

        assertFalse(HgramSummarizer.isInFlight(message));
        assertTrue(message.messageOwner.summarizedOpen);
        assertNotNull(message.messageOwner.summaryText);
        assertEquals("краткий пересказ", message.messageOwner.summaryText.text);
        assertTrue(updates[0] >= 2);
        assertFalse(HgramSummaryButtonState.isLoading(true, HgramSummarizer.isInFlight(message), true));
    }

    @Test
    public void errorAndEmptyResultReturnToIdleWithTheOldToast() {
        final MessageObject failing = message(104, words(60));
        final int[] failingUpdates = {0};
        failure = new RuntimeException("network down");

        assertTrue(HgramSummarizer.toggle(failing, null, () -> failingUpdates[0]++));
        runQueuedRequest();

        assertFalse(HgramSummarizer.isInFlight(failing));
        assertFalse(failing.messageOwner.summarizedOpen);
        assertNull(failing.messageOwner.summaryText);
        assertNotNull(ShadowToast.getTextOfLatestToast());
        assertEquals(2, failingUpdates[0]);

        ShadowToast.reset();
        final MessageObject empty = message(105, words(60));
        final int[] emptyUpdates = {0};
        failure = null;
        resultText = "";

        assertTrue(HgramSummarizer.toggle(empty, null, () -> emptyUpdates[0]++));
        runQueuedRequest();

        assertFalse(HgramSummarizer.isInFlight(empty));
        assertFalse(empty.messageOwner.summarizedOpen);
        assertNull(empty.messageOwner.summaryText);
        assertNotNull(ShadowToast.getTextOfLatestToast());
        assertEquals(2, emptyUpdates[0]);
    }

    @Test
    public void staleRequestReturnsToIdleWithoutApplying() {
        final MessageObject message = message(106, words(60));
        final int[] updates = {0};

        assertTrue(HgramSummarizer.toggle(message, null, () -> updates[0]++));
        assertTrue(HgramSummarizer.isInFlight(message));
        // The source changed while the request was in flight: applyIfFresh must refuse the result.
        message.messageOwner.message = words(70);

        resultText = "устаревший пересказ";
        runQueuedRequest();

        assertFalse(HgramSummarizer.isInFlight(message));
        assertFalse(message.messageOwner.summarizedOpen);
        assertNull(message.messageOwner.summaryText);
        assertFalse(HgramSummaryButtonState.isLoading(true, HgramSummarizer.isInFlight(message), false));
        assertEquals(2, updates[0]);
    }

    @Test
    public void closingOpenSummaryDoesNotStartRequest() {
        final MessageObject message = message(107, words(60));
        HgramSummarizer.apply(message, "локальный пересказ", null);
        assertTrue(message.messageOwner.summarizedOpen);
        final int[] updates = {0};

        assertTrue(HgramSummarizer.toggle(message, null, () -> updates[0]++));

        assertFalse(message.messageOwner.summarizedOpen);
        assertTrue(queuedRequests.isEmpty());
        assertEquals(1, updates[0]);
    }

    @Test
    public void visibilityPredicatesStayUnchanged() {
        final MessageObject below = message(108, words(49));
        final MessageObject atLimit = message(109, words(50));
        assertFalse(HgramSummarizer.canSummarize(below, null, true));
        assertTrue(HgramSummarizer.canSummarize(atLimit, null, true));

        // Only private dialogs; secret chats never pass.
        assertFalse(HgramSummarizer.canSummarize(atLimit, null, false));
        final MessageObject secret = message(110, words(60));
        secret.messageOwner.dialog_id = DialogObject.makeEncryptedDialogId(42);
        assertTrue(HgramSummarizer.isSecretChatMessage(secret));
        assertFalse(HgramSummarizer.canSummarize(secret, null, true));

        // SUMM-NOCHAIN-1: the chain path stays off for the UI button.
        assertNotNull(chainWith(109, "старое сообщение"));
        assertFalse(HgramSummarizer.canSummarize(atLimit, chainWith(109, "старое сообщение"), true));

        // An open summary keeps its close button even when the threshold no longer passes.
        HgramConfig.setSummarizerMinWords(500);
        HgramSummarizer.apply(atLimit, "уже есть", null);
        assertFalse(HgramSummarizer.canSummarize(atLimit, null, true));
        assertTrue(atLimit.messageOwner.summarizedOpen && atLimit.messageOwner.summaryText != null);

        // A threshold-hidden button cannot become visible just because a request is running.
        assertFalse(HgramSummaryButtonState.isLoading(false, true, false));
        assertTrue(HgramSummaryButtonState.isLoading(true, true, false));
        assertFalse(HgramSummaryButtonState.isLoading(true, true, true));
        assertFalse(HgramSummaryButtonState.isLoading(true, false, false));
    }

    private void runQueuedRequest() {
        assertFalse(queuedRequests.isEmpty());
        // Arm the main handler only for the completion callback: Theme/MessageObject init must not
        // execute unrelated background Telegram requests in this Robolectric sandbox.
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        try {
            queuedRequests.remove(0).run();
            idleMainLooper();
        } finally {
            ApplicationLoader.applicationHandler = null;
        }
    }

    private static void idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks();
    }

    private static MessageObject message(int id, String text) {
        final TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = id;
        raw.dialog_id = 777;
        raw.message = text;
        return new MessageObject(1, raw, false, false);
    }

    private static HgramShortMessageMerger.Chain chainWith(int anchorId, String olderText) {
        final Map<Integer, HgramShortMessageMerger.Chain> chains = HgramShortMessageMerger.build(Arrays.asList(
                new HgramShortMessageMerger.Entry(anchorId - 1, 1000, 1, 7, olderText, true),
                new HgramShortMessageMerger.Entry(anchorId, 1100, 1, 7, "свежее сообщение", true)));
        return chains.get(anchorId);
    }

    private static String words(int count) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(' ');
            out.append("слово").append(i);
        }
        return out.toString();
    }
}
