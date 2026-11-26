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
    /** The requested direction is not related to the recorded album-edge return. */
    public static final int NO_EDGE_RETURN = -3;

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
     * Flat neighbour to use when the album-aware move reaches a group edge but the viewer's
     * {@code imagesArr} still contains the adjacent chat photo. Chat albums are mirrored in the
     * grid, so leaving one keeps the same visual direction (index decrease for forward); the
     * media tab and ungrouped chat photos keep the usual array step.
     *
     * @return a valid flat index or {@link #CLOSE} at the real edge of {@code imagesArr}
     */
    public static int flatFallbackIndex(int size, int currentIndex, boolean mediaMode,
                                        long[] groupedIds, int direction) {
        if (size <= 0 || currentIndex < 0 || currentIndex >= size || direction == 0) {
            return CLOSE;
        }
        final long groupId = groupedIds != null && currentIndex < groupedIds.length
                ? groupedIds[currentIndex] : 0L;
        final int step = !mediaMode && groupId != 0L ? -direction : direction;
        final int flat = currentIndex + (step > 0 ? 1 : -1);
        return flat >= 0 && flat < size ? flat : CLOSE;
    }

    /**
     * Index to preload into the side preview slot for the requested direction. Inside an album
     * this is the group neighbour; at either album edge it is the adjacent flat chat photo, so
     * the swipe animation has a real target and only the real edge of the array returns
     * {@link #CLOSE}.
     */
    public static int previewIndex(int size, int currentIndex, boolean mediaMode,
                                   long[] groupedIds, int direction) {
        final int albumIndex = neighborIndex(size, currentIndex, mediaMode, groupedIds, direction);
        if (albumIndex != CLOSE) {
            return albumIndex;
        }
        return flatFallbackIndex(size, currentIndex, mediaMode, groupedIds, direction);
    }

    /**
     * Target after leaving an album edge to the adjacent flat photo.
     *
     * <p>On the outward side of a chat album the flat photos use the same mirror direction as the
     * album: the exit gesture continues further away from the album, while the reverse gesture
     * walks back to the album edge that was left. Other input returns
     * {@link #NO_EDGE_RETURN}.</p>
     *
     * @param albumIndex    album edge that was left
     * @param exitDirection direction of the gesture that left the album
     * @param direction     direction of the new gesture
     * @return the previous flat index on the way back, the next flat index outward,
     *         {@link #CLOSE} at the real array edge, or {@link #NO_EDGE_RETURN} for other input
     */
    public static int edgeReturnIndex(int size, int currentIndex, int albumIndex,
                                      int exitDirection, int direction) {
        if (size <= 0 || currentIndex < 0 || currentIndex >= size
                || albumIndex < 0 || albumIndex >= size
                || exitDirection == 0 || direction == 0) {
            return NO_EDGE_RETURN;
        }
        if (exitDirection > 0 ? currentIndex >= albumIndex : currentIndex <= albumIndex) {
            return NO_EDGE_RETURN;
        }
        if (direction == exitDirection) {
            final int outward = currentIndex - exitDirection;
            return outward >= 0 && outward < size ? outward : CLOSE;
        }
        if (direction == -exitDirection) {
            final int back = currentIndex + exitDirection;
            return back >= 0 && back < size ? back : CLOSE;
        }
        return NO_EDGE_RETURN;
    }

    /**
     * Tells which preview slot holds the new photo after a transition: {@code true} means the
     * forward/right slot (used by {@code goToNext}), {@code false} the back/left slot.
     * Inside a chat album the grid is mirrored relative to {@code imagesArr}, so an index decrease
     * is a forward step. The same mirror applies when leaving the album to the adjacent chat photo;
     * entering an album from a flat photo follows the usual index increase. The media tab is never
     * mirrored.
     */
    public static boolean isForwardStep(boolean mediaMode, long previousGroupId,
                                        long currentGroupId, int previousIndex, int currentIndex) {
        if (!mediaMode && previousGroupId != 0L) {
            return currentIndex < previousIndex;
        }
        return currentIndex > previousIndex;
    }
}
