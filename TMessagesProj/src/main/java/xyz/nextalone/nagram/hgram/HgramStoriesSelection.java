package xyz.nextalone.nagram.hgram;

import java.util.ArrayList;

/** Stable story selection for search lists where server story ids are not the page keys. */
public final class HgramStoriesSelection {

    private HgramStoriesSelection() {
    }

    public static int targetStoryId(int currentPageStoryId, int fallbackStoryId) {
        return currentPageStoryId >= 0 ? currentPageStoryId : fallbackStoryId;
    }

    public static int findDayPosition(ArrayList<ArrayList<Integer>> days, int storyId) {
        if (days == null) {
            return -1;
        }
        for (int i = 0; i < days.size(); i++) {
            final ArrayList<Integer> day = days.get(i);
            if (day != null && day.contains(storyId)) {
                return i;
            }
        }
        return -1;
    }
}
