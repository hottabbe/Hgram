package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HgramTextSemanticsTest {
    @Test
    public void lineStartIsNotMistakenForAProperName() {
        HgramTextSemantics.Fingerprint fingerprint = HgramTextSemantics.fingerprint(
                "В городе открыли новый маршрут для жителей\nАвтобусы доставят пассажиров до центра города");
        assertNotNull(fingerprint);
        assertFalse(fingerprint.entities.contains("автобусы"));
    }

    @Test
    public void capitalizedNameInsideSentenceIsAnEntity() {
        HgramTextSemantics.Fingerprint fingerprint = HgramTextSemantics.fingerprint(
                "Представитель компании Алиса рассказала про новый маршрут для жителей города и пассажиров");
        assertNotNull(fingerprint);
        assertTrue(fingerprint.entities.contains("алиса"));
    }
}
