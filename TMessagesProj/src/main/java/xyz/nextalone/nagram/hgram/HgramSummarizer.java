package xyz.nextalone.nagram.hgram;

import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tw.nekomimi.nekogram.transtale.source.LLMTranslator;

/** Local OpenRouter summarization for private chats. */
public final class HgramSummarizer {

    public static final String DEFAULT_PROMPT =
            "Ты — помощник, который кратко пересказывает личные сообщения.\n"
                    + "Верни только суть: 2–4 предложения, без вступлений и оценок, без домыслов.\n"
                    + "Если сообщений несколько, объедини их в один связный пересказ.\n"
                    + "Сохраняй имена, числа, даты и договорённости. Язык ответа — язык исходного текста.";

    public static final class RequestKey {
        public final int account;
        public final long dialogId;
        public final int msgId;

        public RequestKey(int account, long dialogId, int msgId) {
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

    /**
     * Identity of a message that carries a locally produced summary. Only the coordinates are kept
     * so the marker cannot pin a {@link MessageObject}/{@link TLRPC.Message} in memory.
     */
    public static final class SummaryKey {
        public final long dialogId;
        public final int msgId;

        public SummaryKey(long dialogId, int msgId) {
            this.dialogId = dialogId;
            this.msgId = msgId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SummaryKey)) return false;
            final SummaryKey other = (SummaryKey) o;
            return dialogId == other.dialogId && msgId == other.msgId;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(dialogId, msgId);
        }
    }

    /**
     * Process-local proof that a summary was produced from the current source. Never persisted.
     * Holds no {@link MessageObject}/{@link TLRPC.Message} reference on purpose: a cache entry must
     * neither keep a recycled cell alive nor point at an owner that has since been re-bound.
     */
    private static final class FingerprintEntry {
        final String fingerprint;
        final String summary;

        FingerprintEntry(String fingerprint, String summary) {
            this.fingerprint = fingerprint;
            this.summary = summary;
        }
    }

