package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramShortcutReconcilerTest {

    private static final String HIDDEN = "Hidden";
    private static final int HIDDEN_ICON = 111;
    private static final int REAL_ICON = 222;

    @Test
    public void everyOwnedPrefixIsNeutralizedOnHide() {
        for (String prefix : new String[]{"sdid_", "ndid_", "bdid_", "did3_"}) {
            final FakeBackend backend = new FakeBackend();
            final String id = prefix + "42";
            backend.pinned.add(shortcut(id, 0, "Alice", REAL_ICON, true));
            final Set<String> neutralized = new HashSet<>();
            final HgramShortcutReconciler.Result result = reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                    (account, dialogId) -> true, neutralized);
            assertEquals(1, backend.updateCalls);
            assertEquals(1, backend.disableCalls);
            final HgramShortcutReconciler.Snapshot after = backend.pinned.get(0);
            assertEquals(HIDDEN, after.shortLabel.toString());
            assertEquals(HIDDEN, after.longLabel.toString());
            assertEquals(HIDDEN_ICON, after.iconRes);
            assertFalse(after.enabled);
            assertTrue(neutralized.contains(id));
            assertTrue(result.trackingChanged);
        }
    }


    @Test
    public void visibleUntrackedShortcutIsNoOp() {
        final FakeBackend backend = new FakeBackend();
        final String id = "sdid_42";
        backend.pinned.add(shortcut(id, 0, "Alice", REAL_ICON, true));
        final Set<String> neutralized = new HashSet<>();
        final HgramShortcutReconciler.Result result = reconcile(backend,
                (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> false, neutralized);
        assertEquals(0, backend.updateCalls);
        assertEquals(0, backend.disableCalls);
        assertEquals(0, backend.enableCalls);
        assertEquals(0, backend.removeDynamicCalls);
        assertEquals("Alice", backend.pinned.get(0).shortLabel.toString());
        assertEquals(REAL_ICON, backend.pinned.get(0).iconRes);
        assertTrue(neutralized.isEmpty());
        assertFalse(result.trackingChanged);
    }

    @Test
    public void everyOwnedPrefixIsRestoredOnUnhide() {
        for (String prefix : new String[]{"sdid_", "ndid_", "bdid_", "did3_"}) {
            final FakeBackend backend = new FakeBackend();
            final String id = prefix + "42";
            backend.pinned.add(shortcut(id, 0, HIDDEN, HIDDEN_ICON, false));
            final Set<String> neutralized = new HashSet<>(Collections.singletonList(id));
            reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                    (account, dialogId) -> false, neutralized);
            assertEquals(1, backend.updateCalls);
            assertEquals(1, backend.enableCalls);
            final HgramShortcutReconciler.Snapshot after = backend.pinned.get(0);
            assertEquals("Alice", after.shortLabel.toString());
            assertEquals("Alice", after.longLabel.toString());
            assertEquals(REAL_ICON, after.iconRes);
            assertTrue(after.enabled);
            assertTrue(neutralized.isEmpty());
        }
    }

    @Test
    public void secondPassWithSameStateIsNoOp() {
        final FakeBackend backend = new FakeBackend();
        backend.pinned.add(shortcut("sdid_42", 0, "Alice", REAL_ICON, true));
        final Set<String> neutralized = new HashSet<>();
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> true, neutralized);
        backend.resetCounters();
        final HgramShortcutReconciler.Result result = reconcile(backend,
                (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> true, neutralized);
        assertEquals(0, backend.updateCalls);
        assertEquals(0, backend.enableCalls);
        assertEquals(0, backend.disableCalls);
        assertEquals(0, backend.removeDynamicCalls);
        assertFalse(result.trackingChanged);
        assertFalse(result.dynamicNeedsRebuild);
    }

    @Test
    public void missingOwnerIsNeutralizedAndNeverRestored() {
        final FakeBackend backend = new FakeBackend();
        backend.pinned.add(shortcut("did3_42", -1, "Alice", REAL_ICON, true));
        final Set<String> neutralized = new HashSet<>();
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> false, neutralized);
        assertEquals(HIDDEN, backend.pinned.get(0).shortLabel.toString());
        assertFalse(backend.pinned.get(0).enabled);
        assertTrue(neutralized.contains("did3_42"));
    }

    @Test
    public void missingEntityStaysNeutralAndRetriesAfterItAppears() {
        final FakeBackend backend = new FakeBackend();
        final String id = "bdid_42";
        backend.pinned.add(shortcut(id, 0, HIDDEN, HIDDEN_ICON, false));
        final Set<String> neutralized = new HashSet<>(Collections.singletonList(id));
        reconcile(backend, (account, dialogId, type) -> null, (account, dialogId) -> false, neutralized);
        assertTrue(neutralized.contains(id));
        assertEquals(HIDDEN, backend.pinned.get(0).shortLabel.toString());

        backend.resetCounters();
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Bot", 333),
                (account, dialogId) -> false, neutralized);
        assertEquals(1, backend.updateCalls);
        assertEquals(1, backend.enableCalls);
        assertEquals("Bot", backend.pinned.get(0).shortLabel.toString());
        assertTrue(neutralized.isEmpty());
    }

    @Test
    public void failedUpdateStillDisablesAndKeepsIdForRetry() {
        final FakeBackend backend = new FakeBackend();
        backend.updateResult = false;
        backend.pinned.add(shortcut("sdid_42", 0, "Alice", REAL_ICON, true));
        final Set<String> neutralized = new HashSet<>();
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> true, neutralized);
        assertEquals(1, backend.updateCalls);
        assertEquals(1, backend.disableCalls);
        assertFalse(backend.pinned.get(0).enabled);
        assertTrue(neutralized.contains("sdid_42"));
    }

    @Test
    public void absentTrackedIdIsPruned() {
        final FakeBackend backend = new FakeBackend();
        final Set<String> neutralized = new HashSet<>(Collections.singletonList("sdid_999"));
        final HgramShortcutReconciler.Result result = reconcile(backend,
                (account, dialogId, type) -> null, (account, dialogId) -> false, neutralized);
        assertTrue(neutralized.isEmpty());
        assertTrue(result.trackingChanged);
        assertTrue(result.dynamicNeedsRebuild);
    }

    @Test
    public void neutralizationIsBatched() {
        final FakeBackend backend = new FakeBackend();
        backend.pinned.add(shortcut("sdid_1", 0, "A", REAL_ICON, true));
        backend.pinned.add(shortcut("did3_2", 0, "B", REAL_ICON, true));
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("X", REAL_ICON),
                (account, dialogId) -> true, new HashSet<>());
        assertEquals(1, backend.updateCalls);
        assertEquals(1, backend.disableCalls);
        assertEquals(2, backend.lastUpdated.size());
        assertEquals(2, backend.lastDisabled.size());
    }

    @Test
    public void hiddenSnapshotWithUnknownIconAndTrackingIsNoOp() {
        final FakeBackend backend = new FakeBackend();
        final String id = "sdid_42";
        backend.pinned.add(shortcut(id, 0, HIDDEN, 0, false));
        final Set<String> neutralized = new HashSet<>(Collections.singletonList(id));
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> true, neutralized);
        assertEquals(0, backend.updateCalls);
        assertEquals(0, backend.enableCalls);
        assertEquals(0, backend.disableCalls);
        assertTrue(neutralized.contains(id));
    }

    @Test
    public void hiddenSnapshotWithoutTrackingIsAdoptedWithoutMutation() {
        final FakeBackend backend = new FakeBackend();
        final String id = "sdid_42";
        backend.pinned.add(shortcut(id, 0, HIDDEN, 0, false));
        final Set<String> neutralized = new HashSet<>();
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> true, neutralized);
        assertEquals(0, backend.updateCalls);
        assertEquals(0, backend.disableCalls);
        assertTrue(neutralized.contains(id));
    }

    @Test
    public void failedEnableKeepsTrackingAndRetriesNextPass() {
        final FakeBackend backend = new FakeBackend();
        final String id = "sdid_42";
        backend.pinned.add(shortcut(id, 0, HIDDEN, HIDDEN_ICON, false));
        backend.enableResult = false;
        final Set<String> neutralized = new HashSet<>(Collections.singletonList(id));
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> false, neutralized);
        assertEquals(1, backend.enableCalls);
        assertTrue(neutralized.contains(id));
        assertFalse(backend.pinned.get(0).enabled);

        backend.enableResult = true;
        backend.resetCounters();
        reconcile(backend, (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> false, neutralized);
        assertEquals(1, backend.enableCalls);
        assertTrue(backend.pinned.get(0).enabled);
        assertTrue(neutralized.isEmpty());
    }

    @Test
    public void failedPinnedReadSkipsPassWithoutTrackingWrites() {
        final FakeBackend backend = new FakeBackend();
        backend.failPinnedRead = true;
        final Set<String> neutralized = new HashSet<>(Collections.singletonList("sdid_42"));
        final HgramShortcutReconciler.Result result = reconcile(backend,
                (account, dialogId, type) -> new HgramShortcutReconciler.Entity("Alice", REAL_ICON),
                (account, dialogId) -> false, neutralized);
        assertEquals(0, backend.updateCalls);
        assertEquals(0, backend.enableCalls);
        assertEquals(0, backend.disableCalls);
        assertEquals(0, backend.removeDynamicCalls);
        assertTrue(neutralized.contains("sdid_42"));
        assertFalse(result.trackingChanged);
        assertFalse(result.dynamicNeedsRebuild);
    }

    private static HgramShortcutReconciler.Result reconcile(FakeBackend backend,
            HgramShortcutReconciler.EntityResolver resolver,
            HgramShortcutReconciler.HiddenLookup hidden, Set<String> neutralized) {
        return HgramShortcutReconciler.reconcile(backend, resolver, hidden, neutralized, HIDDEN, HIDDEN_ICON);
    }

    private static HgramShortcutReconciler.Snapshot shortcut(String id, int owner, String label, int icon, boolean enabled) {
        final Intent intent = new Intent();
        if (owner >= 0) {
            intent.putExtra("currentAccount", owner);
        }
        return new HgramShortcutReconciler.Snapshot(id, label, label, icon, enabled, intent);
    }

    private static final class FakeBackend implements HgramShortcutReconciler.Backend {
        final ArrayList<HgramShortcutReconciler.Snapshot> pinned = new ArrayList<>();
        final ArrayList<HgramShortcutReconciler.Snapshot> dynamic = new ArrayList<>();
        int updateCalls;
        int enableCalls;
        int disableCalls;
        int removeDynamicCalls;
        boolean updateResult = true;
        boolean enableResult = true;
        boolean failPinnedRead;
        List<HgramShortcutReconciler.Snapshot> lastUpdated = Collections.emptyList();
        List<String> lastEnabled = Collections.emptyList();
        List<String> lastDisabled = Collections.emptyList();
        List<String> lastRemoved = Collections.emptyList();

        @Override
        public List<HgramShortcutReconciler.Snapshot> listPinned() {
            if (failPinnedRead) {
                throw new IllegalStateException("read failed");
            }
            return new ArrayList<>(pinned);
        }

        @Override
        public List<HgramShortcutReconciler.Snapshot> listDynamic() {
            return new ArrayList<>(dynamic);
        }

        @Override
        public boolean updateShortcuts(List<HgramShortcutReconciler.Snapshot> shortcuts) {
            updateCalls++;
            lastUpdated = new ArrayList<>(shortcuts);
            if (!updateResult) {
                return false;
            }
            final Map<String, HgramShortcutReconciler.Snapshot> byId = new HashMap<>();
            for (HgramShortcutReconciler.Snapshot snapshot : shortcuts) {
                byId.put(snapshot.id, snapshot);
            }
            for (int i = 0; i < pinned.size(); i++) {
                final HgramShortcutReconciler.Snapshot current = pinned.get(i);
                final HgramShortcutReconciler.Snapshot desired = byId.get(current.id);
                if (desired != null) {
                    pinned.set(i, new HgramShortcutReconciler.Snapshot(desired.id, desired.shortLabel,
                            desired.longLabel, desired.iconRes, current.enabled, desired.intent));
                }
            }
            return true;
        }

        @Override
        public boolean enableShortcuts(List<String> ids) {
            enableCalls++;
            lastEnabled = new ArrayList<>(ids);
            if (!enableResult) {
                return false;
            }
            setEnabled(ids, true);
            return true;
        }

        @Override
        public void disableShortcuts(List<String> ids, CharSequence message) {
            disableCalls++;
            lastDisabled = new ArrayList<>(ids);
            setEnabled(ids, false);
        }

        @Override
        public void removeDynamicShortcuts(List<String> ids) {
            removeDynamicCalls++;
            lastRemoved = new ArrayList<>(ids);
            dynamic.removeIf(snapshot -> ids.contains(snapshot.id));
        }

        private void setEnabled(List<String> ids, boolean enabled) {
            for (int i = 0; i < pinned.size(); i++) {
                final HgramShortcutReconciler.Snapshot current = pinned.get(i);
                if (ids.contains(current.id)) {
                    pinned.set(i, new HgramShortcutReconciler.Snapshot(current.id, current.shortLabel,
                            current.longLabel, current.iconRes, enabled, current.intent));
                }
            }
        }

        void resetCounters() {
            updateCalls = 0;
            enableCalls = 0;
            disableCalls = 0;
            removeDynamicCalls = 0;
            lastUpdated = Collections.emptyList();
            lastEnabled = Collections.emptyList();
            lastDisabled = Collections.emptyList();
            lastRemoved = Collections.emptyList();
        }
    }
}
