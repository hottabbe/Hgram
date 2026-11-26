package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HgramReadReceiptCoordinatorTest {

    private static final class FakeScheduler implements HgramReadReceiptCoordinator.Scheduler {
        final ArrayDeque<Runnable> immediate = new ArrayDeque<>();
        final ArrayDeque<Runnable> delayed = new ArrayDeque<>();
        boolean acceptTasks = true;

        @Override
        public boolean post(Runnable runnable) {
            if (!acceptTasks) return false;
            immediate.add(runnable);
            return true;
        }

        @Override
        public boolean postDelayed(Runnable runnable, long delayMillis) {
            if (!acceptTasks) return false;
            delayed.add(runnable);
            return true;
        }

        void runImmediate() {
            Runnable runnable;
            while ((runnable = immediate.poll()) != null) {
                runnable.run();
            }
        }

        void runDelayed() {
            Runnable runnable;
            while ((runnable = delayed.poll()) != null) {
                runnable.run();
            }
        }
    }

    private static final class FakePersistence implements HgramReadReceiptCoordinator.Persistence {
        final List<String> writes = new ArrayList<>();
        int failures;

        FakePersistence(int failures) {
            this.failures = failures;
        }

        @Override
        public boolean write(String value) {
            writes.add(value);
            if (failures > 0) {
                failures--;
                return false;
            }
            return true;
        }
    }

    @Test
    public void rememberWritesAsynchronouslyAndKeepsUiSnapshotImmediately() {
        final FakeScheduler scheduler = new FakeScheduler();
        final FakePersistence persistence = new FakePersistence(0);
        final HgramReadReceiptCoordinator coordinator = new HgramReadReceiptCoordinator(
                "", scheduler, persistence, null);

        coordinator.remember(42L, 0L, 0L, 10, 100);

        // The in-memory queue is visible immediately; disk work stays on the background task.
        assertEquals(1, coordinator.entries().size());
        assertTrue(persistence.writes.isEmpty());

        scheduler.runImmediate();
        assertEquals(1, persistence.writes.size());
        assertEquals(coordinator.snapshot(), persistence.writes.get(0));
    }

    @Test
    public void forgetOnlyRemovesTheIdsThatWereSent() {
        final FakeScheduler scheduler = new FakeScheduler();
        final HgramReadReceiptCoordinator coordinator = new HgramReadReceiptCoordinator(
                "", scheduler, new FakePersistence(0), null);

        coordinator.remember(42L, 0L, 0L, 10, 100);
        coordinator.remember(42L, 0L, 0L, 20, 200);
        scheduler.runImmediate();

        coordinator.forget(42L, 0L, 10, 100);
        assertEquals(20, coordinator.entries().get(0).maxId);
        assertEquals(200, coordinator.entries().get(0).maxDate);

        coordinator.forget(42L, 0L, 20, 200);
        assertTrue(coordinator.entries().isEmpty());
    }

    @Test
    public void simultaneousMutationsDoNotLoseEntries() throws Exception {
        final HgramReadReceiptCoordinator coordinator = new HgramReadReceiptCoordinator(
                "", new FakeScheduler(), new FakePersistence(0), null);
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        final CountDownLatch start = new CountDownLatch(1);
        final int threads = 4;
        final int perThread = 50;

        for (int t = 0; t < threads; t++) {
            final int thread = t;
            executor.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    coordinator.remember(thread * 1000L + i, 0L, 0L, i + 1, i + 10);
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threads * perThread, coordinator.entries().size());
    }

    @Test
    public void persistFailureIsLoggedAndRetried() {
        final FakeScheduler scheduler = new FakeScheduler();
        // More failures than one background task can absorb, so the delayed retry is used.
        final FakePersistence persistence = new FakePersistence(4);
        final List<String> logs = new ArrayList<>();
        final HgramReadReceiptCoordinator coordinator = new HgramReadReceiptCoordinator(
                "", scheduler, persistence, logs::add);

        coordinator.remember(1L, 0L, 0L, 5, 50);
        scheduler.runImmediate();
        assertFalse(logs.isEmpty());
        scheduler.runDelayed();

        assertTrue(persistence.writes.size() >= 5);
        assertEquals(coordinator.snapshot(), persistence.writes.get(persistence.writes.size() - 1));
    }

    @Test
    public void restoreAfterProcessDeathAndClearArePersisted() {
        final FakeScheduler scheduler = new FakeScheduler();
        final FakePersistence persistence = new FakePersistence(0);
        final HgramReadReceiptCoordinator first = new HgramReadReceiptCoordinator(
                "", scheduler, persistence, null);
        first.remember(1L, 0L, 0L, 10, 100);
        first.remember(2L, 7L, 0L, 20, 200);
        scheduler.runImmediate();
        final String persisted = first.snapshot();

        // A new process starts from the persisted preference value.
        final FakeScheduler restoredScheduler = new FakeScheduler();
        final FakePersistence restoredPersistence = new FakePersistence(0);
        final HgramReadReceiptCoordinator restored = new HgramReadReceiptCoordinator(
                persisted, restoredScheduler, restoredPersistence, null);
        assertEquals(2, restored.entries().size());
        assertEquals(persisted, restored.snapshot());

        restored.clear();
        restoredScheduler.runImmediate();
        assertEquals(0, restored.entries().size());
        assertTrue(restored.snapshot().isEmpty());
        assertEquals("", restoredPersistence.writes.get(restoredPersistence.writes.size() - 1));
    }

    @Test
    public void rejectedBackgroundTaskIsLogged() {
        final FakeScheduler scheduler = new FakeScheduler();
        scheduler.acceptTasks = false;
        final List<String> logs = new ArrayList<>();
        final HgramReadReceiptCoordinator coordinator = new HgramReadReceiptCoordinator(
                "", scheduler, new FakePersistence(0), logs::add);

        coordinator.remember(1L, 0L, 0L, 5, 50);

        assertEquals(1, coordinator.entries().size());
        assertFalse(logs.isEmpty());
    }
}