    private static final java.util.Set<RequestKey> inFlight = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Messages whose summary was produced by this process; the only marker persistence skips. */
    private static final Set<SummaryKey> localSummaries = Collections.synchronizedSet(new HashSet<>());
    /** Access-order caches are capped so a long session cannot grow them without bound. */
    private static final int MAX_CACHED_SUMMARIES = 128;
    private static final Map<RequestKey, FingerprintEntry> fingerprints =
            Collections.synchronizedMap(new LinkedHashMap<RequestKey, FingerprintEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RequestKey, FingerprintEntry> eldest) {
                    return size() > MAX_CACHED_SUMMARIES;
                }
            });
    private static final Map<RequestKey, String> activeChains =
            Collections.synchronizedMap(new LinkedHashMap<RequestKey, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<RequestKey, String> eldest) {
                    return size() > MAX_CACHED_SUMMARIES;
                }
            });
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final int MAX_REQUEST_CHARS = 8000;
    public static final int MAX_SYSTEM_PROMPT_CHARS = 2000;
    private static final String[] NON_TEXT_MODEL_NAME_PARTS = {
            "lyria", "whisper", "tts", "audio", "image", "vision-only",
            "dall-e", "stable-diffusion", "midjourney", "clip", "embed", "rerank"
    };

    private HgramSummarizer() {
    }

    public static int summarizeBounds(android.graphics.RectF out, boolean outgoing,
                                       float cellWidth, float bubbleWidth, boolean avatarVisible,
                                       float layoutHeight, float deltaBottom, float reactionsHeight,
                                       int cellHeight, int size, int inset, float minTop) {
        return summarizeBoundsAdaptive(out, outgoing, cellWidth, bubbleWidth, avatarVisible,
                0f, layoutHeight + deltaBottom, reactionsHeight, cellHeight, size, inset, minTop,
                AndroidUtilities.dp(8));
    }

    /** Legacy 12-arg policy keeps the historical 8dp bubble overlap allowance (tests rely on it). */
    public static int summarizeBoundsAdaptive(android.graphics.RectF out, boolean outgoing,
                                       float cellWidth, float bubbleWidth, boolean avatarVisible,
                                       float bubbleTop, float bubbleBottom, float reactionsHeight,
                                               int cellHeight, int size, int gap, float minTop) {
        return summarizeBoundsAdaptive(out, outgoing, cellWidth, bubbleWidth, avatarVisible,
                bubbleTop, bubbleBottom, reactionsHeight, cellHeight, size, gap, minTop,
                AndroidUtilities.dp(8));
    }

    /**
     * @param maxOverlap how many pixels the full-size button may overlap the bubble (tier 2);
     *                   pass 0 to disable the overlap tier entirely.
     */
    public static int summarizeBoundsAdaptive(android.graphics.RectF out, boolean outgoing,
                                       float cellWidth, float bubbleWidth, boolean avatarVisible,
                                       float bubbleTop, float bubbleBottom, float reactionsHeight,
                                               int cellHeight, int size, int gap, float minTop,
                                               float maxOverlap) {
        // The legacy signature only knows outgoing+width; resolve the absolute bubble edges once
        // and hand them to the single geometry policy below.
        final float width = Math.max(AndroidUtilities.dp(40), bubbleWidth);
        final float absoluteLeft = bubbleLeft(outgoing, cellWidth, width, avatarVisible);
        return summarizeBoundsInBubble(out, cellWidth, absoluteLeft, absoluteLeft + width,
                bubbleTop, bubbleBottom, reactionsHeight, cellHeight, size, gap, minTop,
                outgoing, maxOverlap);
    }

    /**
     * Single geometry policy for the summarize button. All bubble values are absolute pixels;
     * {@code preferLeft} only picks the side tried first, never a different bubble.
     *
     * @param maxOverlap how many pixels the full-size button may overlap the bubble (tier 2);
     *                   pass 0 to disable the overlap tier entirely.
     */
    public static int summarizeBoundsInBubble(android.graphics.RectF out, float cellWidth,
                                              float bubbleLeft, float bubbleRight,
                                              float bubbleTop, float bubbleBottom,
                                              float reactionsHeight, int cellHeight, int size,
                                              int gap, float minTop, boolean preferLeft,
                                              float maxOverlap) {
        out.setEmpty();
        final float edge = AndroidUtilities.dp(2);
        final float minSize = AndroidUtilities.dp(24);
        final float fullSize = Math.max(size, minSize);
        final float overlapLimit = Math.max(0f, maxOverlap);
        final float[] gaps = {
                Math.max(edge, gap),
                Math.max(edge, gap / 2f),
                edge
        };

        // Tier 1: full size beside the bubble, reducing the gap down to 2dp.
        // Primary side first, the opposite side only as a fallback.
        for (int side = 0; side < 2; side++) {
            final boolean left = side == 0 ? preferLeft : !preferLeft;
            for (float candidateGap : gaps) {
                final float x = left ? bubbleLeft - candidateGap - fullSize : bubbleRight + candidateGap;
                if (x >= edge && x + fullSize <= cellWidth - edge) {
                    placeBeside(out, x, fullSize, bubbleBottom, reactionsHeight, minTop, cellHeight, cellWidth, edge);
                    return 1;
                }
            }
        }

        // Tier 2: full size may overlap the bubble; disabled when maxOverlap <= 0.
        if (overlapLimit > 0) {
            for (int side = 0; side < 2; side++) {
                final boolean left = side == 0 ? preferLeft : !preferLeft;
                final float x = left ? bubbleLeft - fullSize + overlapLimit : bubbleRight - overlapLimit;
                if (x >= edge && x + fullSize <= cellWidth - edge) {
                    placeBeside(out, x, fullSize, bubbleBottom, reactionsHeight, minTop, cellHeight, cellWidth, edge);
                    return 2;
                }
            }
        }

        // Tier 3: shrink the button while keeping a 2dp gap on the chosen side.
        // Requiring both the gap and the floor guarantees buttonSize >= 24dp and gap >= 2dp;
        // otherwise the cell falls through to tier 4 instead of drawing a sub-24dp button.
        for (int side = 0; side < 2; side++) {
            final boolean left = side == 0 ? preferLeft : !preferLeft;
            final float available = left ? bubbleLeft - edge : cellWidth - edge - bubbleRight;
            if (available >= minSize + edge) {
                final float buttonSize = Math.min(fullSize, available - edge);
                final float x = left ? bubbleLeft - edge - buttonSize : bubbleRight + edge;
                placeBeside(out, x, buttonSize, bubbleBottom, reactionsHeight, minTop, cellHeight, cellWidth, edge);
                return 3;
            }
        }

        // Tier 4: no room beside the bubble, put it above or below it, aligned to its edge.
        final float buttonSize = fullSize;
        float x = preferLeft ? bubbleLeft : bubbleRight - buttonSize;
        final float above = bubbleTop - buttonSize - AndroidUtilities.dp(4);
        final float below = bubbleBottom + Math.max(AndroidUtilities.dp(4), reactionsHeight + AndroidUtilities.dp(4));
        float y;
        if (above >= minTop) {
            y = above;
        } else if (below + buttonSize <= cellHeight - edge) {
            y = below;
        } else {
            y = Math.max(minTop, Math.min(above, cellHeight - buttonSize - edge));
        }
        x = Math.max(edge, Math.min(x, cellWidth - buttonSize - edge));
        y = Math.max(minTop, Math.min(y, cellHeight - buttonSize - edge));
        out.set(x, y, x + buttonSize, y + buttonSize);
        return 4;
    }

    private static void placeBeside(android.graphics.RectF out, float x, float buttonSize,
                                    float bubbleBottom, float reactionsHeight, float minTop,
                                    int cellHeight, float cellWidth, float edge) {
        float y = bubbleBottom - buttonSize - (buttonSize * 9f / 32f) - Math.max(0, reactionsHeight);
        y = Math.max(minTop, Math.min(y, cellHeight - buttonSize - edge));
        x = Math.max(edge, Math.min(x, cellWidth - buttonSize - edge));
        out.set(x, y, x + buttonSize, y + buttonSize);
    }

    /** Physical left edge of the bubble in pixels; caller supplies px cell/bubble widths. */
    public static float bubbleLeft(boolean outgoing, float cellWidth, float bubbleWidth, boolean avatarVisible) {
        return outgoing
                ? cellWidth - AndroidUtilities.dp(12) - bubbleWidth
                : AndroidUtilities.dp(12) + (avatarVisible ? AndroidUtilities.dp(52) : 0);
    }

    public static boolean isInsideButton(android.graphics.RectF bounds, float x, float y, float slop) {
        return bounds != null && !bounds.isEmpty()
                && x >= bounds.left - slop
                && x <= bounds.right + slop
                && y >= bounds.top - slop
                && y <= bounds.bottom + slop;
    }

    /**
     * Cuts the part of a touch target that overlaps a blocking button. The cut follows the axis with
     * the smaller overlap, and the target keeps the side its centre is on, so the remaining rect is
     * a contiguous strip of the original instead of an L-shape. Empty results collapse to a
     * canonical empty rect.
     */
    public static void clipTouchRect(android.graphics.RectF hit, android.graphics.RectF blocker) {
        if (hit == null || blocker == null || hit.isEmpty() || blocker.isEmpty()) return;
        final float overlapX = Math.min(hit.right, blocker.right) - Math.max(hit.left, blocker.left);
        final float overlapY = Math.min(hit.bottom, blocker.bottom) - Math.max(hit.top, blocker.top);
        if (overlapX <= 0f || overlapY <= 0f) return;
        if (overlapX <= overlapY) {
            if (hit.centerX() < blocker.centerX()) {
                hit.right = Math.min(hit.right, blocker.left);
            } else {
                hit.left = Math.max(hit.left, blocker.right);
            }
        } else if (hit.centerY() < blocker.centerY()) {
            hit.bottom = Math.min(hit.bottom, blocker.top);
        } else {
            hit.top = Math.max(hit.top, blocker.bottom);
        }
        if (hit.isEmpty()) hit.setEmpty();
    }

    public static boolean isSecretChatMessage(MessageObject message) {
        return message != null && DialogObject.isEncryptedDialog(message.getDialogId());
    }

    public static boolean canSummarize(MessageObject message, HgramShortMessageMerger.Chain chain) {
        return canSummarize(message, chain, true);
    }

    public static boolean canSummarize(MessageObject message, HgramShortMessageMerger.Chain chain, boolean privateChat) {
        if (message == null || message.messageOwner == null || !privateChat || isSecretChatMessage(message)) {
            return false;
        }
        final String source = chain != null
                ? chain.text
                : (!TextUtils.isEmpty(message.messageOwner.message)
                ? message.messageOwner.message
                : (message.caption != null ? message.caption.toString() : null));
        return chain != null ? isEligibleChain(chain) : isEligibleText(source);
    }

    public static boolean acceptsModel(boolean inputKnown, boolean inputHasText,
                                       boolean outputKnown, boolean outputTextOnly,
                                       String modelId) {
        if (inputKnown && !inputHasText) return false;
        if (outputKnown) return outputTextOnly;
        return !hasNonTextModelName(modelId);
    }

    private static boolean hasNonTextModelName(String modelId) {
        if (TextUtils.isEmpty(modelId)) return false;
        final String id = modelId.toLowerCase(java.util.Locale.ROOT);
        for (String part : NON_TEXT_MODEL_NAME_PARTS) {
            if (id.contains(part)) return true;
        }
        return false;
    }

    public static boolean isEligibleChain(HgramShortMessageMerger.Chain chain) {
        if (!isEnabledHere() || chain == null) return false;
        return !TextUtils.isEmpty(chain.text);
    }

    public static boolean isEligibleText(String text) {
        return isEnabledHere() && !TextUtils.isEmpty(text)
                && wordCount(text) >= HgramConfig.getSummarizerMinWords();
    }

    private static boolean isEnabledHere() {
        return HgramConfig.isSummarizerEnabled()
                && !HgramConfig.isAntiPeekActive()
                && !HgramAntiPeek.isActive()
                && !HgramSecretSpace.isActive();
    }

    public static int wordCount(String text) {
        if (text == null) return 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final boolean letter = Character.isLetterOrDigit(c) || c == '_';
            if (letter && !inWord) words++;
            inWord = letter;
        }
        return words;
    }

    /** Rough number of summaries one million prompt+completion tokens buys for the given script. */
    public static int estimateSummariesPerMillion(int minWords, String prompt, java.util.Locale locale) {
        final int words = Math.max(1, minWords);
        final java.util.Locale effective = locale != null ? locale : java.util.Locale.getDefault();
        final String language = effective.getLanguage();
        final double charsPerToken;
        final double tokensPerWord;
        if ("ru".equals(language) || "uk".equals(language) || "be".equals(language)
                || "bg".equals(language) || "sr".equals(language) || "mk".equals(language)) {
            charsPerToken = 3.0d;
            tokensPerWord = 1.6d;
        } else if (isLatinScript(effective)) {
            charsPerToken = 3.8d;
            tokensPerWord = 1.3d;
        } else {
            charsPerToken = 3.0d;
            tokensPerWord = 1.8d;
        }
        final int promptChars = prompt == null ? 0 : prompt.length();
        final int systemTokens = (int) Math.ceil(promptChars / charsPerToken);
        final int messageTokens = (int) (tokensPerWord * words) + 5;
        final int outputTokens = 200;
        final int perRequest = systemTokens + messageTokens + outputTokens;
        final int perMillion = Math.max(1, 1_000_000 / perRequest);
        return Math.max(10, (perMillion / 10) * 10);
    }

    private static boolean isLatinScript(java.util.Locale locale) {
        final String display = locale.getDisplayLanguage(locale);
        if (TextUtils.isEmpty(display)) return false;
        return Character.UnicodeScript.of(display.codePointAt(0)) == Character.UnicodeScript.LATIN;
    }

    public static String buildRequestText(MessageObject message, HgramShortMessageMerger.Chain chain) {
        final StringBuilder out = new StringBuilder();
        if (chain != null) {
            final String[] lines = chain.text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                final String prefix = (i > 0 ? "\n" : "") + "[" + (i + 1) + "] ";
                if (out.length() + prefix.length() >= MAX_REQUEST_CHARS) break;
                out.append(prefix);
                final int budget = Math.min(MAX_MESSAGE_CHARS, MAX_REQUEST_CHARS - out.length());
                if (budget <= 0) break;
                out.append(truncate(lines[i], budget));
            }
        } else if (message != null && message.messageOwner != null) {
            if (!TextUtils.isEmpty(message.messageOwner.message)) {
                out.append(truncate(message.messageOwner.message, MAX_MESSAGE_CHARS));
            } else if (message.caption != null) {
                out.append(truncate(message.caption.toString(), MAX_MESSAGE_CHARS));
            }
        }
        return truncate(out.toString(), MAX_REQUEST_CHARS);
    }

    private static String truncate(String text, int max) {
        if (text == null || max <= 0) return "";
        if (text.length() <= max) return text;
        if (max == 1) return "…";
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return end <= 0 ? "…" : text.substring(0, end) + "…";
    }

    private static RequestKey requestKey(MessageObject m) {
        return new RequestKey(m.currentAccount, m.getDialogId(), m.getId());
    }

    /** A summary belongs to the chain anchor, never to whichever cell happens to render it. */
    private static RequestKey anchorKey(MessageObject message, HgramShortMessageMerger.Chain chain) {
        if (chain == null || chain.anchorId == 0) {
            return requestKey(message);
        }
        return new RequestKey(message.currentAccount, message.getDialogId(), chain.anchorId);
    }

    private static FingerprintEntry entryOf(MessageObject message, HgramShortMessageMerger.Chain chain) {
        if (message == null || message.messageOwner == null) return null;
        return fingerprints.get(anchorKey(message, chain));
    }

    /** Test hook: how many summaries this process still has a verifiable source fingerprint for. */
    public static int cachedSummaryCount() {
        return fingerprints.size();
    }

    private static SummaryKey summaryKey(TLRPC.Message owner) {
        return owner == null ? null : new SummaryKey(owner.dialog_id, owner.id);
    }

    /** True when the summary attached to this owner was produced locally by this process. */
    public static boolean isLocalSummaryMessage(TLRPC.Message owner) {
        return owner != null && localSummaries.contains(summaryKey(owner));
    }

    /** True when an open, locally produced summary is the one currently shown for the message. */
    public static boolean isLocalSummaryOpen(MessageObject message) {
        return message != null && message.messageOwner != null
                && message.messageOwner.summarizedOpen
                && isLocalSummaryMessage(message.messageOwner);
    }

    private static void clearSummary(TLRPC.Message owner) {
        if (owner == null) return;
        localSummaries.remove(summaryKey(owner));
        owner.summaryText = null;
        owner.summarizedOpen = false;
        owner.translatedSummaryText = null;
        owner.translatedSummaryLanguage = null;
    }

    public static boolean isInFlight(MessageObject message) {
        return message != null && message.messageOwner != null && inFlight.contains(requestKey(message));
    }

    /**
     * Deterministic fingerprint of the text a summary was produced from. Chain read counters are
     * intentionally excluded: a receipt must not invalidate a cached summary.
     */
    public static String sourceFingerprint(MessageObject message, HgramShortMessageMerger.Chain chain) {
        if (chain != null) {
            final StringBuilder out = new StringBuilder("hgram:chain:");
            out.append(chain.anchorId).append('|');
            for (int i = 0; i < chain.ids.size(); i++) {
                if (i > 0) out.append(',');
                out.append(chain.ids.get(i));
            }
            final String text = chain.text == null ? "" : chain.text;
            return out.append('|').append(text.length()).append('|').append(digest(text)).toString();
        }
        if (message == null || message.messageOwner == null) return null;
        final String text = sourceText(message);
        return "hgram:message:" + message.getId() + '|' + text.length() + '|' + digest(text);
    }

    /** SHA-256 hex of the source text; hashCode is a last-resort fallback that never runs in practice. */
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

    private static String sourceText(MessageObject message) {
        if (!TextUtils.isEmpty(message.messageOwner.message)) {
            return message.messageOwner.message;
        }
        final TLRPC.MessageMedia media = message.messageOwner.media;
        return media != null && !TextUtils.isEmpty(media.captionLegacy) ? media.captionLegacy : "";
    }

    public static boolean isSummaryFresh(MessageObject message, HgramShortMessageMerger.Chain chain) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (message.messageOwner.summaryText == null) {
            return false;
        }
        final FingerprintEntry entry = entryOf(message, chain);
        if (entry == null) {
            // Unknown source. A single message may carry a server-side summary, so keep it;
            // a chain summary is always ours, and without an entry its source is unverifiable.
            return chain == null;
        }
        return entry.fingerprint != null && entry.fingerprint.equals(sourceFingerprint(message, chain));
    }

    /**
     * Drops a cached summary whose source no longer matches. The passed owner is cleared, not the
     * recycled cell; server-side single-message summaries are never touched.
     *
     * @return true when a summary was actually dropped and the caller must refresh its text.
     */
    public static boolean invalidateStaleSummary(MessageObject message, HgramShortMessageMerger.Chain chain) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        final FingerprintEntry entry = entryOf(message, chain);
        if (entry == null) {
            if (chain != null && message.messageOwner.summaryText != null) {
                // A chain summary without a process-local entry came from a previous process and
                // cannot be trusted; a single-message summary may be server-side, so keep it.
                clearSummary(message.messageOwner);
                message.summarized = false;
                return true;
            }
            return false;
        }
        if (message.messageOwner.summaryText == null) {
            return false;
        }
        if (entry.fingerprint != null && entry.fingerprint.equals(sourceFingerprint(message, chain))) {
            return false;
        }
        clearSummary(message.messageOwner);
        message.summarized = false;
        fingerprints.remove(anchorKey(message, chain));
        return true;
    }

    /** Remembers which source the currently displayed chain was built from. */
    public static void rememberChain(MessageObject anchor, HgramShortMessageMerger.Chain chain) {
        if (anchor == null || anchor.messageOwner == null || chain == null) return;
        activeChains.put(anchorKey(anchor, chain), sourceFingerprint(anchor, chain));
    }

    /** The chain is gone (regrouped, merge disabled, cell rebound): drop its summary with it. */
    public static void onChainDissolved(int account, long dialogId, int anchorId, MessageObject anchor) {
        if (anchorId == 0) return;
        final RequestKey key = new RequestKey(account, dialogId, anchorId);
        activeChains.remove(key);
        fingerprints.remove(key);
        if (anchor != null && anchor.messageOwner != null && isLocalSummaryMessage(anchor.messageOwner)) {
            clearSummary(anchor.messageOwner);
            anchor.summarized = false;
            anchor.updateTranslation(true);
        }
    }

    /** True when this process produced the summary currently shown for the message/chain. */
    public static boolean hasLocalSummary(MessageObject message, HgramShortMessageMerger.Chain chain) {
        return message != null && message.messageOwner != null
                && message.messageOwner.summarizedOpen
                && message.messageOwner.summaryText != null
                && isLocalSummaryMessage(message.messageOwner);
    }

    /**
     * Closes an already visible local summary. Deliberately bypasses the toggle threshold and the
     * API key/model checks: closing never needs a request and never touches the network.
     */
    public static boolean closeLocal(MessageObject message, HgramShortMessageMerger.Chain chain) {
        if (message == null || message.messageOwner == null) return false;
        if (!isLocalSummaryMessage(message.messageOwner)
                || !message.messageOwner.summarizedOpen
                || message.messageOwner.summaryText == null) return false;
        close(message);
        message.summarized = false;
        return true;
    }

    /** Toggles locally produced summary; network work only starts on an explicit user tap. */
    public static boolean toggle(MessageObject message, HgramShortMessageMerger.Chain chain, Runnable onUpdated) {
        if (message == null || message.messageOwner == null || onUpdated == null) return false;
        if (isSecretChatMessage(message)) return false;
        if (!DialogObject.isUserDialog(message.getDialogId())) return false;
        final String key = HgramConfig.getSummarizerKey();
        final String model = HgramConfig.getSummarizerModel();
        if (message.messageOwner.summarizedOpen && message.messageOwner.summaryText != null) {
            if (isSummaryFresh(message, chain)) {
                close(message);
                onUpdated.run();
                return true;
            }
            invalidateStaleSummary(message, chain);
        }
        if (!message.messageOwner.summarizedOpen && message.messageOwner.summaryText != null) {
            if (isSummaryFresh(message, chain)) {
                apply(message, message.messageOwner.summaryText.text, chain);
                onUpdated.run();
                return true;
            }
            invalidateStaleSummary(message, chain);
        }
        if (isInFlight(message)) {
            return true;
        }
        if (TextUtils.isEmpty(key)) {
            toast("HgramSummarizerNoKey");
            return false;
        }
        if (TextUtils.isEmpty(model)) {
            toast("HgramSummarizerNoModel");
            return false;
        }
        final RequestKey scopedKey = requestKey(message);
        if (!inFlight.add(scopedKey)) {
            return true;
        }
        // Capture the source before the network call: the cell may be rebound while we wait.
        final String requestFingerprint = sourceFingerprint(message, chain);
        try {
            final String userPrompt = buildRequestText(message, chain);
            final String systemPrompt = truncate(HgramConfig.getSummarizerSystemPrompt(), MAX_SYSTEM_PROMPT_CHARS);
            new Thread(() -> {
                String result = null;
                String errorKind = null;
                try {
                    result = LLMTranslator.INSTANCE.completeBlocking(key, model, systemPrompt, userPrompt);
                } catch (Throwable t) {
                    errorKind = classifyError(t);
                }
                final String finalResult = result;
                final String finalErrorKind = errorKind;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (TextUtils.isEmpty(finalResult)) {
                            toast(errorMessage(finalErrorKind));
                        } else {
                            applyIfFresh(message, finalResult, chain, requestFingerprint);
                        }
                        onUpdated.run();
                    } finally {
                        inFlight.remove(scopedKey);
                    }
                });
            }, "HgramSummarizer").start();
        } catch (Throwable t) {
            inFlight.remove(scopedKey);
            return false;
        }
        return true;
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

    private static void apply(MessageObject message, String summary) {
        apply(message, summary, null);
    }

    public static void apply(MessageObject message, String summary, HgramShortMessageMerger.Chain chain) {
        final TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
        text.text = summary;
        text.entities = new java.util.ArrayList<>();
        message.messageOwner.summaryText = text;
        message.messageOwner.summarizedOpen = true;
        message.summarized = true;
        fingerprints.put(anchorKey(message, chain),
                new FingerprintEntry(sourceFingerprint(message, chain), summary));
        localSummaries.add(summaryKey(message.messageOwner));
        message.applyNewText(summary);
        refreshDisplayedText(message);
    }

    /**
     * Applies a network result only while the source it was requested for is still the source on
     * screen: a reused cell or a regrouped chain must not show someone else's summary.
     */
    private static boolean applyIfFresh(MessageObject message, String summary,
                                        HgramShortMessageMerger.Chain chain, String expectedFingerprint) {
        if (message == null || message.messageOwner == null || summary == null) {
            return false;
        }
        final String current = sourceFingerprint(message, chain);
        if (expectedFingerprint == null || current == null || !current.equals(expectedFingerprint)) {
            return false;
        }
        if (chain != null) {
            final String active = activeChains.get(anchorKey(message, chain));
            if (active == null || !active.equals(expectedFingerprint)) {
                return false;
            }
        }
        apply(message, summary, chain);
        return true;
    }

    private static void close(MessageObject message) {
        localSummaries.remove(summaryKey(message.messageOwner));
        message.messageOwner.summarizedOpen = false;
        message.summarized = false;
        message.applyNewText(sourceText(message));
        refreshDisplayedText(message);
    }

    private static void refreshDisplayedText(MessageObject message) {
        if (message.type == MessageObject.TYPE_ARTICLE) {
            message.generateLayout(null);
        } else {
            message.generateCaption();
        }
    }
}
