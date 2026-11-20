package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramOcrProtectionTest {

    @Test
    public void ocrProtectedAvatarAndMessageUseViewerCopyPolicy() {
        assertTrue(HgramOcrProtection.isCopyProtected(true, false, false, 0, false, false, false));
        assertFalse("existing disable-flag-secure / force-copy overrides relax message noforwards",
                HgramOcrProtection.isCopyProtected(true, false, false, 0, false, false, true));

        assertTrue("protected avatar has no message object",
                HgramOcrProtection.isCopyProtected(false, true, false, 0, false, false, false));
        assertTrue("avatar protection is not relaxed by copy overrides",
                HgramOcrProtection.isCopyProtected(false, true, false, 0, false, false, true));
        assertTrue(HgramOcrProtection.isProtectedAvatar(42, true));
        assertFalse(HgramOcrProtection.isProtectedAvatar(0, true));

        assertTrue(HgramOcrProtection.isCopyProtected(false, false, true, 0, false, false, true));
        assertTrue(HgramOcrProtection.isCopyProtected(false, false, false, 3599, false, false, false));
        assertFalse(HgramOcrProtection.isCopyProtected(false, false, false, 3600, false, false, false));
        assertTrue(HgramOcrProtection.isCopyProtected(false, false, false, 0, true, false, false));
        assertTrue(HgramOcrProtection.isCopyProtected(false, false, false, 0, false, true, false));

        // Permissions may change while a selection is open: copy must re-evaluate, not use a snapshot.
        boolean protectedAtCopy = HgramOcrProtection.isCopyProtected(false, false, false, 0, false, false, false);
        assertFalse(protectedAtCopy);
        protectedAtCopy = HgramOcrProtection.isCopyProtected(true, false, false, 0, false, false, false);
        assertTrue(protectedAtCopy);
    }
}
