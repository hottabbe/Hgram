package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import org.telegram.messenger.UserConfig;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramShortcutStateTest {

    @Test
    public void decisionIsScopedToShortcutOwnerAccount() {
        // The same peer hidden in account A must not force the shortcut owned by account B to stay neutral.
        assertEquals(HgramShortcutState.NEUTRAL, HgramShortcutState.decide(0, 100L, true));
        assertEquals(HgramShortcutState.RESTORE, HgramShortcutState.decide(1, 100L, false));
        assertEquals(HgramShortcutState.RESTORE, HgramShortcutState.decide(1, 100L, false));
    }

    @Test
    public void invalidOwnerOrDialogIsFailClosed() {
        assertEquals(HgramShortcutState.SKIP, HgramShortcutState.decide(-1, 100L, false));
        assertEquals(HgramShortcutState.SKIP, HgramShortcutState.decide(UserConfig.MAX_ACCOUNT_COUNT, 100L, false));
        assertEquals(HgramShortcutState.SKIP, HgramShortcutState.decide(0, 0L, false));
    }

    @Test
    public void parsesOwnedShortcutIds() {
        assertEquals(HgramShortcutState.TYPE_USER_OR_CHAT, HgramShortcutState.typeForId("sdid_42"));
        assertEquals(HgramShortcutState.TYPE_USER_OR_CHAT, HgramShortcutState.typeForId("ndid_42"));
        assertEquals(HgramShortcutState.TYPE_USER_OR_CHAT, HgramShortcutState.typeForId("did3_42"));
        assertEquals(HgramShortcutState.TYPE_ATTACHED_BOT, HgramShortcutState.typeForId("bdid_42"));
        assertEquals(42L, HgramShortcutState.dialogIdForId("bdid_42"));
        assertEquals(42L, HgramShortcutState.dialogIdForId("sdid_42"));
    }

    @Test
    public void unknownOrMalformedIdsAreSkipped() {
        assertEquals(HgramShortcutState.TYPE_UNKNOWN, HgramShortcutState.typeForId("compose"));
        assertEquals(HgramShortcutState.TYPE_UNKNOWN, HgramShortcutState.typeForId(null));
        assertEquals(0L, HgramShortcutState.dialogIdForId("sdid_"));
        assertEquals(0L, HgramShortcutState.dialogIdForId("sdid_notanumber"));
        assertEquals(HgramShortcutState.TYPE_USER_OR_CHAT, HgramShortcutState.typeForId("sdid_notanumber"));
    }

    @Test
    public void reconcileRestoresOnlyShortcutsItNeutralized() {
        assertEquals(HgramShortcutState.NOOP, HgramShortcutState.reconcileDecision(0, 100L, false, false));
        assertEquals(HgramShortcutState.RESTORE, HgramShortcutState.reconcileDecision(0, 100L, false, true));
        assertEquals(HgramShortcutState.NEUTRAL, HgramShortcutState.reconcileDecision(0, 100L, true, false));
        assertEquals(HgramShortcutState.NEUTRAL, HgramShortcutState.reconcileDecision(0, 100L, true, true));
        assertEquals(HgramShortcutState.SKIP, HgramShortcutState.reconcileDecision(-1, 100L, false, true));
    }

    @Test
    public void everyOwnedPrefixUsesExactPerShortcutDecision() {
        assertEquals(HgramShortcutState.RESTORE, HgramShortcutState.reconcileDecision(1, 42L, false, true));
        assertEquals(42L, HgramShortcutState.dialogIdForId("did3_42"));
        assertEquals(42L, HgramShortcutState.dialogIdForId("ndid_42"));
        assertEquals(42L, HgramShortcutState.dialogIdForId("bdid_42"));
        assertEquals(HgramShortcutState.NEUTRAL, HgramShortcutState.reconcileDecision(0, 42L, true, true));
        assertEquals(HgramShortcutState.RESTORE, HgramShortcutState.reconcileDecision(1, 42L, false, true));
    }
}
