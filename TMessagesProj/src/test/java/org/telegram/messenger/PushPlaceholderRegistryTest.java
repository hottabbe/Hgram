package org.telegram.messenger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PushPlaceholderRegistryTest {

    @Test
    public void markThenConsume_trueOnce() {
        PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
        registry.mark(777L, 1001);

        assertTrue(registry.hasPending(777L));
        assertTrue(registry.hasPending(777L, 1001));
        assertTrue(registry.hasAny());

        assertTrue("first consume replaces the placeholder", registry.consume(777L, 1001));
        assertFalse("second consume must not repeat the replacement", registry.consume(777L, 1001));
        // PUSH-CHAT-3: the UI consume only burns the one-shot right; the durable marker belongs to
        // storage and stays until the real write is confirmed.
        assertTrue("durable pending survives the UI consume", registry.hasPending(777L));
        assertTrue(registry.hasPending(777L, 1001));
        assertTrue(registry.hasAny());
        assertFalse(registry.hasReplacement(777L, 1001));

        registry.clear(777L, 1001);
        assertFalse("only the storage clear removes the durable marker", registry.hasPending(777L));
        assertFalse(registry.hasAny());
    }

    @Test
    public void consumeUnknown_false() {
        PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
        assertFalse(registry.consume(777L, 1001));

        registry.mark(777L, 1001);
        assertFalse("another dialog", registry.consume(778L, 1001));
        assertFalse("another id", registry.consume(777L, 1002));
        assertTrue(registry.consume(777L, 1001));
    }

    @Test
    public void storageClearFirst_keepsOneShotUiReplacement() {
        PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
        registry.mark(777L, 1001);

        // Real message written in storage before the UI update: the durable row and marker are gone.
        registry.clear(777L, 1001);

        assertFalse("durable pending is acknowledged", registry.hasPending(777L, 1001));
        assertFalse(registry.hasAny());
        assertTrue("the open chat still replaces the preview once", registry.consume(777L, 1001));
        assertFalse("a duplicate UI update must not replace twice", registry.consume(777L, 1001));
    }

    @Test
    public void uiConsumeBeforeFailedRealWriteKeepsDurablePending() {
        PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
        registry.mark(777L, 1001);

        // The open chat replaced the preview, but the real storage write did not commit yet (or
        // failed): the durable pending row still exists and must keep the marker alive.
        assertTrue(registry.consume(777L, 1001));
        assertFalse(registry.hasReplacement(777L, 1001));
        assertTrue("consume must not acknowledge the durable row", registry.hasPending(777L, 1001));
        assertTrue(registry.hasPending(777L));
        assertTrue(registry.hasAny());
        assertFalse("no second UI replacement", registry.consume(777L, 1001));

        // Only the confirmed storage write clears the durable marker.
        registry.clear(777L, 1001);
        assertFalse(registry.hasPending(777L, 1001));
        assertFalse(registry.hasAny());
    }

    @Test
    public void uiConsumeFirst_thenStorageClear_isIdempotent() {
        PushPlaceholderRegistry registry = new PushPlaceholderRegistry();
        registry.mark(777L, 1001);

        assertTrue(registry.consume(777L, 1001));
        registry.clear(777L, 1001);

        assertFalse(registry.hasPending(777L, 1001));
        assertFalse("second consume false in the other ordering too", registry.consume(777L, 1001));
    }
}
