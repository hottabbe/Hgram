package xyz.nextalone.nagram.hgram;

import java.util.Locale;

/** Immutable description of one summarizer model advertised by OpenRouter. */
public final class HgramSummarizerModel {

    public final String id;
    public final String name;
    public final Double promptPricePer1M;
    public final Double completionPricePer1M;
    public final boolean isFree;
    public final boolean textOnly;

    public HgramSummarizerModel(String id, String name, Double promptPricePer1M,
                                Double completionPricePer1M, boolean isFree, boolean textOnly) {
        this.id = id;
        this.name = name;
        this.promptPricePer1M = promptPricePer1M;
        this.completionPricePer1M = completionPricePer1M;
        this.isFree = isFree;
        this.textOnly = textOnly;
    }

    /** OpenRouter quotes USD per single token; this converts a raw string to USD per 1M tokens. */
    public static Double parsePricePerMillion(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            final double value = Double.parseDouble(raw.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) return null;
            if (value < 0d) return null;
            return value * 1_000_000d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String formatPrice(Double prompt, Double completion) {
        final Double promptPrice = normalizePrice(prompt);
        final Double completionPrice = normalizePrice(completion);
        if (promptPrice == null && completionPrice == null) return "—";
        return formatSinglePrice(promptPrice) + " / " + formatSinglePrice(completionPrice);
    }

    private static Double normalizePrice(Double value) {
        return value == null || value < 0d ? null : value;
    }

    private static String formatSinglePrice(Double value) {
        if (value == null) return "—";
        if (value < 0d) return "—";
        if (value == 0d) return "0.00";
        if (value > 0d && value < 0.01d) return String.format(Locale.US, "%.3f", value);
        return String.format(Locale.US, "%.2f", value);
    }

    /** A model is free when its id is explicitly suffixed ":free" or both published prices are exactly zero. */
    public static boolean isFreeModel(String id, Double promptPricePer1M, Double completionPricePer1M) {
        if (id != null && id.endsWith(":free")) return true;
        return promptPricePer1M != null && completionPricePer1M != null
                && promptPricePer1M == 0d && completionPricePer1M == 0d;
    }

    /** Free models first, then paid by prompt price ascending (unknown price last), then by name/id. */
    public static void sort(java.util.List<HgramSummarizerModel> models) {
        if (models == null || models.size() < 2) return;
        java.util.Collections.sort(models, (a, b) -> {
            if (a.isFree != b.isFree) return a.isFree ? -1 : 1;
            if (!a.isFree) {
                final int byPrice = comparePrices(a.promptPricePer1M, b.promptPricePer1M);
                if (byPrice != 0) return byPrice;
            }
            final int byName = displayName(a).compareToIgnoreCase(displayName(b));
            if (byName != 0) return byName;
            return safeId(a).compareTo(safeId(b));
        });
    }

    private static int comparePrices(Double left, Double right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return Double.compare(left, right);
    }

    private static String displayName(HgramSummarizerModel model) {
        final String name = model.name;
        return name == null || name.trim().isEmpty() ? safeId(model) : name.trim();
    }

    private static String safeId(HgramSummarizerModel model) {
        return model.id == null ? "" : model.id;
    }
}
