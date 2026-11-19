package org.telegram.ui.Adapters;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DialogsSearchAdapterNamesTest {

    @Test
    public void filteringKeepsResultAndNamesAligned() {
        final ArrayList<Object> result = new ArrayList<>(Arrays.asList("hidden", "visible", "hidden2"));
        final ArrayList<CharSequence> names = new ArrayList<>(Arrays.asList("Hidden Chat", "Visible Chat", "Hidden Chat 2"));
        final Set<String> hidden = new HashSet<>(Arrays.asList("hidden", "hidden2"));

        DialogsSearchAdapter.filterAligned(result, names, item -> !hidden.contains(item));

        assertEquals(1, result.size());
        assertEquals("visible", result.get(0));
        assertEquals(1, names.size());
        assertEquals("Visible Chat", names.get(0).toString());
        assertFalse(names.contains("Hidden Chat"));
        assertFalse(names.contains("Hidden Chat 2"));
    }


    @Test
    public void alwaysTruePredicatePreservesOrder() {
        final ArrayList<Object> result = new ArrayList<>(Arrays.asList("a", "b", "c"));
        final ArrayList<CharSequence> names = new ArrayList<>(Arrays.asList("A", "B", "C"));
        DialogsSearchAdapter.filterAligned(result, names, item -> true);
        assertEquals(Arrays.asList("a", "b", "c"), result);
        assertEquals(3, names.size());
        assertEquals("A", names.get(0).toString());
        assertEquals("B", names.get(1).toString());
        assertEquals("C", names.get(2).toString());
    }

    @Test
    public void shortNamesArePaddedToResultSize() {
        final ArrayList<Object> result = new ArrayList<>(Arrays.asList("a", "b", "c"));
        final ArrayList<CharSequence> names = new ArrayList<>(Arrays.asList("A"));

        DialogsSearchAdapter.filterAligned(result, names, item -> true);

        assertEquals(3, result.size());
        assertEquals(3, names.size());
        assertEquals("A", names.get(0).toString());
        assertEquals(null, names.get(1));
        assertEquals(null, names.get(2));
    }
}
