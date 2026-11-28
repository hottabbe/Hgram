package xyz.nextalone.nagram.hgram;

/**
 * Pure geometry for the attached message menu: the selected bubble is lifted so its top
 * lands in the upper quarter of the visible chat area, but only when the bubble is in the
 * lower half and fits between the available top and bottom. The same pass keeps the menu
 * inside the available height and the bubble visible above it.
 */
public final class HgramScrimMenuPosition {
    private HgramScrimMenuPosition() {
    }

    public static final class AttachedLayout {
        public final float translationY;
        public final int popupY;
        public final int maxHeight;

        AttachedLayout(float translationY, int popupY, int maxHeight) {
            this.translationY = translationY;
            this.popupY = popupY;
            this.maxHeight = maxHeight;
        }
    }

    public static float computeTranslationY(int availableTop, int attachedAvailableBottom,
                                            int menuGap, int cellTop, int cellHeight) {
        final int availableBottom = attachedAvailableBottom - menuGap;
        final int availableHeight = availableBottom - availableTop;
        if (availableHeight <= 0 || cellHeight <= 0 || cellHeight > availableHeight) {
            return 0f;
        }
        final int cellBottom = cellTop + cellHeight;
        final int mid = (availableTop + availableBottom) / 2;
        final boolean lowerHalf = Math.min(cellBottom, availableBottom) > mid;
        if (!lowerHalf) {
            return 0f;
        }
        final int targetTop = availableTop + availableHeight / 4;
        return targetTop - cellTop;
    }

    /**
     * @param minMenuHeight            minimum space reserved for the menu (dp 160)
     * @param minMessageVisibleSpace   minimum space kept for the bubble (dp 160)
     * @param minMenuHeightFloor       lower bound for the resulting max height (dp 120)
     * @param minY                     top clamp used by the popup
     */
    public static AttachedLayout computeAttached(int availableTop, int attachedAvailableBottom,
                                                  int menuGap, int cellTop, int cellHeight,
                                                  int menuHeight, int desiredMinMenuHeight,
                                                  int reactionsTopOffset, int minMenuHeight,
                                                  int minMessageVisibleSpace, int minMenuHeightFloor,
                                                  int minY) {
        final int availableHeight = attachedAvailableBottom - availableTop;
        float translationY = computeTranslationY(availableTop, attachedAvailableBottom,
                menuGap, cellTop, cellHeight);
        final int cellBottom = cellTop + cellHeight;
        final boolean keepMessageVisible =
                cellHeight <= availableHeight - minMessageVisibleSpace - menuGap;
        final int effectiveDesiredMinMenuHeight = Math.min(menuHeight, desiredMinMenuHeight);
        final int effectiveMinMenuHeight = keepMessageVisible
                ? Math.max(minMenuHeight, Math.min(effectiveDesiredMinMenuHeight,
                        availableHeight - cellHeight - menuGap))
                : Math.min(effectiveDesiredMinMenuHeight,
                        Math.max(minMenuHeight, availableHeight / 2));
        final int maxMessageBottom = attachedAvailableBottom - effectiveMinMenuHeight - menuGap;
        int desiredMenuTop = (int) (cellBottom + translationY) + menuGap;
        if (desiredMenuTop - menuGap > maxMessageBottom) {
            translationY -= desiredMenuTop - menuGap - maxMessageBottom;
            desiredMenuTop = maxMessageBottom + menuGap;
        }
        if (keepMessageVisible) {
            final int minAllowedTop = Math.min(availableTop,
                    attachedAvailableBottom - effectiveMinMenuHeight - menuGap - cellHeight);
            if (cellTop + translationY < minAllowedTop) {
                translationY += minAllowedTop - (cellTop + translationY);
                desiredMenuTop = (int) (cellBottom + translationY) + menuGap;
            }
        }
        int popupY = desiredMenuTop - reactionsTopOffset;
        popupY = Math.max(popupY, minY);
        final int maxHeight = Math.max(minMenuHeightFloor,
                attachedAvailableBottom - popupY - menuGap);
        return new AttachedLayout(translationY, popupY, maxHeight);
    }
}
