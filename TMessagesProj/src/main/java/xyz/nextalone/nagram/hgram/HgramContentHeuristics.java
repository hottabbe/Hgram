package xyz.nextalone.nagram.hgram;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative local heuristics for promotional and clickbait posts. */
public final class HgramContentHeuristics {

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(https?://|www\\.)\\S+");
    private static final Pattern TELEGRAM_LINK = Pattern.compile("(?i)(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/\\S+");
    private static final Pattern EXPLICIT_AD_PATTERN = Pattern.compile("(?iu)(?:^|[\\s#])(?:реклама|на правах рекламы|рекламный\\s+пост|спонсорский\\s+пост|партн[её]рский\\s+материал|sponsored|advertisement|affiliate\\s+link|promo\\s+code|ads?)(?:$|[\\s.!?,:;])");

    private HgramContentHeuristics() {
    }

    public static Reason classify(String value) {
        return classify(value, false);
    }

    public static Reason classify(String value, boolean hasEntityLink) {
        if (value == null || value.trim().isEmpty()) {
            return Reason.NONE;
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String keyword : customKeywords()) {
            if (text.contains(keyword)) {
                return Reason.ADVERTISEMENT;
            }
        }
        if (EXPLICIT_AD_PATTERN.matcher(text).find()) {
            return Reason.ADVERTISEMENT;
        }
        if (value.length() < 40) {
            return Reason.NONE;
        }
        boolean hasLink = hasEntityLink || URL_PATTERN.matcher(text).find() || TELEGRAM_LINK.matcher(text).find();

        boolean promoOffer = containsAny(text, "промокод", "промо код", "скидк", "распродажа",
                "купон", "фриспин", "free spin", "бонус за регистрацию", "первый депозит", "sale", "discount");
        boolean purchaseIntent = containsAny(text, "закажи", "заказать", "заказыва", "купить", "покупай", "покупайте", "приобрести",
                "оформить заказ", "записаться", "запишись", "регистрируйся", "переходи по ссылке", "жми на ссылку",
                "подробнее по ссылке", "link in bio", "узнать условия", "оставить заявку");
        boolean salesLanguage = containsAny(text, "доставка", "каталог", "стоимость", "тариф", "в наличии",
                "цена от", "цены от", "за покупку", "для новых клиентов", "товары", "услуги", "продукц", "артикул");
        boolean gambling = containsAny(text, "казино", "casino", "ставки на спорт", "букмекер", "букмекерская",
                "1win", "mostbet", "melbet", "vavada", "pin-up", "pinup", "азино", "слоты", "фриспин", "free spin");
        boolean giveaway = containsAny(text, "розыгрыш", "разыгрыва", "giveaway", "конкурс с приз", "призовой фонд", "выиграй приз");
        boolean giftOffer = text.contains("подар") && (hasLink || salesLanguage || purchaseIntent);
        boolean channelPromotion = containsAny(text, "подпишись", "подписывайтесь", "подписаться на канал",
                "переходи в канал", "перейти в канал", "наш канал", "телеграм-канал", "telegram-канал");
        boolean eventPromotion = containsAny(text, "вебинар", "мастер-класс", "мастер класс", "интенсив", "обучающий курс")
                && containsAny(text, "сегодня", "присоединяйся", "регистрируйся", "проведет", "проведёт", "запись", "участвуй");

        // Treat a link alone, a discount mention alone, or financial/news vocabulary as normal content.
        // Require an offer plus an action/link, while keeping obvious gambling and giveaway promos.
        if ((hasLink && (promoOffer || salesLanguage || channelPromotion) && (purchaseIntent || channelPromotion))
                || (hasLink && salesLanguage && purchaseIntent)
                || (promoOffer && purchaseIntent)
                || (gambling && (hasLink || purchaseIntent || promoOffer))
                || (giveaway && hasLink && (purchaseIntent || channelPromotion))
                || giftOffer
                || (eventPromotion && hasLink)) {
            return Reason.ADVERTISEMENT;
        }

        int clickbait = 0;
        clickbait += weight(text, 3, "вы не поверите", "шокирующая правда", "все скрывают", "никогда не делайте", "этот секрет", "подробности в конце", "ты должен это увидеть", "you won't believe", "shocking truth", "they don't want you to know", "what happened next", "doctors hate");
        clickbait += weight(text, 1, "смотри что", "узнай почему", "вся правда", "подробности здесь", "watch what", "find out why", "это изменит", "важно знать");
        if (hasLink && (text.contains("смотри") || text.contains("узнай") || text.contains("подробнее"))) {
            clickbait++;
        }
        if (hasExcessivePunctuation(value)) {
            clickbait++;
        }
        if (hasAllCapsHeadline(value)) {
            clickbait++;
        }
        return clickbait >= 3 ? Reason.CLICKBAIT : Reason.NONE;
    }

    private static volatile String cachedKeywordsRaw;
    private static volatile String[] cachedKeywords;

    private static String[] customKeywords() {
        final String raw = HgramConfig.getCustomFeedKeywords();
        final String[] cached = cachedKeywords;
        if (cached != null && raw.equals(cachedKeywordsRaw)) {
            return cached;
        }
        final String[] parts = raw.split("[\\n,;]");
        int count = 0;
        for (int i = 0; i < parts.length; i++) {
            final String value = parts[i].trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                parts[count++] = value;
            }
        }
        final String[] result = new String[count];
        System.arraycopy(parts, 0, result, 0, count);
        cachedKeywords = result;
        cachedKeywordsRaw = raw;
        return result;
    }

    private static int weight(String text, int points, String... phrases) {
        int score = 0;
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                score += points;
            }
        }
        return score;
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExcessivePunctuation(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '!' || c == '?' || c == '‼' || c == '⁉') {
                count++;
            }
        }
        return count >= 4;
    }

    private static boolean hasAllCapsHeadline(String value) {
        int newline = value.indexOf('\n');
        String headline = newline >= 0 ? value.substring(0, newline) : value.substring(0, Math.min(value.length(), 100));
        int letters = 0;
        int uppercase = 0;
        for (int i = 0; i < headline.length(); i++) {
            char c = headline.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    uppercase++;
                }
            }
        }
        return letters >= 12 && uppercase >= 10 && uppercase / (float) letters >= 0.75f;
    }

    public enum Reason {
        NONE,
        ADVERTISEMENT,
        CLICKBAIT
    }
}
