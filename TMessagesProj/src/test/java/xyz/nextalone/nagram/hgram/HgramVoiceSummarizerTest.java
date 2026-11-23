package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Voice/round-video summary path: the request seams keep the network out, and the caption is
 * served from the process-local state of {@link HgramVoiceSummarizer} only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34,
        shadows = {HgramVoiceSummarizerTest.NoJni.class,
                HgramVoiceSummarizerTest.NoHeavyMessagesController.class})
public class HgramVoiceSummarizerTest {

    @org.robolectric.annotation.Implements(org.telegram.tgnet.ConnectionsManager.class)
    public static class NoJni {
        @org.robolectric.annotation.Implementation
        protected static int native_isTestBackend(int instance) {
            return 0;
        }
    }

    /**
     * The real controller constructor opens storage, location services and shared preferences.
     * The peer-protection path only reads {@code getUserFull}, so the constructor is skipped and
     * the full user is supplied directly; {@code isUserNoForwards} itself stays real.
     */
    @org.robolectric.annotation.Implements(
            value = org.telegram.messenger.MessagesController.class, isInAndroidSdk = false)
    public static class NoHeavyMessagesController {
        static TLRPC.UserFull userFull;

        @org.robolectric.annotation.Implementation
        protected void __constructor__(int account) {
        }

        @org.robolectric.annotation.Implementation
        protected TLRPC.UserFull getUserFull(long uid) {
            return userFull;
        }

        @org.robolectric.annotation.Implementation
        protected static android.content.SharedPreferences getGlobalMainSettings() {
            return ApplicationLoader.applicationContext.getSharedPreferences(
                    "mainconfig", android.content.Context.MODE_PRIVATE);
        }
    }

