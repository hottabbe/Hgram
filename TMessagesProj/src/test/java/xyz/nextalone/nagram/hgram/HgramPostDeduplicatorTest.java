package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class HgramPostDeduplicatorTest {

    private static final String POST = "Сегодня городская комиссия представила подробный доклад о развитии общественного транспорта и новых маршрутах, которые начнут работать в следующем месяце.";

    @Test
    public void similarLongPostFromAnotherChannelFindsPreviouslySeenSource() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        index.rememberSeen(POST, -100, 10, "@first_channel", 1_000);

        HgramPostDeduplicator.Match match = index.findSimilar(POST.replace(',', '!'), -200, 2_000);

        assertNotNull(match);
        assertEquals("@first_channel", match.sourceLabel);
        assertEquals(-100, match.dialogId);
    }

    @Test
    public void paraphrasedReportWithSameTopicAndFactsFindsPreviouslySeenSource() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        index.rememberSeen(POST, -100, 10, "@first_channel", 1_000);
        String paraphrase = "Городские власти сообщили о запуске автобусных маршрутов и улучшении общественного транспорта уже в следующем месяце. "
                + "По данным комиссии, изменения затронут пассажиров центра и окраин.";

        HgramTextSemantics.Fingerprint original = HgramTextSemantics.fingerprint(POST);
        HgramTextSemantics.Fingerprint rewritten = HgramTextSemantics.fingerprint(paraphrase);
        assertNotNull("score=" + HgramTextSemantics.similarity(original, rewritten)
                        + ", containment=" + HgramTextSemantics.stemContainment(original, rewritten),
                index.findSimilar(paraphrase, -200, 2_000));
    }

    @Test
    public void doesNotCollapseUnrelatedReportFromSameTopic() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        index.rememberSeen(POST, -100, 10, "@first_channel", 1_000);
        String unrelated = "Вчера в центре города произошла авария, движение автобусов временно ограничили из-за ремонта теплотрассы. "
                + "Очевидцы сообщили о пробке на соседней улице и работе коммунальных служб.";

        assertNull(index.findSimilar(unrelated, -200, 2_000));
    }

    @Test
    public void doesNotCollapseAnotherPostFromTheSameChannel() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        index.rememberSeen(POST, -100, 10, "@first_channel", 1_000);

        assertNull(index.findSimilar(POST, -100, 2_000));
    }

    @Test
    public void forgetsPostsAfterTwoHours() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        index.rememberSeen(POST, -100, 10, "@first_channel", 1_000);

        assertNull(index.findSimilar(POST, -200, 1_000 + HgramPostDeduplicator.RETENTION_MS));
    }

    @Test
    public void preparedPostIsRememberedOnlyAfterDrawing() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        HgramPostDeduplicator.PreparedPost prepared = index.prepare(POST, -100, 1_000);
        assertNull(prepared.match);
        assertNull(index.findSimilar(POST, -200, 1_001));

        index.rememberSeen(prepared, 10, "@first_channel", 1_002);
        assertNotNull(index.findSimilar(POST, -200, 1_003));
    }

    @Test
    public void editedPostCanInvalidateItsOldFingerprint() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        index.rememberSeen(POST, -100, 10, "@first_channel", 1_000);
        assertNotNull(index.findSimilar(POST, -200, 1_001));
        index.removeSeen(-100, 10);
        assertNull(index.findSimilar(POST, -200, 1_002));
    }

    @Test
    public void shortPreparedTextIsNotRemembered() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        HgramPostDeduplicator.PreparedPost prepared = index.prepare("Короткая новость", -100, 1_000);
        index.rememberSeen(prepared, 10, "@channel", 1_001);
        assertNull(index.findSimilar("Короткая новость", -200, 1_002));
    }

    @Test
    public void ignoresShortTexts() {
        HgramPostDeduplicator index = new HgramPostDeduplicator();
        index.rememberSeen("Короткая новость", -100, 10, "@first_channel", 1_000);

        assertNull(index.findSimilar("Короткая новость", -200, 2_000));
    }
}
