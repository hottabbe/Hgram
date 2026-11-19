package xyz.nextalone.nagram.hgram;

import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import tw.nekomimi.nekogram.transtale.source.LLMTranslator;

/** Local OpenRouter summarization for private chats. */
public final class HgramSummarizer {

    public static final String DEFAULT_PROMPT =
            "Ты — помощник, который кратко пересказывает личные сообщения.\n"
                    + "Верни только суть: 2–4 предложения, без вступлений и оценок, без домыслов.\n"
                    + "Если сообщений несколько, объедини их в один связный пересказ.\n"
                    + "Сохраняй имена, числа, даты и договорённости. Язык ответа — язык исходного текста.";

    private static final java.util.Set<Integer> inFlight = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final int MAX_REQUEST_CHARS = 8000;
    private static final java.util.Set<Integer> loggedSummarizeCalls = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
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
                0f, layoutHeight + deltaBottom, reactionsHeight, cellHeight, size, inset, minTop);
    }

    public static int summarizeBoundsAdaptive(android.graphics.RectF out, boolean outgoing,
                                       float cellWidth, float bubbleWidth, boolean avatarVisible,
                                       float bubbleTop, float bubbleBottom, float reactionsHeight,
                                               int cellHeight, int size, int gap, float minTop) {
        out.setEmpty();
        final float width = Math.max(AndroidUtilities.dp(40), bubbleWidth);
        final float bubbleLeft = bubbleLeft(outgoing, cellWidth, width, avatarVisible);
        final float bubbleRight = bubbleLeft + width;
        final float edge = AndroidUtilities.dp(2);
        final float minSize = AndroidUtilities.dp(24);
        final float fullSize = Math.max(size, AndroidUtilities.dp(24));
        final float overlapLimit = AndroidUtilities.dp(8);
        final float available = outgoing ? bubbleLeft - edge : cellWidth - edge - bubbleRight;
        float x;

        // Tier 1: full size beside the bubble, reducing the gap from 8dp to 2dp.
        final float[] gaps = {AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(2)};
        for (float candidateGap : gaps) {
            if (available >= fullSize + candidateGap) {
                x = outgoing ? bubbleLeft - candidateGap - fullSize : bubbleRight + candidateGap;
                placeBeside(out, x, fullSize, bubbleBottom, reactionsHeight, minTop, cellHeight, cellWidth, edge);
                return 1;
            }
        }

        // Tier 2: full size may overlap the bubble by at most 8dp.
        x = outgoing ? bubbleLeft - fullSize + overlapLimit : bubbleRight - overlapLimit;
        if (x >= edge && x + fullSize <= cellWidth - edge) {
            placeBeside(out, x, fullSize, bubbleBottom, reactionsHeight, minTop, cellHeight, cellWidth, edge);
            return 2;
        }

        // Tier 3: shrink the button down to 24dp while keeping a 2dp gap.
        if (available >= minSize) {
            final float buttonSize = Math.min(fullSize,
                    Math.max(minSize, available - AndroidUtilities.dp(2)));
            x = outgoing
                    ? bubbleLeft - AndroidUtilities.dp(2) - buttonSize
                    : bubbleRight + AndroidUtilities.dp(2);
            placeBeside(out, x, buttonSize, bubbleBottom, reactionsHeight, minTop, cellHeight, cellWidth, edge);
            return 3;
        }

        // Tier 4: no room beside the bubble, put it above or below it, aligned to its edge.
        final float buttonSize = fullSize;
        x = outgoing ? bubbleLeft : bubbleRight - buttonSize;
        final float above = bubbleTop - buttonSize - AndroidUtilities.dp(4);
        final float below = bubbleBottom + AndroidUtilities.dp(4);
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

    public static boolean canSummarize(MessageObject message, HgramShortMessageMerger.Chain chain) {
        if (message == null || message.messageOwner == null) return false;
        final String source = chain != null
                ? chain.text
                : (!TextUtils.isEmpty(message.messageOwner.message)
                ? message.messageOwner.message
                : (message.caption != null ? message.caption.toString() : null));
        final boolean result = chain != null ? isEligibleChain(chain) : isEligibleText(source);
        final int msgId = message.getId();
        if (loggedSummarizeCalls.add(msgId)) {
            Log.d("HGRAM_SUM", "canSummarize msgId=" + msgId
                    + " chain=" + (chain != null)
                    + " isOut=" + message.isOutOwner()
                    + " textLen=" + (source == null ? -1 : source.length())
                    + " words=" + wordCount(source)
                    + " minWords=" + HgramConfig.getSummarizerMinWords()
                    + " enabled=" + HgramConfig.isSummarizerEnabled()
                    + " antiPeekCfg=" + HgramConfig.isAntiPeekActive()
                    + " antiPeekActive=" + HgramAntiPeek.isActive()
                    + " secretSpace=" + HgramSecretSpace.isActive()
                    + " result=" + result);
        }
        return result;
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
                if (i > 0) out.append('\n');
                out.append('[').append(i + 1).append("] ").append(truncate(lines[i], MAX_MESSAGE_CHARS));
            }
        } else if (message != null && message.messageOwner != null) {
            out.append(truncate(message.messageOwner.message, MAX_MESSAGE_CHARS));
        }
        return truncate(out.toString(), MAX_REQUEST_CHARS);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public static boolean isInFlight(MessageObject message) {
        return message != null && inFlight.contains(message.getId());
    }

    /** Toggles locally produced summary; network work only starts on an explicit user tap. */
    public static boolean toggle(MessageObject message, HgramShortMessageMerger.Chain chain, Runnable onUpdated) {
        if (message == null || message.messageOwner == null || onUpdated == null) return false;
        final String key = HgramConfig.getSummarizerKey();
        final String model = HgramConfig.getSummarizerModel();
        // Diagnostic only: mirrors canSummarize's source resolution without touching its one-shot log set.
        final String source = chain != null ? chain.text
                : (!TextUtils.isEmpty(message.messageOwner.message)
                ? message.messageOwner.message
                : (message.caption != null ? message.caption.toString() : null));
        Log.d("HGRAM_SUM", "toggle start msgId=" + message.getId()
                + " chain=" + (chain != null)
                + " summarizedOpen=" + message.messageOwner.summarizedOpen
                + " summary=" + (message.messageOwner.summaryText != null)
                + " inFlight=" + isInFlight(message)
                + " can=" + (chain != null ? isEligibleChain(chain) : isEligibleText(source))
                + " enabled=" + HgramConfig.isSummarizerEnabled()
                + " key=" + (TextUtils.isEmpty(key) ? "empty" : "set")
                + " model=" + (TextUtils.isEmpty(model) ? "empty" : "set"));
        if (message.messageOwner.summarizedOpen && message.messageOwner.summaryText != null) {
            Log.d("HGRAM_SUM", "toggle reason=close");
            close(message);
            onUpdated.run();
            return true;
        }
        if (!message.messageOwner.summarizedOpen && message.messageOwner.summaryText != null) {
            Log.d("HGRAM_SUM", "toggle reason=open");
            apply(message, message.messageOwner.summaryText.text);
            onUpdated.run();
            return true;
        }
        if (isInFlight(message)) {
            Log.d("HGRAM_SUM", "toggle reason=inflight");
            return true;
        }
        if (TextUtils.isEmpty(key)) {
            Log.d("HGRAM_SUM", "toggle reason=nokey");
            toast("HgramSummarizerNoKey");
            return false;
        }
        if (TextUtils.isEmpty(model)) {
            Log.d("HGRAM_SUM", "toggle reason=nomodel");
            toast("HgramSummarizerNoModel");
            return false;
        }
        final int id = message.getId();
        inFlight.add(id);
        final String userPrompt = buildRequestText(message, chain);
        final String systemPrompt = HgramConfig.getSummarizerSystemPrompt();
        new Thread(() -> {
            String result = null;
            String error = null;
            try {
                result = LLMTranslator.INSTANCE.completeBlocking(key, model, systemPrompt, userPrompt);
            } catch (Throwable t) {
                error = t.getMessage();
            } finally {
                inFlight.remove(id);
                Log.d("HGRAM_SUM", "toggle done msgId=" + id + " inFlight=" + isInFlight(message) + " result=" + (result != null) + " error=" + error);
            }
            final String finalResult = result;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (TextUtils.isEmpty(finalResult)) {
                    toast(errorMessage(finalError));
                } else {
                    apply(message, finalResult);
                }
                onUpdated.run();
            });
        }, "HgramSummarizer").start();
        return true;
    }

    private static String errorMessage(String error) {
        final String value = error == null ? "" : error.toLowerCase(java.util.Locale.US);
        if (value.contains("429") || value.contains("rate limit")) return "HgramSummarizerLimit";
        if (value.contains("401") || value.contains("403") || value.contains("api key")) return "HgramSummarizerNoKey";
        if (value.contains("model")) return "HgramSummarizerNoModel";
        return "HgramSummarizerNetwork";
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
        final TLRPC.TL_textWithEntities text = new TLRPC.TL_textWithEntities();
        text.text = summary;
        text.entities = new java.util.ArrayList<>();
        message.messageOwner.summaryText = text;
        message.messageOwner.summarizedOpen = true;
        message.summarized = true;
        message.applyNewText(summary);
    }

    private static void close(MessageObject message) {
        message.messageOwner.summarizedOpen = false;
        message.summarized = false;
        message.applyNewText(message.messageOwner.message);
    }
}