    private final List<Runnable> queuedRequests = new ArrayList<>();
    private String resultText;
    private String capturedKey;
    private String capturedModel;
    private String capturedSystemPrompt;
    private String capturedPrompt;
    private int completionCalls;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        // Robolectric exposes only two root directories; pin the media cache before Theme loads.
        NekoConfig.cachePath.setConfigString(
                ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath());
        // Initialize AndroidUtilities before FileLog/Theme: its static block logs, and a nested
        // FileLog.init from Theme would otherwise abort with "double init call".
        AndroidUtilities.getLogsDir();
        Theme.createCommonMessageResources();
        SharedConfig.appLocked = false;
        UserConfig.selectedAccount = 0;
        HgramAntiPeek.setActive(false, true);
        HgramSecretSpace.resetForTests();
        HgramSecretSpace.setActive(false);
        HgramConfig.resetSummarizer();
        HgramConfig.setSummarizerEnabled(true);
        HgramConfig.setSummarizerMinWords(10);
        HgramConfig.setSummarizerKey("sk-or-test");
        HgramConfig.setSummarizerModel("test/model");
        NaConfig.INSTANCE.getForceCopy().setConfigBool(false);
        queuedRequests.clear();
        resultText = null;
        capturedKey = null;
        capturedModel = null;
        capturedSystemPrompt = null;
        capturedPrompt = null;
        completionCalls = 0;
        NoHeavyMessagesController.userFull = userFull(false, false);
        // A cached controller from another test would bypass the shadowed constructor.
        resetMessagesControllerInstances();
        HgramVoiceSummarizer.resetForTests();
        HgramVoiceSummarizer.setVoiceSummaryEnabledForTests(true);
        HgramVoiceSummarizer.setRequestLauncherForTests(queuedRequests::add);
        HgramVoiceSummarizer.setRequestCompletionForTests((key, model, systemPrompt, userPrompt, permit) -> {
            HgramVoiceSummarizer.newHandoff(permit).accept();
            completionCalls++;
            capturedKey = key;
            capturedModel = model;
            capturedSystemPrompt = systemPrompt;
            capturedPrompt = userPrompt;
            return resultText;
        });
    }

    @After
    public void tearDown() {
        HgramVoiceSummarizer.resetForTests();
        HgramAntiPeek.setActive(false, true);
        HgramSecretSpace.resetForTests();
        HgramSecretSpace.setActive(false);
        HgramConfig.resetSummarizer();
        ShadowToast.reset();
        idleMainLooper();
        resetMessagesControllerInstances();
    }

    @Test
    public void emptyOrNonFinalTranscriptIsNotEligible() {
        assertFalse(HgramVoiceSummarizer.canSummarize(null));

        assertFalse(HgramVoiceSummarizer.canSummarize(voiceMessage(201, null)));
        assertFalse(HgramVoiceSummarizer.canSummarize(voiceMessage(202, "")));
        final MessageObject notFinal = voiceMessage(203, words(60));
        notFinal.messageOwner.voiceTranscriptionFinal = false;
        assertFalse(HgramVoiceSummarizer.canSummarize(notFinal));

        // A text message with a stray final transcript is not a voice path.
        final MessageObject text = voiceMessage(204, words(60));
        text.messageOwner.media = null;
        assertFalse(HgramVoiceSummarizer.canSummarize(text));
    }

    @Test
    public void wordThresholdUsesSummarizerMinWords() {
        HgramConfig.setSummarizerMinWords(20);
        assertFalse(HgramVoiceSummarizer.canSummarize(voiceMessage(205, words(19))));
        assertTrue(HgramVoiceSummarizer.canSummarize(voiceMessage(206, words(20))));
    }

    @Test
    public void requestIsTruncatedTo8000Chars() {
        HgramConfig.setSummarizerMinWords(10);
        final String raw = longTranscript(9000);
        final MessageObject message = voiceMessage(207, raw);
        assertTrue(HgramVoiceSummarizer.canSummarize(message));
        resultText = "итог";

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertTrue(HgramVoiceSummarizer.isInFlight(message));
        runQueuedRequest();

        assertEquals("sk-or-test", capturedKey);
        assertEquals("test/model", capturedModel);
        assertEquals(8000, capturedPrompt.length());
        assertEquals(raw.substring(0, 8000), capturedPrompt);
        assertTrue(capturedSystemPrompt.length() <= HgramSummarizer.MAX_SYSTEM_PROMPT_CHARS);
        assertTrue(HgramVoiceSummarizer.isShowing(message));
    }

    @Test
    public void transcriptChangeInvalidatesResult() {
        final MessageObject message = voiceMessage(208, words(60));
        resultText = "устаревший итог";

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertTrue(HgramVoiceSummarizer.isInFlight(message));

        final String changed = words(61);
        message.messageOwner.voiceTranscription = changed;
        runQueuedRequest();

        assertFalse(HgramVoiceSummarizer.isInFlight(message));
        assertFalse(HgramVoiceSummarizer.isShowing(message));
        assertFalse(HgramVoiceSummarizer.hasCaption(message));
        assertEquals(0, HgramVoiceSummarizer.cachedSummaryCount());
        assertEquals(changed, HgramVoiceSummarizer.getCaption(message));

        // The dropped result must not leak into a later request either.
        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertEquals(1, queuedRequests.size());
    }

    @Test
    public void secretAndAntiPeekAreNotEligible() {
        final MessageObject secret = voiceMessage(209, words(60));
        secret.messageOwner.dialog_id = DialogObject.makeEncryptedDialogId(42);
        assertTrue(HgramSummarizer.isSecretChatMessage(secret));
        assertFalse(HgramVoiceSummarizer.canSummarize(secret));

        final MessageObject plain = voiceMessage(210, words(60));
        assertTrue(HgramVoiceSummarizer.canSummarize(plain));

        HgramAntiPeek.setActive(true, true);
        assertFalse(HgramVoiceSummarizer.canSummarize(plain));
        HgramAntiPeek.setActive(false, true);
        assertTrue(HgramVoiceSummarizer.canSummarize(plain));

        HgramSecretSpace.setActive(true);
        assertFalse(HgramVoiceSummarizer.canSummarize(plain));
        HgramSecretSpace.setActive(false);
        assertTrue(HgramVoiceSummarizer.canSummarize(plain));
    }

    @Test
    public void repeatTapWhileInFlightDoesNotLaunchSecondRequest() {
        final MessageObject message = voiceMessage(211, words(60));
        final int[] updates = {0};
        resultText = "итог";

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> updates[0]++));
        assertEquals(1, queuedRequests.size());
        assertTrue(HgramVoiceSummarizer.isInFlight(message));
        assertEquals(1, updates[0]);

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> updates[0]++));

        assertEquals(1, queuedRequests.size());
        assertTrue(HgramVoiceSummarizer.isInFlight(message));
        assertEquals(2, updates[0]);
        assertFalse(HgramVoiceSummarizer.isShowing(message));
    }

    @Test
    public void roundVideoUsesSameCaptionPath() {
        final MessageObject message = roundVideoMessage(212, words(60));
        assertEquals(MessageObject.TYPE_ROUND_VIDEO, message.type);
        assertTrue(HgramVoiceSummarizer.canSummarize(message));
        resultText = "итог кружка";

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        runQueuedRequest();

        assertTrue(HgramVoiceSummarizer.isShowing(message));
        assertTrue(HgramVoiceSummarizer.hasCaption(message));
        assertEquals("итог кружка", HgramVoiceSummarizer.getCaption(message));
        // The summary stays out of the persisted text fields of the message.
        assertNull(message.messageOwner.summaryText);
    }

    @Test
    public void doesNotTouchPersistedSummaryOrTranscriptionFlags() {
        final MessageObject message = voiceMessage(213, words(60));
        message.messageOwner.voiceTranscriptionOpen = true;
        resultText = "итог";

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        runQueuedRequest();
        assertTrue(HgramVoiceSummarizer.isShowing(message));

        assertNull(message.messageOwner.summaryText);
        assertFalse(message.messageOwner.summarizedOpen);
        assertFalse(message.summarized);
        assertTrue(message.messageOwner.voiceTranscriptionOpen);

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertFalse(HgramVoiceSummarizer.isShowing(message));

        assertNull(message.messageOwner.summaryText);
        assertFalse(message.messageOwner.summarizedOpen);
        assertFalse(message.summarized);
        assertTrue(message.messageOwner.voiceTranscriptionOpen);
    }

    @Test
    public void closeReturnsTranscript() {
        final MessageObject message = voiceMessage(214, words(60));
        resultText = "итог";

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        runQueuedRequest();
        assertTrue(HgramVoiceSummarizer.isShowing(message));
        assertEquals("итог", HgramVoiceSummarizer.getCaption(message));

        // Closing is local: no second request, the caption falls back to the transcript.
        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertFalse(HgramVoiceSummarizer.isShowing(message));
        // The transcript must stay visible even though the ordinary open flag is off.
        assertTrue(HgramVoiceSummarizer.hasCaption(message));
        assertEquals(message.getVoiceTranscription().toString(),
                HgramVoiceSummarizer.getCaption(message));
        assertTrue(queuedRequests.isEmpty());

        // Reopening uses the validated cache and still does not touch the network.
        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertTrue(HgramVoiceSummarizer.isShowing(message));
        assertEquals("итог", HgramVoiceSummarizer.getCaption(message));
        assertTrue(queuedRequests.isEmpty());
    }

    @Test
    public void peerNoForwardsBlocksVoiceRequest() {
        final MessageObject message = voiceMessage(215, words(60));
        assertTrue(HgramVoiceSummarizer.canSummarize(message));

        // Either UserFull flag alone must block the private chat; no message-level noforwards.
        NoHeavyMessagesController.userFull = userFull(true, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(true, false));
        assertTrue(HgramVoiceSummarizer.isPeerProtected(message));
        assertFalse(HgramVoiceSummarizer.canSummarize(message));
        assertFalse(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertTrue(queuedRequests.isEmpty());
        assertEquals(0, completionCalls);

        NoHeavyMessagesController.userFull = userFull(false, true);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, true));
        assertTrue(HgramVoiceSummarizer.isPeerProtected(message));
        assertFalse(HgramVoiceSummarizer.canSummarize(message));
        assertFalse(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertTrue(queuedRequests.isEmpty());
        assertEquals(0, completionCalls);

        // Neither flag: an ordinary private chat stays eligible.
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        assertFalse(HgramVoiceSummarizer.isPeerProtected(message));
        assertTrue(HgramVoiceSummarizer.canSummarize(message));
    }

    @Test
    public void unknownPeerProtectionNeverStartsCompletion() {
        NoHeavyMessagesController.userFull = null;
        final MessageObject message = voiceMessage(218, words(60));

        assertEquals(HgramVoiceSummarizer.PeerProtection.UNKNOWN,
                HgramVoiceSummarizer.peerProtection(message));
        assertFalse(HgramVoiceSummarizer.canSummarize(message));
        assertFalse(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertTrue(queuedRequests.isEmpty());
        assertEquals(0, completionCalls);
        assertEquals(0, ShadowToast.shownToastCount());

        // Only an explicitly unrestricted profile may start the request.
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        assertEquals(HgramVoiceSummarizer.PeerProtection.ALLOWED,
                HgramVoiceSummarizer.peerProtection(message));
        assertTrue(HgramVoiceSummarizer.canSummarize(message));
        resultText = "итог";
        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertEquals(1, queuedRequests.size());
        runQueuedRequest();
        assertEquals(1, completionCalls);
    }

    @Test
    public void peerProtectionEnabledBeforeWorkerSkipsCompletion() {
        assertLatePeerProtectionBlocks(219, true, false);
        assertLatePeerProtectionBlocks(220, false, true);
    }

    private void assertLatePeerProtectionBlocks(int id, boolean peerNoForwards, boolean myNoForwards) {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        final MessageObject message = voiceMessage(id, words(60));
        resultText = "итог";

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertEquals(1, queuedRequests.size());
        assertEquals(0, completionCalls);

        // The profile is published between the tap and the worker; each flag alone must block.
        NoHeavyMessagesController.userFull = userFull(peerNoForwards, myNoForwards);
        HgramVoiceSummarizer.updatePeerProtection(1, 777,
                userFull(peerNoForwards, myNoForwards));
        runQueuedRequest();

        assertEquals(0, completionCalls);
        assertNull(capturedPrompt);
        assertEquals(0, ShadowToast.shownToastCount());
        assertFalse(HgramVoiceSummarizer.isInFlight(message));
        assertFalse(HgramVoiceSummarizer.isShowing(message));
        assertTrue(queuedRequests.isEmpty());
    }

    @Test
    public void blockedPublishedAfterFinalAssertBeforeHttpDoesNotSend() {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        final MessageObject message = voiceMessage(230, words(60));
        final int generation = HgramVoiceSummarizer.accountGeneration(1);

        // The early worker check sees ALLOWED; then the prepared request reaches the real bridge.
        final boolean[] sent = {false};
        HgramVoiceSummarizer.newHandoff(
                HgramVoiceSummarizer.newVoicePermit(message, generation)).accept();
        sent[0] = true;
        assertTrue(sent[0]);

        // The protection is published after the early assert but before the transport handoff.
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(true, false));
        sent[0] = false;
        try {
            HgramVoiceSummarizer.newHandoff(
                    HgramVoiceSummarizer.newVoicePermit(message, generation)).accept();
            sent[0] = true;
            org.junit.Assert.fail("transport must be blocked before the HTTP handoff");
        } catch (HgramVoiceSummarizer.TransportBlockedException expected) {
            // Silent refusal: no body, no toast, no log.
        }
        assertFalse(sent[0]);
    }

    @Test
    public void blockedPublishedAfterHandoffAcceptedBeforeExecuteDoesNotSend() throws Exception {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        final MessageObject message = voiceMessage(239, words(60));
        resultText = "не должно отправиться";
        final CountDownLatch atTransport = new CountDownLatch(1);
        final CountDownLatch releaseTransport = new CountDownLatch(1);
        final AtomicInteger sends = new AtomicInteger();
        HgramVoiceSummarizer.setRequestCompletionForTests((key, model, systemPrompt, userPrompt, permit) -> {
            // Fake transport: hold after the worker's early check, before the HTTP handoff.
            atTransport.countDown();
            if (!releaseTransport.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("transport latch timeout");
            }
            HgramVoiceSummarizer.newHandoff(permit).accept();
            sends.incrementAndGet();
            return resultText;
        });

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        try {
            final Thread worker = new Thread(queuedRequests.remove(0), "voice-transport-test");
            worker.start();
            assertTrue(atTransport.await(5, TimeUnit.SECONDS));

            // Protection is published in the exact window between early check and accept().
            HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(true, false));
            releaseTransport.countDown();
            worker.join(5000);
            idleMainLooper();
        } finally {
            releaseTransport.countDown();
            ApplicationLoader.applicationHandler = null;
        }

        assertEquals(0, sends.get());
        assertFalse(HgramVoiceSummarizer.isInFlight(message));
        assertFalse(HgramVoiceSummarizer.isShowing(message));
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test
    public void secondHandoffAcceptDoesNotSendAgain() {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        final MessageObject message = voiceMessage(240, words(60));
        final HgramVoiceSummarizer.TransportHandoff handoff = HgramVoiceSummarizer.newHandoff(
                HgramVoiceSummarizer.newVoicePermit(message,
                        HgramVoiceSummarizer.accountGeneration(1)));
        final AtomicInteger sends = new AtomicInteger();
        handoff.accept();
        sends.incrementAndGet();
        try {
            handoff.accept();
            sends.incrementAndGet();
            org.junit.Assert.fail("second accept must be rejected");
        } catch (HgramVoiceSummarizer.TransportBlockedException expected) {
            // One-shot admission.
        }
        assertEquals(1, sends.get());
    }

    @Test
    public void cachedDialogProfilePublicationBlocksQueuedVoiceSummary() {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        final MessageObject message = voiceMessage(231, words(60));
        resultText = "итог";
        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertEquals(1, queuedRequests.size());

        // Same publication entry point as both cached-dialog writers.
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, true));
        runQueuedRequest();

        assertEquals(0, completionCalls);
        assertNull(capturedPrompt);
        assertEquals(0, ShadowToast.shownToastCount());
        assertFalse(HgramVoiceSummarizer.isInFlight(message));
    }

    @Test
    public void protectedCacheMutationCannotRaceWithHandoff() {
        final MessageObject message = voiceMessage(234, words(60));
        final int generation = HgramVoiceSummarizer.accountGeneration(1);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        HgramVoiceSummarizer.updatePeerProtection(2, 888, userFull(false, false));

        final boolean[] cacheMutated = {false};
        HgramVoiceSummarizer.publishProfileAtomically(1, 777, userFull(true, false), generation,
                () -> cacheMutated[0] = true);
        assertTrue(cacheMutated[0]);
        assertEquals(HgramVoiceSummarizer.PeerProtection.BLOCKED,
                HgramVoiceSummarizer.publishedPeerProtection(1, 777));
        // Account B keeps its own unrestricted status.
        assertEquals(HgramVoiceSummarizer.PeerProtection.ALLOWED,
                HgramVoiceSummarizer.publishedPeerProtection(2, 888));

        final boolean[] sent = {false};
        try {
            HgramVoiceSummarizer.newHandoff(
                    HgramVoiceSummarizer.newVoicePermit(message, generation)).accept();
            sent[0] = true;
            org.junit.Assert.fail("cache publication must win before the handoff");
        } catch (HgramVoiceSummarizer.TransportBlockedException expected) {
            // Silent refusal.
        }
        assertFalse(sent[0]);
    }

    @Test
    public void oldProfileCallbackCannotReauthorizeAfterCleanup() {
        final int staleGeneration = HgramVoiceSummarizer.accountGeneration(1);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        HgramVoiceSummarizer.clearPeerProtection(1);

        final boolean[] cacheMutated = {false};
        assertFalse(HgramVoiceSummarizer.publishProfileAtomically(1, 777, userFull(false, false),
                staleGeneration, () -> cacheMutated[0] = true));
        assertFalse(cacheMutated[0]);
        assertEquals(HgramVoiceSummarizer.PeerProtection.UNKNOWN,
                HgramVoiceSummarizer.publishedPeerProtection(1, 777));

        final MessageObject message = voiceMessage(235, words(60));
        final boolean[] sent = {false};
        try {
            HgramVoiceSummarizer.newHandoff(
                    HgramVoiceSummarizer.newVoicePermit(message, staleGeneration)).accept();
            sent[0] = true;
            org.junit.Assert.fail("old generation must not authorize");
        } catch (HgramVoiceSummarizer.TransportBlockedException expected) {
            // Silent refusal.
        }
        assertFalse(sent[0]);
    }

    @Test
    public void oldCompletionCannotPublishIntoNewEpoch() {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        final MessageObject message = voiceMessage(236, words(60));
        resultText = "устаревший итог";
        HgramVoiceSummarizer.setRequestCompletionForTests((key, model, systemPrompt, userPrompt, permit) -> {
            HgramVoiceSummarizer.newHandoff(permit).accept();
            HgramVoiceSummarizer.clearPeerProtection(1);
            return resultText;
        });

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        runQueuedRequest();

        assertFalse(HgramVoiceSummarizer.isShowing(message));
        assertFalse(HgramVoiceSummarizer.isInFlight(message));
        assertEquals(0, ShadowToast.shownToastCount());
        assertEquals(0, HgramVoiceSummarizer.cachedSummaryCount());
    }

    @Test
    public void lateGateChecksFullProtectionNotOnlyPeer() {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        final MessageObject message = voiceMessage(237, words(60));

        // UNKNOWN (profile cleared) blocks the handoff even if the worker snapshot was ALLOWED.
        final int generation = HgramVoiceSummarizer.accountGeneration(1);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, null);
        assertHandoffBlocked(message, generation);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));

        // Non-peer protection (anti-peek) is checked by the same final gate.
        HgramAntiPeek.setActive(true, true);
        try {
            assertHandoffBlocked(message, generation);
        } finally {
            HgramAntiPeek.setActive(false, true);
        }
    }

    private void assertHandoffBlocked(MessageObject message, int generation) {
        final boolean[] sent = {false};
        try {
            HgramVoiceSummarizer.newHandoff(
                    HgramVoiceSummarizer.newVoicePermit(message, generation)).accept();
            sent[0] = true;
            org.junit.Assert.fail("handoff must be blocked by the full protection gate");
        } catch (HgramVoiceSummarizer.TransportBlockedException expected) {
            // Silent refusal.
        }
        assertFalse(sent[0]);
    }

    @Test
    public void peerSnapshotUsesFull64BitId() {
        final long shortId = 17L;
        final long fullId = 4294967313L; // 2^32 + 17: collides with shortId under a 32-bit key
        HgramVoiceSummarizer.updatePeerProtection(1, shortId, userFull(true, false));
        HgramVoiceSummarizer.updatePeerProtection(1, fullId, userFull(false, false));

        assertEquals(HgramVoiceSummarizer.PeerProtection.BLOCKED,
                HgramVoiceSummarizer.publishedPeerProtection(1, shortId));
        assertEquals(HgramVoiceSummarizer.PeerProtection.ALLOWED,
                HgramVoiceSummarizer.publishedPeerProtection(1, fullId));

        HgramVoiceSummarizer.clearPeerProtection(1);
        HgramVoiceSummarizer.updatePeerProtection(2, shortId, userFull(true, false));
        HgramVoiceSummarizer.clearPeerProtection(1);
        assertEquals(HgramVoiceSummarizer.PeerProtection.BLOCKED,
                HgramVoiceSummarizer.publishedPeerProtection(2, shortId));
    }

    @Test
    public void cleanupIsAccountScopedAndRejectsOldGenerationWorker() {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        HgramVoiceSummarizer.updatePeerProtection(2, 777, userFull(false, false));
        final MessageObject message = voiceMessage(232, words(60));
        resultText = "итог";
        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));

        // Account cleanup bumps only its own epoch; the other account stays ALLOWED.
        HgramVoiceSummarizer.clearPeerProtection(1);
        assertEquals(HgramVoiceSummarizer.PeerProtection.ALLOWED,
                HgramVoiceSummarizer.publishedPeerProtection(2, 777));
        runQueuedRequest();

        assertEquals(0, completionCalls);
        assertNull(capturedPrompt);
        assertFalse(HgramVoiceSummarizer.isInFlight(message));
    }

    @Test
    public void clearedSnapshotNeverStartsCompletionOnBackgroundWorker() {
        NoHeavyMessagesController.userFull = userFull(false, false);
        HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(false, false));
        final MessageObject message = voiceMessage(233, words(60));
        resultText = "итог";
        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));

        HgramVoiceSummarizer.clearPeerProtection(1);
        runQueuedRequest();

        assertEquals(0, completionCalls);
        assertNull(capturedPrompt);
        assertEquals(0, ShadowToast.shownToastCount());
        assertFalse(HgramVoiceSummarizer.isInFlight(message));
        assertEquals(HgramVoiceSummarizer.PeerProtection.UNKNOWN,
                HgramVoiceSummarizer.publishedPeerProtection(1, 777));
    }

    @Test
    public void lateProtectedProfileAndForceCopyDoNotSend() {
        NaConfig.INSTANCE.getForceCopy().setConfigBool(true);
        try {
            NoHeavyMessagesController.userFull = userFull(false, false);
            final MessageObject message = voiceMessage(221, words(60));
            resultText = "итог";

            assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
            }));
            assertEquals(1, queuedRequests.size());

            // ForceCopy cannot turn a late protected profile into an allowed request.
            NoHeavyMessagesController.userFull = userFull(true, false);
            HgramVoiceSummarizer.updatePeerProtection(1, 777, userFull(true, false));
            runQueuedRequest();

            assertEquals(0, completionCalls);
            assertNull(capturedPrompt);
            assertEquals(0, ShadowToast.shownToastCount());
            assertFalse(HgramVoiceSummarizer.isInFlight(message));
        } finally {
            NaConfig.INSTANCE.getForceCopy().setConfigBool(false);
        }
    }

    @Test
    public void privacyChangeBeforeWorkerSkipsCompletion() {
        final MessageObject message = voiceMessage(216, words(60));
        final String transcript = message.getVoiceTranscription().toString();
        final int[] updates = {0};
        resultText = "итог";

        // Hold the launcher: the worker has not started yet when the tap completes.
        assertTrue(HgramVoiceSummarizer.toggle(message, () -> updates[0]++));
        assertTrue(HgramVoiceSummarizer.isInFlight(message));
        assertEquals(1, queuedRequests.size());
        assertEquals(0, completionCalls);

        // Privacy turns on between the tap and the worker; the captured text must not be sent.
        HgramAntiPeek.setActive(true, true);
        runQueuedRequest();

        assertEquals(0, completionCalls);
        assertNull(capturedPrompt);
        assertEquals(0, ShadowToast.shownToastCount());
        assertFalse(HgramVoiceSummarizer.isInFlight(message));
        assertFalse(HgramVoiceSummarizer.isShowing(message));
        assertFalse(HgramVoiceSummarizer.hasCaption(message));
        assertEquals(transcript, HgramVoiceSummarizer.getCaption(message));
        assertEquals(0, HgramVoiceSummarizer.cachedSummaryCount());
        assertTrue(queuedRequests.isEmpty());
        // One redraw for the tap, one for the blocked worker: the spinner must go away.
        assertEquals(2, updates[0]);
    }

    @Test
    public void closedTranscriptIsShownAfterSummaryToggleOff() {
        final MessageObject message = voiceMessage(217, words(60));
        final String transcript = message.getVoiceTranscription().toString();
        resultText = "итог";

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        runQueuedRequest();
        assertTrue(HgramVoiceSummarizer.isShowing(message));
        assertEquals("итог", HgramVoiceSummarizer.getCaption(message));

        assertTrue(HgramVoiceSummarizer.toggle(message, () -> {
        }));
        assertFalse(HgramVoiceSummarizer.isShowing(message));
        assertTrue(HgramVoiceSummarizer.isTranscriptRevealed(message));
        assertTrue(HgramVoiceSummarizer.hasCaption(message));
        assertEquals(transcript, HgramVoiceSummarizer.getCaption(message));
        assertTrue(queuedRequests.isEmpty());
    }

    @Test
    public void releaseGateOffBlocksAllVoiceEntryPoints() {
        HgramVoiceSummarizer.setVoiceSummaryEnabledForTests(false);
        NoHeavyMessagesController.userFull = null; // Gate must win even before any peer check.
        final MessageObject message = voiceMessage(238, words(60));
        final int[] updates = {0};
        resultText = "не должно отправиться";

        assertFalse(HgramVoiceSummarizer.isVoiceSummaryEnabled());
        assertFalse(HgramVoiceSummarizer.canSummarize(message));
        assertFalse(HgramVoiceSummarizer.isSummarizeButtonVisible(message));
        assertFalse(HgramVoiceSummarizer.toggle(message, () -> updates[0]++));
        assertTrue(queuedRequests.isEmpty());
        assertEquals(0, completionCalls);
        assertEquals(0, updates[0]);
        assertFalse(HgramVoiceSummarizer.isInFlight(message));
        assertFalse(HgramVoiceSummarizer.isShowing(message));
        assertFalse(HgramVoiceSummarizer.hasCaption(message));
        assertEquals(0, ShadowToast.shownToastCount());

        final boolean[] sent = {false};
        try {
            HgramVoiceSummarizer.newHandoff(HgramVoiceSummarizer.newVoicePermit(message,
                    HgramVoiceSummarizer.accountGeneration(1))).accept();
            sent[0] = true;
            org.junit.Assert.fail("disabled gate must block the transport bridge");
        } catch (HgramVoiceSummarizer.TransportBlockedException expected) {
            // Silent refusal.
        }
        assertFalse(sent[0]);
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

    private static void resetMessagesControllerInstances() {
        try {
            final Field field = MessagesController.class.getDeclaredField("Instance");
            field.setAccessible(true);
            ((SparseArray<?>) field.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static TLRPC.UserFull userFull(boolean peerNoForwards, boolean myNoForwards) {
        final TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.noforwards_peer_enabled = peerNoForwards;
        full.noforwards_my_enabled = myNoForwards;
        return full;
    }

    private static MessageObject voiceMessage(int id, String transcript) {
        return voiceOrRoundMessage(id, transcript, false);
    }

    private static MessageObject roundVideoMessage(int id, String transcript) {
        return voiceOrRoundMessage(id, transcript, true);
    }

    private static MessageObject voiceOrRoundMessage(int id, String transcript, boolean roundVideo) {
        final TLRPC.TL_message raw = new TLRPC.TL_message();
        raw.id = id;
        raw.dialog_id = 777;
        raw.voiceTranscription = transcript;
        raw.voiceTranscriptionFinal = true;
        final TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = new TLRPC.TL_document();
        media.document.attributes = new ArrayList<>();
        if (roundVideo) {
            media.document.mime_type = "video/mp4";
            final TLRPC.TL_documentAttributeVideo video = new TLRPC.TL_documentAttributeVideo();
            video.round_message = true;
            video.w = 240;
            video.h = 240;
            media.document.attributes.add(video);
        } else {
            final TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
            audio.voice = true;
            media.document.attributes.add(audio);
        }
        raw.media = media;
        return new MessageObject(1, raw, false, false);
    }

    private static String words(int count) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(' ');
            out.append("слово").append(i);
        }
        return out.toString();
    }

    private static String longTranscript(int chars) {
        final StringBuilder out = new StringBuilder(chars + 8);
        while (out.length() < chars) {
            out.append("слово ");
        }
        return out.substring(0, chars);
    }
}
