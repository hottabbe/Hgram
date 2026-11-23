package xyz.nextalone.nagram.hgram;

/**
 * Pure index mapping for album navigation inside {@code PhotoViewer}.
 *
 * <p>In a chat {@code imagesArr} is ordered newest to oldest, while the album grid is laid out
 * oldest to newest from left to right. {@code mediaMode} describes the array: {@code false} for
 * a chat (newest to oldest), {@code true} for the media tab (oldest to newest). The navigator
 * maps a swipe direction to the visually adjacent grid cell and never leaves the current
 * {@code grouped_id}, so the viewer cannot jump to media of another message from inside an album.</p>
 *
 * <p>Results are either a valid array index, {@link #CLOSE} when the viewer has reached the edge
 * of the album or of the flat array, or {@link #NO_MOVE} when the neighbour exists but the caller
 * reports it as not loaded yet (the gesture must be suppressed).</p>
 */
public final class HgramAlbumNavigator {

    /** No target: the viewer has to be closed. */
    public static final int CLOSE = -1;
    /** The neighbour exists but is not ready: keep the current photo. */
    public static final int NO_MOVE = -2;

    public static final int DIRECTION_BACK = -1;
    public static final int DIRECTION_FORWARD = 1;
    /** Alias used by the viewer's left-edge gesture path. */
    public static final int DIRECTION_BACKWARD = DIRECTION_BACK;

    private HgramAlbumNavigator() {
    }

    /**
     * @return index of the neighbouring photo in the requested direction, or {@link #CLOSE}.
     *         No loading information is consulted: this is the index the preview slots should use.
     */
    public static int neighborIndex(int size, int currentIndex, boolean mediaMode,
                                    long[] groupedIds, int direction) {
        if (size <= 0 || currentIndex < 0 || currentIndex >= size || direction == 0) {
            return CLOSE;
        }
        long groupId = groupedIds != null && currentIndex < groupedIds.length
                ? groupedIds[currentIndex] : 0L;
        if (mediaMode || groupId == 0L) {
            int flat = currentIndex + (direction > 0 ? 1 : -1);
            return flat >= 0 && flat < size ? flat : CLOSE;
        }
        // Chat album arrays are newest -> oldest, so the right neighbour has a lower index.
        int target = currentIndex + (direction > 0 ? -1 : 1);
        if (target < 0 || target >= size || groupedIds == null || target >= groupedIds.length) {
            return CLOSE;
        }
        return groupedIds[target] == groupId ? target : CLOSE;
    }

    /**
     * Same mapping as {@link #neighborIndex(int, int, boolean, long[], int)} plus the loading gate.
     *
     * @return the target index, {@link #NO_MOVE} when the target is not loaded, or {@link #CLOSE}.
     */
    public static int resolveMove(int size, int currentIndex, boolean mediaMode,
                                  long[] groupedIds, int direction, boolean neighborLoaded) {
        int target = neighborIndex(size, currentIndex, mediaMode, groupedIds, direction);
        if (target == CLOSE) {
            return CLOSE;
        }
        return neighborLoaded ? target : NO_MOVE;
    }

    /**
     * Tells which preview slot holds the new photo after a transition: {@code true} means the
     * forward/right slot (used by {@code goToNext}), {@code false} the back/left slot.
     * Inside a chat album the grid is mirrored relative to {@code imagesArr}, so an index decrease
     * is a forward step; the media tab and flat navigation keep the usual index increase.
     */
    public static boolean isForwardStep(boolean mediaMode, long previousGroupId,
                                        long currentGroupId, int previousIndex, int currentIndex) {
        if (!mediaMode && previousGroupId != 0L && previousGroupId == currentGroupId) {
            return currentIndex < previousIndex;
        }
        return currentIndex > previousIndex;
    }
}
