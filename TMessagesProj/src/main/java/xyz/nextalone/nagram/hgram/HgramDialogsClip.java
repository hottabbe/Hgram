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

    /**
     * The clip may never cut into the list padding: a stale header bottom above the list would
     * otherwise hide the first rows and look like an empty gap under the tab strip.
     */
    public static int clampToPadding(int computed, int paddingTop) {
        final int safePadding = Math.max(0, paddingTop);
        return Math.max(0, Math.min(computed, safePadding));
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
