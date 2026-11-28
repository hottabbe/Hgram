package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class HgramStoriesSelectionTest {

    @Test
    public void zeroIsAValidSearchStoryKey() {
        final ArrayList<ArrayList<Integer>> days = new ArrayList<>();
        days.add(new ArrayList<>(Arrays.asList(7)));
        days.add(new ArrayList<>(Arrays.asList(0)));
        days.add(new ArrayList<>(Arrays.asList(9)));

        assertEquals(1, HgramStoriesSelection.findDayPosition(days, 0));
        assertEquals(0, HgramStoriesSelection.targetStoryId(0, 9));
    }

    @Test
    public void fallbackIsUsedOnlyWithoutCurrentPage() {
        final ArrayList<ArrayList<Integer>> days = new ArrayList<>();
        days.add(new ArrayList<>(Arrays.asList(7)));
        days.add(new ArrayList<>(Arrays.asList(0)));

        assertEquals(0, HgramStoriesSelection.findDayPosition(days, 7));
        assertEquals(0, HgramStoriesSelection.targetStoryId(-1, 0));
        assertEquals(1, HgramStoriesSelection.findDayPosition(days, HgramStoriesSelection.targetStoryId(-1, 0)));
        assertEquals(-1, HgramStoriesSelection.findDayPosition(days, 5));
    }
}
