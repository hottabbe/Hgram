package xyz.nextalone.nagram.hgram;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramOcrDownloadSessionTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void viewerDestroyCancelsOwnedDownloadAndResumeUsesPart() throws Exception {
        final HgramOcrDownloadSession session = new HgramOcrDownloadSession();
        final long generation = session.switchTo("m:42:7");
        session.own(generation, 77);
        final File part = temporary.newFile("eng.traineddata.part");

        assertEquals(77, session.detachAndTakeOwned());
        assertEquals(0, session.detachAndTakeOwned());
        assertFalse(session.owns(77));

        session.switchTo(null);
        assertFalse("destroyed session must reject its callbacks", session.isCurrent(generation, "m:42:7"));
        assertTrue("the partial file is retained for the next Range resume", part.isFile());

        final long resumed = session.switchTo("m:42:7");
        assertTrue(session.isCurrent(resumed, "m:42:7"));
        assertFalse(session.isCurrent(generation, "m:42:7"));
    }

    @Test
    public void staleDownloadCannotFinishNewPhotoSession() {
        final HgramOcrDownloadSession session = new HgramOcrDownloadSession();
        final long first = session.switchTo("m:42:7");
        session.own(first, 11);
        assertEquals(11, session.detachAndTakeOwned());

        final long second = session.switchTo("m:42:8");
        session.own(first, 12);
        assertTrue(session.isCurrent(second, "m:42:8"));
        assertFalse(session.isCurrent(first, "m:42:7"));
        assertFalse("a stale callback cannot claim the new session", session.owns(12));
        assertFalse(session.isCurrent(first, "m:42:8"));

        session.own(second, 13);
        assertTrue(session.owns(13));
        assertFalse(session.owns(12));
    }
}
