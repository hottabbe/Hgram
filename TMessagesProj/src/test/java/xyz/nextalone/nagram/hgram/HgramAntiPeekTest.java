package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

public class HgramAntiPeekTest {

    @Test
    public void higherSensitivityNeedsLessTilt() {
        assertEquals(0.866f, HgramAntiPeek.downThreshold(
                HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH), 0.001f);
        assertEquals(0.707f, HgramAntiPeek.downThreshold(
                HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM), 0.001f);
        assertEquals(0.5f, HgramAntiPeek.downThreshold(
                HgramConfig.ANTI_PEEK_SENSITIVITY_LOW), 0.001f);
        assertTrue(HgramAntiPeek.downThreshold(HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH)
                > HgramAntiPeek.downThreshold(HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertTrue(HgramAntiPeek.downThreshold(HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM)
                > HgramAntiPeek.downThreshold(HgramConfig.ANTI_PEEK_SENSITIVITY_LOW));
        assertTrue(HgramAntiPeek.upThreshold(HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH)
                > HgramAntiPeek.upThreshold(HgramConfig.ANTI_PEEK_SENSITIVITY_LOW));
    }

    @Test
    public void classifierDetectsDownAndUpWithGravityGate() {
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_DOWN,
                HgramAntiPeek.classifySample(-9f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                HgramAntiPeek.classifySample(9f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                HgramAntiPeek.classifySample(0f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_UNDEFINED,
                HgramAntiPeek.classifySample(-9f, 4.9f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_UNDEFINED,
                HgramAntiPeek.classifySample(-9f, 13.6f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
    }

    @Test
    public void sensitivityChangesDownThresholdBoundary() {
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                HgramAntiPeek.classifySample(-5f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH));
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                HgramAntiPeek.classifySample(-5f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_DOWN,
                HgramAntiPeek.classifySample(-5f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_LOW));
    }



    @Test
    public void acceptanceVectorsMatchAngleThresholds() {
        // 0:0:-9.8 => down on every level.
        for (int level : new int[]{HgramConfig.ANTI_PEEK_SENSITIVITY_LOW,
                HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM, HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH}) {
            assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_DOWN,
                    HgramAntiPeek.classifySample(-9.8f, 9.8f, level));
        }
        // 0:5:-7 => ratio -0.81: medium down, high is in the dead zone, low down.
        assertEquals(HgramAntiPeek.ORIENTATION_UNDEFINED,
                HgramAntiPeek.classifySample(-7f, 8.602f, HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH));
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_DOWN,
                HgramAntiPeek.classifySample(-7f, 8.602f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_DOWN,
                HgramAntiPeek.classifySample(-7f, 8.602f, HgramConfig.ANTI_PEEK_SENSITIVITY_LOW));
        // 0:5:7 => up, 0:9.8:0 => vertical/up, 0:0:0 => outside gravity gate.
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                HgramAntiPeek.classifySample(7f, 8.602f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                HgramAntiPeek.classifySample(0f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_UNDEFINED,
                HgramAntiPeek.classifySample(0f, 0f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
    }

    @Test
    public void holdInsideWindowToggles() {
        final HgramAntiPeek.HoldState state = new HgramAntiPeek.HoldState();
        assertFalse(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 0, 200, 1200));
        assertFalse(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 1000, 200, 1200));
        assertTrue(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 1500, 200, 1200));
    }

    @Test
    public void holdOutsideWindowDoesNotToggle() {
        final HgramAntiPeek.HoldState state = new HgramAntiPeek.HoldState();
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 0, 200, 1200);
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 1000, 200, 1200);
        assertFalse(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 1100, 200, 1200));
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 2000, 200, 1200);
        assertFalse(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 3300, 200, 1200));
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 4000, 200, 1200);
        assertTrue(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 5200, 200, 1200));
    }

    @Test
    public void firstDownAfterStartNeedsAnUpArm() {
        final HgramAntiPeek.HoldState state = new HgramAntiPeek.HoldState();
        assertFalse(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 0, 200, 1200));
        assertFalse(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 500, 200, 1200));
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 1000, 200, 1200);
        assertTrue(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 1500, 200, 1200));
    }

    @Test
    public void deadZoneDoesNotRearmOrDoubleToggle() {
        final HgramAntiPeek.HoldState state = new HgramAntiPeek.HoldState();
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 0, 200, 1200);
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 1000, 200, 1200);
        state.onSample(HgramAntiPeek.ORIENTATION_UNDEFINED, 1050, 200, 1200);
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 1100, 200, 1200);
        assertTrue(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 1500, 200, 1200));
        assertFalse(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 1600, 200, 1200));
        state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 1700, 200, 1200);
        assertTrue(state.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 2200, 200, 1200));
    }

    @Test
    public void scopeAllowsOnlyChatScreens() {
        assertTrue(HgramAntiPeek.isGestureScopeClass("org.telegram.ui.DialogsActivity"));
        assertTrue(HgramAntiPeek.isGestureScopeClass("org.telegram.ui.ChatActivity"));
        assertTrue(HgramAntiPeek.isGestureScopeClass("org.telegram.ui.TopicsFragment"));
        assertFalse(HgramAntiPeek.isGestureScopeClass("tw.nekomimi.nekogram.settings.NekoSettingsActivity"));
        assertFalse(HgramAntiPeek.isGestureScopeClass("org.telegram.ui.PhotoViewer"));
        assertFalse(HgramAntiPeek.isGestureScopeClass(null));
    }

    @Test
    public void reviewBlockersRegressionCoverage() {
        // B1: active sheet -> visible layers -> right pane -> main.
        assertEquals("privacy", HgramAntiPeek.chooseForeground(
                true, "privacy", false, null, true, "chat", "dialogs"));
        assertEquals("privacy", HgramAntiPeek.chooseForeground(
                true, "privacy", true, "settings", true, "chat", "dialogs"));
        assertEquals("settings", HgramAntiPeek.chooseForeground(
                false, null, true, "settings", true, "chat", "dialogs"));
        assertEquals("chat", HgramAntiPeek.chooseForeground(
                false, null, false, null, true, "chat", "dialogs"));
        assertEquals("dialogs", HgramAntiPeek.chooseForeground(
                false, null, false, null, false, "chat", "dialogs"));
        assertNull(HgramAntiPeek.chooseForeground(
                true, null, true, "settings", true, "chat", "dialogs"));
        assertNull(HgramAntiPeek.chooseForeground(
                false, null, false, null, false, null, null));
        assertFalse(HgramAntiPeek.isGestureScopeClass("settings"));
        assertTrue(HgramAntiPeek.isGestureScopeClass("org.telegram.ui.ChatActivity"));

        // B2: UNDEFINED after reset must not arm the first DOWN.
        final HgramAntiPeek.HoldState undefinedFirst = new HgramAntiPeek.HoldState();
        assertFalse(undefinedFirst.onSample(HgramAntiPeek.ORIENTATION_UNDEFINED,
                0, 200, 1200));
        assertFalse(undefinedFirst.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN,
                100, 200, 1200));
        assertFalse(undefinedFirst.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                600, 200, 1200));
        undefinedFirst.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 700, 200, 1200);
        assertTrue(undefinedFirst.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                900, 200, 1200));

        // B3: a foreground boundary invalidates the hold started on the previous screen.
        final HgramAntiPeek.HoldState navigation = new HgramAntiPeek.HoldState();
        navigation.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 0, 200, 1200);
        navigation.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 100, 200, 1200);
        navigation.invalidatePending();
        assertFalse(navigation.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                600, 200, 1200));
        navigation.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 700, 200, 1200);
        assertTrue(navigation.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                900, 200, 1200));

        // B3: reset preserves the real 500 ms cooldown.
        final HgramAntiPeek.HoldState cooldown = new HgramAntiPeek.HoldState();
        cooldown.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 0, 200, 1200);
        cooldown.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 100, 200, 1200);
        assertTrue(cooldown.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                300, 200, 1200));
        cooldown.reset();
        cooldown.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP, 320, 200, 1200);
        cooldown.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 400, 200, 1200);
        assertFalse(cooldown.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                700, 200, 1200));
        cooldown.onSample(HgramAntiPeek.ORIENTATION_SCREEN_DOWN, 800, 200, 1200);
        assertTrue(cooldown.onSample(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                1000, 200, 1200));

    }

    @Test
    public void overlayGuardTracksAllViewers() {
        final HgramAntiPeek.OverlayGuard guard = new HgramAntiPeek.OverlayGuard();
        final Object firstViewer = new Object();
        final Object secondViewer = new Object();
        assertTrue(guard.acquire(firstViewer));
        assertTrue(guard.acquire(secondViewer));
        assertFalse(guard.acquire(firstViewer));
        assertTrue(guard.release(firstViewer));
        assertTrue(guard.isBlocked());
        assertFalse(guard.release(firstViewer));
        assertTrue(guard.release(secondViewer));
        assertFalse(guard.isBlocked());
        assertNull(guard.getOwner());
    }

    @Test
    public void closingSheetBlocksGestureUntilWindowCloses() {
        final Object sheet = new Object();
        assertFalse(HgramAntiPeek.hasClosingSheet());
        HgramAntiPeek.onSheetDismissStarted(sheet);
        assertTrue(HgramAntiPeek.hasClosingSheet());
        // Repeated dismiss callbacks for the same sheet are idempotent.
        HgramAntiPeek.onSheetDismissStarted(sheet);
        assertTrue(HgramAntiPeek.hasClosingSheet());
        // An unrelated sheet cannot clear this one.
        HgramAntiPeek.onSheetClosed(new Object());
        assertTrue(HgramAntiPeek.hasClosingSheet());
        // dismissInternal/detach or an interrupted/reopened animation clears the guard.
        HgramAntiPeek.onSheetClosed(sheet);
        assertFalse(HgramAntiPeek.hasClosingSheet());
        HgramAntiPeek.onSheetClosed(sheet);
        assertFalse(HgramAntiPeek.hasClosingSheet());
    }

    @Test
    public void sheetVetoKeepsStackLayoutAndGuardIdle() {
        final Object sheet = new Object();
        final ArrayList<Object> stack = new ArrayList<>();
        final Object[] holder = new Object[1];
        stack.add(sheet);
        holder[0] = sheet;
        HgramAntiPeek.onSheetClosed(sheet);

        // A delegate veto reports dismissedNow=false: nothing may be removed or marked.
        assertFalse(HgramSheetState.finalizeDismiss(stack, holder, sheet, false, false));
        assertEquals(1, stack.size());
        assertSame(sheet, stack.get(0));
        assertSame(sheet, holder[0]);
        assertFalse(HgramAntiPeek.hasClosingSheet());
        assertTrue(HgramAntiPeek.isGestureScopeAllowed(false, false,
                "org.telegram.ui.ChatActivity"));
        assertFalse(HgramAntiPeek.isGestureScopeAllowed(false, true,
                "org.telegram.ui.ChatActivity"));
        assertFalse(HgramAntiPeek.isGestureScopeAllowed(true, false,
                "org.telegram.ui.ChatActivity"));

        // One delegate decision: veto happens before onPreFinished and never clears reopen.
        final ArrayList<String> order = new ArrayList<>();
        assertFalse(HgramSheetState.acceptDismiss(false,
                () -> {
                    order.add("delegate");
                    return false;
                },
                () -> order.add("preFinished"),
                () -> order.add("clearReopen")));
        assertEquals(1, order.size());
        assertEquals("delegate", order.get(0));

        order.clear();
        assertTrue(HgramSheetState.acceptDismiss(false,
                () -> {
                    order.add("delegate");
                    return true;
                },
                () -> order.add("preFinished"),
                () -> order.add("clearReopen")));
        assertEquals(3, order.size());
        assertEquals("delegate", order.get(0));
        assertEquals("preFinished", order.get(1));
        assertEquals("clearReopen", order.get(2));

        // Already dismissed/late path is a no-op and must not clear the reopen bit.
        order.clear();
        assertTrue(HgramSheetState.acceptDismiss(true,
                () -> {
                    order.add("delegate");
                    return false;
                },
                () -> order.add("preFinished"),
                () -> order.add("clearReopen")));
        assertTrue(order.isEmpty());

        // A repeated dismiss (alreadyDismissed=true) must not tear down live ownership.
        assertFalse(HgramSheetState.finalizeDismiss(stack, holder, sheet, true, true));
        assertEquals(1, stack.size());
        assertSame(sheet, holder[0]);
        assertFalse(HgramAntiPeek.hasClosingSheet());
    }

    @Test
    public void sheetReopenRestoresOneEntryAndRecloses() {
        final Object sheet = new Object();
        final ArrayList<Object> stack = new ArrayList<>();
        final Object[] holder = new Object[1];
        stack.add(sheet);
        holder[0] = sheet;
        HgramAntiPeek.onSheetClosed(sheet);

        for (int i = 0; i < 5; i++) {
            // Close: guard starts before the stack entry disappears.
            assertTrue(HgramSheetState.finalizeDismiss(stack, holder, sheet, false, true));
            assertTrue(HgramAntiPeek.hasClosingSheet());
            assertTrue(stack.isEmpty());
            assertNull(holder[0]);

            // Reopen during the close animation: restore ownership exactly once.
            assertSame(sheet, HgramSheetState.restoreForShow(stack, holder, sheet));
            assertSame(sheet, HgramSheetState.restoreForShow(stack, holder, sheet));
            assertEquals(1, stack.size());
            assertSame(sheet, stack.get(0));
            assertSame(sheet, holder[0]);
            HgramAntiPeek.onSheetClosed(sheet);
            assertFalse(HgramAntiPeek.hasClosingSheet());
        }

        // A terminal dismissInternal/detach still clears everything.
        assertTrue(HgramSheetState.finalizeDismiss(stack, holder, sheet, false, true));
        HgramSheetState.removeIdentity(stack, sheet);
        holder[0] = null;
        HgramAntiPeek.onSheetClosed(sheet);
        assertTrue(stack.isEmpty());
        assertNull(holder[0]);
        assertFalse(HgramAntiPeek.hasClosingSheet());
    }

    @Test
    public void undefinedZoneHasHysteresis() {
        assertEquals(HgramAntiPeek.ORIENTATION_UNDEFINED,
                HgramAntiPeek.classifySample(-5.6f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
        assertEquals(HgramAntiPeek.ORIENTATION_SCREEN_UP,
                HgramAntiPeek.classifySample(3.5f, 9.8f, HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM));
    }
}
