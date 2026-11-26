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

    /**
     * During a pull-down the list rows are translated by {@code storiesOverscroll} while the
     * static list padding stays the same. The clip must follow the rows, otherwise the space
     * between the header and the translated rows stays visible as an empty band until the
     * 200 ms overscroll animator returns to zero.
     */
    public static int effectivePaddingTop(int paddingTop, float storiesOverscroll) {
        final int safePadding = Math.max(0, paddingTop);
        if (!(storiesOverscroll > 0f)) {
            return safePadding;
        }
        return safePadding + (int) Math.ceil(storiesOverscroll);
    }

    /** A DOWN below the clip must reach the panel, not the list. */
    public static boolean acceptsDown(float y, int clipTop) {
        return y >= clipTop;
    }

    /** Result of the complete clip computation, reused by logging and the cache. */
    public static final class Clip {
        public final int computed;
        public final int paddingTop;
        public final int clipTop;

        public Clip(int computed, int paddingTop, int clipTop) {
            this.computed = computed;
            this.paddingTop = paddingTop;
            this.clipTop = clipTop;
        }
    }

    /**
     * Single composition used by the dialogs list: absolute header edge -> local computed clip ->
     * overscroll-aware padding clamp. Kept pure so the on-screen path can be unit tested.
     */
    public static Clip resolveClipTop(float headerBottom, float listTop, float scale, int listHeight,
                                      int staticPaddingTop, float storiesOverscroll) {
        final float adjustedBottom = scale > 0f ? (headerBottom - listTop) / scale : 0f;
        final int computed = clipTop(adjustedBottom, 0, listHeight);
        final int paddingTop = effectivePaddingTop(staticPaddingTop, storiesOverscroll);
        return new Clip(computed, paddingTop, clampToPadding(computed, paddingTop));
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
