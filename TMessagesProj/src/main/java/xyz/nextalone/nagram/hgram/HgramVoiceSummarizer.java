package xyz.nextalone.nagram.hgram;

import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tw.nekomimi.nekogram.transtale.source.LLMTranslator;

/**
 * Process-local summary of a voice message or a round video transcription. The summary never
 * touches {@link TLRPC.Message}, {@code custom_params} or the database: it lives only in this
 * class for the lifetime of the process. Eligibility and the request path are deliberately
 * separate from {@link HgramSummarizer}, whose text summary invariants must not change.
 */
public final class HgramVoiceSummarizer {

    /**
     * Release gate for 1311: voice/round-video summaries stay implemented but disabled. For 1312
     * this is the single switch: change it to {@code true} (no UI setting, no other code change).
     */
    public static final boolean VOICE_SUMMARY_RELEASE_ENABLED = false;
    private static volatile boolean voiceSummaryEnabled = VOICE_SUMMARY_RELEASE_ENABLED;

    /** Request input is the raw transcript, capped independently of the text summary limit. */
    private static final int MAX_REQUEST_CHARS = 8000;
    /** Access-order cache is capped so a long session cannot grow it without bound. */
    private static final int MAX_CACHED_SUMMARIES = 128;

    private static final Set<RequestKey> inFlight =
            Collections.synchronizedSet(new HashSet<>());
    /** Owner token of the request that currently owns each in-flight key. */
    private static final Map<RequestKey, Integer> inFlightTokens = new java.util.HashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger REQUEST_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Messages whose summary is currently displayed instead of the transcript. */
    private static final Set<RequestKey> showing =
            Collections.synchronizedSet(new HashSet<>());
    /**
     * Messages whose summary was closed and whose transcript must stay visible even when the
     * ordinary voice-transcription flag is off. Cleared as soon as a new summary/request starts.
     */
    private static final Set<RequestKey> closedTranscripts =
            Collections.synchronizedSet(new HashSet<>());

    /**
     * A cached summary together with the SHA-256 of the raw transcript it was produced from.
     * Holds no {@link MessageObject}/{@link TLRPC.Message} reference on purpose, so a cache entry
     * can neither keep a recycled cell alive nor point at an owner that has since been re-bound.
     */
    private static final class Entry {
        final String summary;
        final String digest;

        Entry(String summary, String digest) {
            this.summary = summary;
            this.digest = digest;
        }
    }

