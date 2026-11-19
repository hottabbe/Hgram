package xyz.nextalone.nagram.hgram;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight local semantic fingerprint for news posts: proper nouns/numbers, canonicalized
 * keywords, topic buckets and a SimHash fallback. No network or model files are used.
 */
public final class HgramTextSemantics {

    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+|www\\.\\S+");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{1,}");
    private static final Pattern HASHTAG = Pattern.compile("#[\\p{L}\\p{N}_]{2,}");
    private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9_]{3,}");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:[.,]\\d+)?\\b");

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все", "она",
            "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "только", "ее",
            "мне", "было", "вот", "от", "меня", "еще", "нет", "о", "из", "ему", "теперь", "когда",
            "даже", "ну", "вдруг", "ли", "если", "уже", "или", "ни", "быть", "был", "него", "до",
            "вас", "нибудь", "опять", "уж", "вам", "ведь", "там", "потом", "себя", "ничего", "ей",
            "может", "они", "тут", "где", "есть", "надо", "ней", "для", "мы", "тебя", "их", "чем",
            "была", "сам", "чтоб", "без", "будто", "чего", "раз", "тоже", "себе", "под", "будет",
            "ж", "тогда", "кто", "этот", "того", "потому", "этого", "какой", "совсем", "ним", "здесь",
            "этом", "один", "почти", "мой", "тем", "чтобы", "нее", "сейчас", "были", "куда", "зачем",
            "всех", "никогда", "можно", "при", "наконец", "два", "об", "другой", "хоть", "после",
            "над", "больше", "тот", "через", "эти", "нас", "про", "всего", "них", "какая", "много",
            "разве", "три", "эту", "моя", "впрочем", "хорошо", "свою", "этой", "перед", "иногда",
            "лучше", "чуть", "том", "нельзя", "такой", "им", "более", "всегда", "конечно", "всю",
            "между", "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "to",
            "of", "in", "on", "for", "with", "from", "by", "at", "as", "it", "this", "that", "these",
            "those", "has", "have", "had", "will", "would", "can", "could", "should", "may", "might"
    ));

    /** Maps frequent news verbs/nouns with different wording to one canonical concept. */
    private static final Map<String, String> SYNONYMS = new HashMap<>();
    static {
        put("say", "сообщил", "заявил", "рассказал", "отметил", "подчеркнул", "заявляет", "сообщает", "говорится", "пишет", "передает", "передаёт",
                "сообщила", "сообщили", "заявила", "заявили", "рассказала", "рассказали", "отметила", "отметили", "подчеркнула", "подчеркнули");
        put("report", "доклад", "отчет", "отчёт", "исследование", "анализ", "обзор");
        put("city", "город", "городе", "города", "муниципалитет", "мэрия", "администрация", "администрации", "мэрии", "чиновник", "чиновники", "чиновников", "городские", "городских");
        put("transport", "транспорт", "транспорта", "маршрут", "маршруты", "маршрутах", "маршрута", "автобус", "автобусы", "автобусов", "автобусах", "метро", "трамвай", "перевозки", "перевозок", "пассажиров", "пассажиры");
        put("authority", "власти", "властей", "правительство", "министерство", "ведомство", "департамент", "комиссия");
        put("people", "жители", "жителей", "жителям", "люди", "граждане", "население", "горожан", "горожане");
        put("new", "новый", "новые", "новая", "новое", "новых");
        put("start", "начнут", "начинают", "запустят", "запуск", "стартует", "стартуют", "введут", "заработают");
        put("work", "работать", "работа", "работы", "функционировать");
        put("month", "месяц", "месяца", "месяце", "месяцев", "сентябрь", "сентября", "сентябре", "октябрь", "октября", "ноябрь", "ноября", "декабрь", "декабря");
        put("quality", "качество", "качества", "качеству", "обслуживание", "обслуживания", "интервал", "интервалы", "интервалов", "движение", "движения");
        put("week", "неделя", "неделе", "недели", "неделю");
        put("year", "год", "года", "году", "годы", "лет");
        put("increase", "рост", "вырос", "выросла", "увеличился", "увеличилась", "подорожал", "подорожала");
        put("decrease", "снижение", "упал", "упала", "снизился", "снизилась", "подешевел", "подешевела");
        put("emergency", "пожар", "авария", "катастрофа", "чп", "взрыв", "обрушение");
        put("war", "война", "войны", "бои", "обстрел", "удар", "атака", "дрон", "дроны", "ракета");
        put("politics", "президент", "министр", "депутат", "парламент", "правительство", "выборы");
        put("economy", "экономика", "банк", "рубль", "доллар", "курс", "инфляция", "бюджет", "налог");
        put("tech", "технологии", "смартфон", "приложение", "нейросеть", "ии", "компьютер", "интернет");
        put("sport", "матч", "футбол", "хоккей", "олимпиада", "чемпионат", "турнир", "игра");
        put("weather", "погода", "снег", "дождь", "мороз", "жара", "ветер", "температура");
        put("health", "здоровье", "врачи", "больница", "вирус", "вакцина", "медицина", "заболевание");
        put("official", "официально", "официальный", "официальное", "сообщение", "заявление");
    }

    private static void put(String canonical, String... words) {
        for (String word : words) {
            SYNONYMS.put(word, canonical);
        }
    }

    private static final String[][] TOPICS = {
            {"transport", "транспорт", "маршрут", "автобус", "метро", "трамвай", "дорог", "движен", "пробк"},
            {"emergency", "пожар", "авар", "катастроф", "чп", "взрыв", "обрушен", "спасател", "пострадав"},
            {"war", "войн", "обстрел", "удар", "атак", "дрон", "ракет", "сво", "фронт", "бои"},
            {"politics", "президент", "министр", "депутат", "парламент", "выбор", "правительств", "политик"},
            {"economy", "эконом", "банк", "рубл", "доллар", "курс", "инфляц", "бюджет", "налог", "цен"},
            {"tech", "технолог", "смартфон", "приложен", "нейросет", "искусственн", "компьютер", "интернет"},
            {"sport", "матч", "футбол", "хокке", "олимпиад", "чемпионат", "турнир", "спортсмен"},
            {"weather", "погод", "снег", "дожд", "мороз", "жар", "ветер", "температур"},
            {"health", "здоров", "врач", "больниц", "вирус", "вакцин", "медицин", "заболеван"}
    };

    private HgramTextSemantics() {
    }

    public static Fingerprint fingerprint(String value) {
        if (value == null || value.length() < 48) {
            return null;
        }
        String text = Normalizer.normalize(URL.matcher(value).replaceAll(" "), Normalizer.Form.NFKC);
        Set<String> entities = new HashSet<>();
        Set<String> stems = new HashSet<>();
        Map<String, Integer> frequency = new HashMap<>();
        Set<String> topics = new HashSet<>();
        List<String> orderedStems = new ArrayList<>();

        Matcher hashtags = HASHTAG.matcher(text);
        while (hashtags.find()) {
            entities.add(hashtags.group().toLowerCase(Locale.ROOT));
        }
        Matcher mentions = MENTION.matcher(text);
        while (mentions.find()) {
            entities.add(mentions.group().toLowerCase(Locale.ROOT));
        }
        Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            if (numbers.group().length() >= 1) {
                entities.add(numbers.group());
            }
        }

        Matcher tokens = TOKEN.matcher(text);
        while (tokens.find()) {
            String raw = tokens.group();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.length() < 3 || STOP.contains(lower)) {
                continue;
            }
            boolean proper = Character.isUpperCase(raw.charAt(0)) && !isSentenceStart(text, tokens.start());
            if (proper && lower.length() >= 3) {
                entities.add(lower);
            }
            String canonical = SYNONYMS.get(lower);
            String stem = stem(canonical != null ? canonical : lower);
            if (stem.length() < 3) {
                continue;
            }
            stems.add(stem);
            orderedStems.add(stem);
            frequency.merge(stem, 1, Integer::sum);
        }
        if (stems.size() < 6) {
            return null;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (String[] topic : TOPICS) {
            for (int i = 1; i < topic.length; i++) {
                if (lowerText.contains(topic[i])) {
                    topics.add(topic[0]);
                    break;
                }
            }
        }
        long simhash = simhash(orderedStems);
        return new Fingerprint(entities, stems, frequency, topics, simhash, orderedStems.size());
    }

    public static double similarity(Fingerprint a, Fingerprint b) {
        if (a == null || b == null) {
            return 0;
        }
        int sharedEntities = intersectionSize(a.entities, b.entities);
        int sharedStems = intersectionSize(a.stems, b.stems);
        double containment = sharedStems == 0 ? 0
                : sharedStems / (double) Math.min(a.stems.size(), b.stems.size());
        double entityContainment = sharedEntities == 0 ? 0
                : sharedEntities / (double) Math.min(Math.max(1, a.entities.size()), Math.max(1, b.entities.size()));
        double cosine = cosine(a, b);
        double simhash = 1.0 - Long.bitCount(a.simhash ^ b.simhash) / 64.0;

        double score = 0.45 * containment + 0.22 * cosine + 0.13 * simhash + 0.20 * entityContainment;
        int sharedTopics = intersectionSize(a.topics, b.topics);
        if (sharedTopics != 0) {
            score += 0.05 * Math.min(2, sharedTopics);
        }
        // Avoid collapsing unrelated posts that merely share common words.
        if (sharedEntities == 0 && sharedStems < 4) {
            score *= 0.6;
        } else if (sharedStems < 3) {
            score *= 0.8;
        }
        return Math.min(1.0, score);
    }

    public static double stemContainment(Fingerprint a, Fingerprint b) {
        if (a == null || b == null || a.stems.isEmpty() || b.stems.isEmpty()) {
            return 0;
        }
        return intersectionSize(a.stems, b.stems) / (double) Math.min(a.stems.size(), b.stems.size());
    }

    private static int intersectionSize(Set<String> first, Set<String> second) {
        if (first.size() > second.size()) {
            Set<String> temp = first;
            first = second;
            second = temp;
        }
        int count = 0;
        for (String value : first) {
            if (second.contains(value)) count++;
        }
        return count;
    }

    private static double cosine(Fingerprint a, Fingerprint b) {
        double dot = 0;
        for (Map.Entry<String, Integer> entry : a.frequency.entrySet()) {
            Integer bv = b.frequency.get(entry.getKey());
            if (bv != null) {
                dot += entry.getValue() * bv;
            }
        }
        return a.squaredNorm == 0 || b.squaredNorm == 0 ? 0 : dot / Math.sqrt(a.squaredNorm * b.squaredNorm);
    }

    private static long simhash(List<String> stems) {
        int[] weights = new int[64];
        for (int i = 0; i + 2 < stems.size(); i++) {
            long hash = hash64(stems.get(i) + ' ' + stems.get(i + 1) + ' ' + stems.get(i + 2));
            for (int bit = 0; bit < 64; bit++) {
                weights[bit] += (hash & (1L << bit)) == 0 ? -1 : 1;
            }
        }
        long signature = 0;
        for (int bit = 0; bit < 64; bit++) {
            if (weights[bit] >= 0) {
                signature |= 1L << bit;
            }
        }
        return signature;
    }

    private static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        return hash ^ (hash >>> 33);
    }

    private static boolean isSentenceStart(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '.' || c == '!' || c == '?' || c == '\n' || c == ':' || c == ';';
        }
        return true;
    }

    private static String stem(String word) {
        String value = word;
        String[] suffixes = {
                "иями", "ями", "ами", "ов", "ев", "ой", "ый", "ий", "ая", "яя", "ое", "ее", "ые", "ие",
                "ого", "его", "ому", "ему", "ыми", "ими", "ах", "ях", "ам", "ям", "ов", "ев", "ия",
                "ии", "ию", "ей", "ем", "ом", "ую", "юю", "ся", "сь", "ть", "ти", "ла", "ли", "ло",
                "ны", "на", "но", "ет", "ут", "ют", "ат", "ят", "ing", "ed", "es", "s"
        };
        for (String suffix : suffixes) {
            if (value.length() - suffix.length() >= 4 && value.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        return value;
    }

    public static final class Fingerprint {
        final Set<String> entities;
        final Set<String> stems;
        final Map<String, Integer> frequency;
        final Set<String> topics;
        final long simhash;
        final int tokenCount;
        final double squaredNorm;

        Fingerprint(Set<String> entities, Set<String> stems, Map<String, Integer> frequency, Set<String> topics, long simhash, int tokenCount) {
            this.entities = entities;
            this.stems = stems;
            this.frequency = frequency;
            double norm = 0;
            for (int count : frequency.values()) {
                norm += count * count;
            }
            this.squaredNorm = norm;
            this.topics = topics;
            this.simhash = simhash;
            this.tokenCount = tokenCount;
        }
    }
}
