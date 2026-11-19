package tw.nekomimi.nekogram.settings;

/** One-shot reset pipeline: config reset, search cleanup, row rebuild, single adapter notify. */
final class HgramSettingsReset {

    interface Host {
        void resetConfig();
        void applyAntiPeek();
        String currentEngine();
        void clearSearchQuery();
        void setSearchCallbackSuppressed(boolean suppressed);
        void clearSearchField();
        void applyVisibleRows(int[] rows);
        void notifyAdapterOnce();
    }

    private HgramSettingsReset() {
    }

    static void run(Host host) {
        host.resetConfig();
        host.applyAntiPeek();
        // Clear the field with the watcher suppressed so setText() cannot start a second
        // filter/rebuild pass. The reset itself notifies exactly once at the end.
        host.setSearchCallbackSuppressed(true);
        try {
            host.clearSearchQuery();
            host.clearSearchField();
        } finally {
            host.setSearchCallbackSuppressed(false);
        }
        host.applyVisibleRows(HgramSettingsActivity.buildBaseVisibleRows(host.currentEngine()));
        host.notifyAdapterOnce();
    }
}