    private static final Map<RequestKey, Entry> cache =
            Collections.synchronizedMap(new LinkedHashMap<RequestKey, Entry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<RequestKey, HgramVoiceSummarizer.Entry> eldest) {
                    return size() > MAX_CACHED_SUMMARIES;
                }
            });

    /**
     * Test seams for the background request path: production starts a named thread and calls the
     * live LLM. Package-private so unit tests can hold a request open and count launches without
     * touching the real network; the production path is exactly the two defaults below.
     */
    interface RequestLauncher {
        void launch(Runnable task);
    }

    interface RequestCompletion {
        String complete(String key, String model, String systemPrompt, String userPrompt,
                        VoicePermit permit) throws Throwable;
    }

    private static final RequestLauncher THREAD_LAUNCHER =
            task -> new Thread(task, "HgramVoiceSummarizer").start();
    private static final RequestCompletion LIVE_COMPLETION =
            (key, model, systemPrompt, userPrompt, permit) ->
                    LLMTranslator.INSTANCE.completeBlocking(key, model, systemPrompt, userPrompt, permit);
    private static volatile RequestLauncher requestLauncher = THREAD_LAUNCHER;
    private static volatile RequestCompletion requestCompletion = LIVE_COMPLETION;

    static final class RequestKey {
        final int account;
        final long dialogId;
        final int msgId;

        RequestKey(int account, long dialogId, int msgId) {
            this.account = account;
            this.dialogId = dialogId;
            this.msgId = msgId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RequestKey)) return false;
            final RequestKey other = (RequestKey) o;
            return account == other.account && dialogId == other.dialogId && msgId == other.msgId;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(account, dialogId, msgId);
        }
    }

    /** Voice-specific peer protection; only {@link #ALLOWED} may reach the network. */
    enum PeerProtection {
        ALLOWED,
        BLOCKED,
        UNKNOWN
    }

    /** Full-width identity of a voice privacy snapshot: no 32-bit truncation of the user id. */
    static final class PeerKey {
        final int account;
        final long userId;

        PeerKey(int account, long userId) {
            this.account = account;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PeerKey)) return false;
            final PeerKey other = (PeerKey) o;
            return account == other.account && userId == other.userId;
        }

        @Override
        public int hashCode() {
            return 31 * account + (int) (userId ^ (userId >>> 32));
        }
    }

    /**
     * One explicit voice-operation permit. It is passed through the completion call and consumed
     * exactly once at the transport boundary; a plain translation/text call passes {@code null}.
     */
    public static final class VoicePermit {
        final MessageObject message;
        final PeerKey peer;
        final int generation;
        boolean handedOff;

        VoicePermit(MessageObject message, PeerKey peer, int generation) {
            this.message = message;
            this.peer = peer;
            this.generation = generation;
        }
    }

    /**
     * Serializes every profile/snapshot publication with the final voice handoff. The lock is only
     * held for the predicate check and the one-way handoff flag; the blocking HTTP call runs after
     * unlock. A publication that wins this lock before the handoff cancels the request.
     */
    private static final java.util.concurrent.locks.ReentrantLock AUTHORIZATION_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    /** Last safely published peer status per full (account, userId) identity. */
    private static final Map<PeerKey, PeerProtection> peerProtection = new java.util.HashMap<>();

    /** Account cleanup epoch: a worker from an older epoch can never authorize a send. */
    private static final Map<Integer, Integer> accountGenerations = new java.util.HashMap<>();

    private static PeerKey peerKey(int account, long userId) {
        return new PeerKey(account, userId);
    }

    public static int accountGeneration(int account) {
        AUTHORIZATION_LOCK.lock();
        try {
            Integer generation = accountGenerations.get(account);
            return generation == null ? 0 : generation;
        } finally {
            AUTHORIZATION_LOCK.unlock();
        }
    }

    static PeerProtection publishedPeerProtection(int account, long userId) {
        AUTHORIZATION_LOCK.lock();
        try {
            final PeerProtection status = peerProtection.get(peerKey(account, userId));
            return status == null ? PeerProtection.UNKNOWN : status;
        } finally {
            AUTHORIZATION_LOCK.unlock();
        }
    }

    static VoicePermit newVoicePermit(MessageObject message, int generation) {
        return new VoicePermit(message,
                peerKey(message.currentAccount, message.getDialogId()), generation);
    }

    /**
     * Production transport bridge: check the complete voice protection and epoch, mark the permit
     * as handed off under the publication lock, then run the prepared blocking HTTP request after
     * the lock is released. A block is silent.
     */
    public static void authorizeAndSend(VoicePermit permit, Runnable handoff) {
        if (handoff == null) return;
        authorizeAndRun(permit, () -> {
            handoff.run();
            return null;
        });
    }

    /** Same boundary for a request that returns the transport response. */
    public static <T> T authorizeAndSendResult(VoicePermit permit, java.util.function.Supplier<T> handoff) {
        if (handoff == null) return null;
        return authorizeAndRun(permit, handoff::get);
    }

    /**
     * The handoff is a thread start performed under the publication lock: once the sender thread
     * exists, the request is considered handed to the transport and a later publication no longer
     * promises to revoke bytes. The caller waits outside the lock, so HTTP never runs while it is
     * held and no unconditional body can follow a publication that won the lock.
     */
    private static <T> T authorizeAndRun(VoicePermit permit, java.util.concurrent.Callable<T> handoff) {
        if (permit == null) {
            try {
                return handoff.call();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (!voiceSummaryEnabled) {
            throw new TransportBlockedException();
        }
        final java.util.concurrent.FutureTask<T> task;
        AUTHORIZATION_LOCK.lock();
        try {
            if (permit.handedOff || !isProtectionClear(permit.message, permit.generation)) {
                throw new TransportBlockedException();
            }
            permit.handedOff = true;
            task = new java.util.concurrent.FutureTask<>(handoff);
            new Thread(task, "HgramVoiceTransport").start();
        } finally {
            AUTHORIZATION_LOCK.unlock();
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportBlockedException();
        } catch (java.util.concurrent.ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Atomically replaces a profile snapshot and its cache entry under the same barrier. A callback
     * from an older account epoch is dropped before it can touch either the cache or the snapshot.
     */
    public static boolean publishProfileAtomically(int account, long userId, TLRPC.UserFull userFull,
                                                   int expectedGeneration, Runnable cacheMutation) {
        AUTHORIZATION_LOCK.lock();
        try {
            final Integer generation = accountGenerations.get(account);
            if ((generation == null ? 0 : generation) != expectedGeneration) {
                return false;
            }
            final PeerKey key = peerKey(account, userId);
            peerProtection.put(key, PeerProtection.UNKNOWN);
            if (cacheMutation != null) {
                cacheMutation.run();
            }
            peerProtection.put(key, statusOf(userFull));
            return true;
        } finally {
            AUTHORIZATION_LOCK.unlock();
        }
    }

    /** Conservative UNKNOWN while a fresh UserFull is being installed by an older request. */
    public static void markProfilePending(int account, long userId, int expectedGeneration) {
        publishProfileAtomically(account, userId, null, expectedGeneration, null);
    }

    private static PeerProtection statusOf(TLRPC.UserFull userFull) {
        if (userFull == null) return PeerProtection.UNKNOWN;
        return userFull.noforwards_peer_enabled || userFull.noforwards_my_enabled
                ? PeerProtection.BLOCKED : PeerProtection.ALLOWED;
    }

    /** Silent control-flow exception: no message, no cause, nothing to log. */
    public static final class TransportBlockedException extends RuntimeException {
        TransportBlockedException() {
            super(null, null, false, false);
        }
    }

    private HgramVoiceSummarizer() {
    }

    /** Release gate: every voice entry point checks this before any privacy/eligibility work. */
    public static boolean isVoiceSummaryEnabled() {
        return voiceSummaryEnabled;
    }

    /** Test hook; production default is the release constant above. */
    static void setVoiceSummaryEnabledForTests(boolean enabled) {
        voiceSummaryEnabled = enabled;
    }

    /**
     * Right to the ✨ button for a voice message/round video. Does not require an open transcript,
     * an API key, a model or a merge chain: those are checked only when a request starts.
     */
    public static boolean canSummarize(MessageObject message) {
        if (!voiceSummaryEnabled) return false;
        if (message == null || message.messageOwner == null) return false;
        if (!DialogObject.isUserDialog(message.getDialogId())) return false;
        if (HgramSummarizer.isSecretChatMessage(message)) return false;
        if (peerProtection(message) != PeerProtection.ALLOWED) return false;
        if (!isEnabledHere()) return false;
        if (!message.isVoice() && !message.isRoundVideo()) return false;
        final TLRPC.Message owner = message.messageOwner;
        if (!owner.voiceTranscriptionFinal) return false;
        final String raw = owner.voiceTranscription;
        if (TextUtils.isEmpty(raw)) return false;
        if (HgramSummarizer.wordCount(raw) < HgramConfig.getSummarizerMinWords()) return false;
        if (owner.ttl > 0 || owner.destroyTime != 0 || owner.noforwards) return false;
        final TLRPC.MessageMedia media = MessageObject.getMedia(owner);
        return media == null || media.ttl_seconds == 0;
    }

    private static boolean isEnabledHere() {
        return HgramConfig.isSummarizerEnabled()
                && !HgramConfig.isAntiPeekActive()
                && !HgramAntiPeek.isActive()
                && !HgramSecretSpace.isActive();
    }

    /**
     * A private chat can forbid forwarding through {@code UserFull} only: a message received
     * before the ban keeps {@code messageOwner.noforwards == false}. Any of the two flags blocks
     * the network request; ForceCopy/WithOverride are deliberately not consulted.
     */
    static boolean isPeerProtected(MessageObject message) {
        return peerProtection(message) == PeerProtection.BLOCKED;
    }

    /**
     * Current voice-specific peer status. Runs on the UI thread (tap path) and also publishes the
     * result, so a worker that cannot touch {@code MessagesController} sees the latest decision.
     * A missing {@code UserFull} is UNKNOWN, never ALLOWED: a cold cache must not leak a transcript
     * from a protected private chat.
     */
    static PeerProtection peerProtection(MessageObject message) {
        if (message == null || message.messageOwner == null) return PeerProtection.UNKNOWN;
        final PeerKey key = peerKey(message.currentAccount, message.getDialogId());
        // A published status always wins over a stale live read. Only a cold cache may seed the
        // snapshot, and the re-check under the lock prevents a tap read from overwriting a newer
        // cache-writer publication.
        final PeerProtection status = evaluatePeerProtection(message);
        AUTHORIZATION_LOCK.lock();
        try {
            final PeerProtection existing = peerProtection.get(key);
            if (existing != null) {
                return existing;
            }
            peerProtection.put(key, status);
            return status;
        } finally {
            AUTHORIZATION_LOCK.unlock();
        }
    }

    private static PeerProtection evaluatePeerProtection(MessageObject message) {
        if (message == null || message.messageOwner == null) return PeerProtection.UNKNOWN;
        final long dialogId = message.getDialogId();
        if (!DialogObject.isUserDialog(dialogId)) return PeerProtection.UNKNOWN;
        final TLRPC.UserFull userFull = MessagesController.getInstance(message.currentAccount)
                .getUserFull(dialogId);
        if (userFull == null) return PeerProtection.UNKNOWN;
        return userFull.noforwards_peer_enabled || userFull.noforwards_my_enabled
                ? PeerProtection.BLOCKED : PeerProtection.ALLOWED;
    }

    /** Worker-safe lookup: only the published snapshot plus the captured account epoch. */
    private static PeerProtection workerPeerProtection(MessageObject message, int expectedGeneration) {
        if (message == null || message.messageOwner == null) return PeerProtection.UNKNOWN;
        // Only the atomically published snapshot is authoritative on the worker path. Re-reading
        // MessagesController here would bypass both the cache-writer publication and the epoch.
        final PeerKey key = peerKey(message.currentAccount, message.getDialogId());
        AUTHORIZATION_LOCK.lock();
        try {
            final Integer generation = accountGenerations.get(key.account);
            if ((generation == null ? 0 : generation) != expectedGeneration) {
                return PeerProtection.UNKNOWN;
            }
            final PeerProtection status = peerProtection.get(key);
            return status == null ? PeerProtection.UNKNOWN : status;
        } finally {
            AUTHORIZATION_LOCK.unlock();
        }
    }

    /** Called by MessagesController when a UserFull is loaded or its protection flags change. */
    public static void updatePeerProtection(int account, long userId, TLRPC.UserFull userFull) {
        publishProfileAtomically(account, userId, userFull, accountGeneration(account), null);
    }

    /**
     * Account cleanup/logout. Only this account's snapshots are invalidated; its epoch is bumped so
     * workers and callbacks from before the cleanup can never publish or authorize a send.
     */
    public static void clearPeerProtection(int account) {
        AUTHORIZATION_LOCK.lock();
        try {
            final Integer generation = accountGenerations.get(account);
            accountGenerations.put(account, (generation == null ? 0 : generation) + 1);
            peerProtection.keySet().removeIf(key -> key.account == account);
        } finally {
            AUTHORIZATION_LOCK.unlock();
        }
    }

    /**
     * Full privacy gate re-run inside the worker, right before the captured transcript would be
     * sent. Kept separate from {@link #canSummarize(MessageObject)} because only this check covers
     * the moment the request actually starts.
     */
    private static boolean isProtectionClear(MessageObject message, int expectedGeneration) {
        if (message == null || message.messageOwner == null) return false;
        if (!DialogObject.isUserDialog(message.getDialogId())) return false;
        if (!HgramConfig.isSummarizerEnabled()) return false;
        if (HgramConfig.isAntiPeekActive()) return false;
        if (HgramAntiPeek.isActive()) return false;
        if (HgramSecretSpace.isActive()) return false;
        if (HgramSummarizer.isSecretChatMessage(message)) return false;
        final TLRPC.Message owner = message.messageOwner;
        if (owner.noforwards || owner.ttl > 0 || owner.destroyTime != 0) return false;
        final TLRPC.MessageMedia media = MessageObject.getMedia(owner);
        if (media != null && media.ttl_seconds != 0) return false;
        return workerPeerProtection(message, expectedGeneration) == PeerProtection.ALLOWED;
    }

    public static boolean isInFlight(MessageObject message) {
        if (!voiceSummaryEnabled) return false;
        return message != null && message.messageOwner != null && inFlight.contains(requestKey(message));
    }

    private static int beginRequestOwner(RequestKey key) {
        final int token = REQUEST_SEQUENCE.incrementAndGet();
        synchronized (inFlight) {
            inFlightTokens.put(key, token);
        }
        return token;
    }

    /** Removes the loading marker only if this exact request still owns the key. */
    private static boolean finishRequestOwner(RequestKey key, int token) {
        synchronized (inFlight) {
            final Integer owner = inFlightTokens.get(key);
            if (owner == null || owner != token) {
                return false;
            }
            inFlightTokens.remove(key);
            inFlight.remove(key);
            return true;
        }
    }

    /** Whether the voice ✨ may be drawn: the release gate plus the normal eligibility rules. */
    public static boolean isSummarizeButtonVisible(MessageObject message) {
        return voiceSummaryEnabled && (isShowing(message) || canSummarize(message));
    }

    /** True when the summary, not the transcript, is the current caption of this message. */
    public static boolean isShowing(MessageObject message) {
        if (!voiceSummaryEnabled) return false;
        if (message == null || message.messageOwner == null) return false;
        final RequestKey key = requestKey(message);
        if (!showing.contains(key)) return false;
        if (freshEntry(message, key) != null) return true;
        // The transcript no longer matches the cached source: drop the marker with the cache.
        showing.remove(key);
        closedTranscripts.remove(key);
        return false;
    }

    /**
     * The caption block is needed for the shown summary and for a transcript explicitly revealed
     * by closing that summary, even while {@code isVoiceTranscriptionOpen()} is false.
     */
    public static boolean hasCaption(MessageObject message) {
        return isShowing(message) || isTranscriptRevealed(message);
    }

    /** True when closing the summary left the ordinary transcript visible for this message. */
    static boolean isTranscriptRevealed(MessageObject message) {
        if (!voiceSummaryEnabled) return false;
        if (message == null || message.messageOwner == null) return false;
        if (!closedTranscripts.contains(requestKey(message))) return false;
        final CharSequence transcription = message.getVoiceTranscription();
        return !TextUtils.isEmpty(transcription);
    }

    /** Summary while shown; the ordinary voice transcription otherwise. */
    public static String getCaption(MessageObject message) {
        if (!voiceSummaryEnabled) {
            if (message == null) return null;
            final CharSequence transcription = message.getVoiceTranscription();
            return transcription == null ? null : transcription.toString();
        }
        if (message == null || message.messageOwner == null) return null;
        final RequestKey key = requestKey(message);
        if (showing.contains(key)) {
            final Entry entry = freshEntry(message, key);
            if (entry != null) return entry.summary;
        }
        final CharSequence transcription = message.getVoiceTranscription();
        return transcription == null ? null : transcription.toString();
    }

    /**
     * Opens/closes the local summary or starts a request. Closing and reopening from the cache
     * never touch the network; a repeat tap while loading only asks the UI to redraw.
     */
    public static boolean toggle(MessageObject message, Runnable onUpdated) {
        if (!voiceSummaryEnabled) return false;
        if (message == null || message.messageOwner == null || onUpdated == null) return false;
        final RequestKey scopedKey = requestKey(message);

        if (isShowing(message)) {
            showing.remove(scopedKey);
            // The transcript may be closed in the cell; remember that it must be shown instead
            // of vanishing when the summary is dismissed.
            closedTranscripts.add(scopedKey);
            onUpdated.run();
            return true;
        }
        if (inFlight.contains(scopedKey)) {
            // A repeat tap must not start a second request or close the bubble. It still asks the
            // UI to redraw, because the loading look is derived from inFlight, not from the view.
            onUpdated.run();
            return true;
        }
        if (!canSummarize(message)) return false;

        final Entry cached = freshEntry(message, scopedKey);
        if (cached != null) {
            closedTranscripts.remove(scopedKey);
            showing.add(scopedKey);
            onUpdated.run();
            return true;
        }

        final String key = HgramConfig.getSummarizerKey();
        final String model = HgramConfig.getSummarizerModel();
        if (TextUtils.isEmpty(key)) {
            toast("HgramSummarizerNoKey");
            return false;
        }
        if (TextUtils.isEmpty(model)) {
            toast("HgramSummarizerNoModel");
            return false;
        }
        if (!inFlight.add(scopedKey)) {
            onUpdated.run();
            return true;
        }
        final int requestOwner = beginRequestOwner(scopedKey);
        closedTranscripts.remove(scopedKey);
        try {
            // Tell the UI before any network work: the button must switch to its loading look in
            // the same frame as the tap. inFlight is the single source of truth for that look.
            onUpdated.run();
            // Capture the source before the network call: the owner may change while we wait.
            final String requestDigest = sourceDigest(message);
            final int requestGeneration = accountGeneration(scopedKey.account);
            final String userPrompt = truncatePrefix(message.messageOwner.voiceTranscription, MAX_REQUEST_CHARS);
            final String systemPrompt = truncatePrefix(HgramConfig.getSummarizerSystemPrompt(),
                    HgramSummarizer.MAX_SYSTEM_PROMPT_CHARS);
            requestLauncher.launch(() -> {
                // Privacy may have changed between the tap and this worker: re-check everything
                // before the captured transcript reaches the network. A blocked request is
                // silent: no completion, no toast, no transcript in the logs.
                if (!isProtectionClear(message, requestGeneration)) {
                    AndroidUtilities.runOnUIThread(() -> {
                        finishRequestOwner(scopedKey, requestOwner);
                        onUpdated.run();
                    });
                    return;
                }
                String result = null;
                String errorKind = null;
                boolean transportBlocked = false;
                // Explicit voice permit: the completion/transport bridge consumes it exactly once
                // immediately before the prepared HTTP request is accepted.
                final VoicePermit permit = newVoicePermit(message, requestGeneration);
                try {
                    result = requestCompletion.complete(key, model, systemPrompt, userPrompt, permit);
                } catch (TransportBlockedException blocked) {
                    transportBlocked = true;
                } catch (Throwable t) {
                    errorKind = classifyError(t);
                }
                if (transportBlocked) {
                    AndroidUtilities.runOnUIThread(() -> {
                        finishRequestOwner(scopedKey, requestOwner);
                        onUpdated.run();
                    });
                    return;
                }
                final String finalResult = result;
                final String finalErrorKind = errorKind;
                AndroidUtilities.runOnUIThread(() -> {
                    // Drop inFlight first: the redraw below must already see the idle/done state,
                    // otherwise an error would leave the spinner on screen for another frame.
                    finishRequestOwner(scopedKey, requestOwner);
                    try {
                        // A completion that returns after account cleanup must not publish a
                        // summary or a toast into the new epoch.
                        if (accountGeneration(scopedKey.account) != requestGeneration) {
                            return;
                        }
                        if (TextUtils.isEmpty(finalResult)) {
                            toast(errorMessage(finalErrorKind));
                        } else {
                            applyIfFresh(message, scopedKey, finalResult, requestDigest);
                        }
                    } finally {
                        onUpdated.run();
                    }
                });
            });
        } catch (Throwable t) {
            finishRequestOwner(scopedKey, requestOwner);
            try {
                onUpdated.run();
            } catch (Throwable ignored) {
                // The caller's redraw failed; the request is already out of inFlight.
            }
            return false;
        }
        return true;
    }

    /**
     * Stores and shows a network result only while the raw transcript it was requested for is
     * still the transcript on screen: a reused cell must not show someone else's summary.
     */
    private static boolean applyIfFresh(MessageObject message, RequestKey key,
                                        String summary, String expectedDigest) {
        if (message == null || message.messageOwner == null || TextUtils.isEmpty(summary)) {
            return false;
        }
        final String current = sourceDigest(message);
        if (expectedDigest == null || current == null || !expectedDigest.equals(current)) {
            cache.remove(key);
            showing.remove(key);
            closedTranscripts.remove(key);
            return false;
        }
        cache.put(key, new Entry(summary, current));
        closedTranscripts.remove(key);
        showing.add(key);
        return true;
    }

    /**
     * Returns the cached summary only while its digest still matches the raw transcript. A stale
     * entry is removed together with its showing marker, so it can never be displayed again.
     */
    private static Entry freshEntry(MessageObject message, RequestKey key) {
        final Entry entry = cache.get(key);
        if (entry == null) return null;
        final String current = sourceDigest(message);
        if (entry.digest == null || current == null || !entry.digest.equals(current)) {
            cache.remove(key);
            showing.remove(key);
            closedTranscripts.remove(key);
            return null;
        }
        return entry;
    }

    private static RequestKey requestKey(MessageObject message) {
        return new RequestKey(message.currentAccount, message.getDialogId(), message.getId());
    }

    private static String sourceDigest(MessageObject message) {
        if (message == null || message.messageOwner == null) return null;
        return digest(message.messageOwner.voiceTranscription);
    }

    /** SHA-256 hex of the raw transcript; hashCode is a last-resort fallback that never runs. */
    private static String digest(String text) {
        final String value = text == null ? "" : text;
        try {
            final byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                final int v = b & 0xff;
                if (v < 16) out.append('0');
                out.append(Integer.toHexString(v));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** Plain prefix truncation: the prompt handed to the LLM stays a prefix of the raw input. */
    private static String truncatePrefix(String text, int max) {
        if (text == null || max <= 0) return "";
        if (text.length() <= max) return text;
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String classifyError(Throwable t) {
        if (t == null) return "network";
        if (t.getClass().getSimpleName().contains("RateLimitException")) return "limit";
        final String message = t.getMessage();
        final String value = message == null ? "" : message.toLowerCase(java.util.Locale.US);
        if (value.contains("429") || value.contains("rate limit")) return "limit";
        if (value.contains("401") || value.contains("403") || value.contains("api key")) return "nokey";
        if (value.contains("model")) return "nomodel";
        return "network";
    }

    private static String errorMessage(String kind) {
        if (kind == null) return "HgramSummarizerNetwork";
        switch (kind) {
            case "limit": return "HgramSummarizerLimit";
            case "nokey": return "HgramSummarizerNoKey";
            case "nomodel": return "HgramSummarizerNoModel";
            default: return "HgramSummarizerNetwork";
        }
    }

    private static void toast(String key) {
        final android.content.Context context = ApplicationLoader.applicationContext;
        if (context == null) return;
        final int res = resourceFor(key);
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(context,
                res != 0 ? context.getString(res) : key, Toast.LENGTH_SHORT).show());
    }

    private static int resourceFor(String key) {
        switch (key) {
            case "HgramSummarizerNoKey": return org.telegram.messenger.R.string.HgramSummarizerNoKey;
            case "HgramSummarizerNoModel": return org.telegram.messenger.R.string.HgramSummarizerNoModel;
            case "HgramSummarizerLimit": return org.telegram.messenger.R.string.HgramSummarizerLimit;
            case "HgramSummarizerNetwork": return org.telegram.messenger.R.string.HgramSummarizerNetwork;
            default: return 0;
        }
    }

    /** Test hook: holds background requests instead of starting them. */
    static void setRequestLauncherForTests(RequestLauncher launcher) {
        requestLauncher = launcher == null ? THREAD_LAUNCHER : launcher;
    }

    /** Test hook: answers requests without the network. */
    static void setRequestCompletionForTests(RequestCompletion completion) {
        requestCompletion = completion == null ? LIVE_COMPLETION : completion;
    }

    /** Test hook: restores the production seams and drops all process-local state. */
    static void resetForTests() {
        requestLauncher = THREAD_LAUNCHER;
        requestCompletion = LIVE_COMPLETION;
        voiceSummaryEnabled = VOICE_SUMMARY_RELEASE_ENABLED;
        synchronized (inFlight) {
            inFlight.clear();
            inFlightTokens.clear();
        }
        showing.clear();
        closedTranscripts.clear();
        cache.clear();
        AUTHORIZATION_LOCK.lock();
        try {
            peerProtection.clear();
            accountGenerations.clear();
        } finally {
            AUTHORIZATION_LOCK.unlock();
        }
    }

    /** Test hook: how many summaries this process still has a verifiable source digest for. */
    static int cachedSummaryCount() {
        return cache.size();
    }
}
