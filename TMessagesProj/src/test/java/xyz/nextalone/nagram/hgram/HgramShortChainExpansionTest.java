package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HgramShortChainExpansionTest {

    @Test
    public void toggleExpandsThenCollapses() {
        HgramShortChainExpansion state = new HgramShortChainExpansion();
        assertTrue(state.toggle(10));
        assertTrue(state.contains(10));
        assertFalse(state.toggle(10));
        assertFalse(state.contains(10));
    }

    @Test
    public void clearResetsWhenSettingIsDisabled() {
        HgramShortChainExpansion state = new HgramShortChainExpansion();
        state.toggle(1);
        state.toggle(2);
        state.clear();
        assertEquals(0, state.size());
        assertFalse(state.contains(1));
    }

    @Test
    public void conversationKeysAreIndependentAndStateSurvivesRebuild() {
        HgramShortChainExpansion state = new HgramShortChainExpansion();
        assertTrue(state.toggle("1:10:0", 5));
        assertTrue(state.contains("1:10:0", 5));
        assertFalse(state.contains("1:10:1", 5));
        assertFalse(state.contains("2:10:0", 5));

        state.retainAll("1:10:0", Arrays.asList(5));
        assertTrue(state.contains("1:10:0", 5));
        state.clear("1:10:0");
        assertFalse(state.contains("1:10:0", 5));
        assertEquals(0, state.size());
    }

    @Test
    public void emptyPrefillDoesNotEraseRestoredState() {
        HgramShortChainExpansion state = new HgramShortChainExpansion();
        state.toggle("1:10:0", 5);
        state.retainAllIfKnown("1:10:0", Arrays.asList(), false);
        assertTrue(state.contains("1:10:0", 5));
        state.retainAllIfKnown("1:10:0", Arrays.asList(), true);
        assertFalse(state.contains("1:10:0", 5));
    }

    @Test
    public void retainAllPrunesStaleChainsAndCapsGrowth() {
        HgramShortChainExpansion state = new HgramShortChainExpansion();
        state.toggle(1);
        state.toggle(2);
        state.retainAll(Arrays.asList(2, 3));
        assertFalse(state.contains(1));
        assertTrue(state.contains(2));

        for (int i = 0; i < state.capacity() + 5; i++) {
            state.toggle(1000 + i);
        }
        assertTrue(state.size() <= state.capacity());
    }
}
