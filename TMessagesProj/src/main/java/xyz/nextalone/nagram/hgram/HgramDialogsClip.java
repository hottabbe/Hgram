package xyz.nextalone.nagram.hgram;

/** Shared geometry for the dialogs list clip that must follow the real panel edges. */
public final class HgramDialogsClip {

    private HgramDialogsClip() {
    }

    /**
     * Converts an absolute panel bottom edge into a list-local clip.
     *
     * @param panelBottom bottom edge of the lowest visible panel in screen coordinates
     * @param listTop     top edge of the dialogs list in screen coordinates
     * @param listHeight  measured height of the dialogs list
     * @return non-negative clipped height, never exceeding the list height
     */
    public static int clipTop(float panelBottom, float listTop, int listHeight) {
        if (listHeight <= 0) return 0;
        final int local = (int) Math.ceil(panelBottom - listTop);
        if (local <= 0) return 0;
        return Math.min(local, listHeight);
    }

    /** A DOWN below the clip must reach the panel, not the list. */
    public static boolean acceptsDown(float y, int clipTop) {
        return y >= clipTop;
    }

    /** Single publication point: equal edges do not invalidate, changed edges do. */
    public static final class Cache {
        public int clipTop;

        public boolean commit(int next) {
            if (next == clipTop) return false;
            clipTop = next;
            return true;
        }
    }
}
