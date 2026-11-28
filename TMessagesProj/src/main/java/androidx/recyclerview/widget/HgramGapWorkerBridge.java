package androidx.recyclerview.widget;

/** Package bridge for Hgram refresh-rate changes; keeps upstream RecyclerView untouched. */
public final class HgramGapWorkerBridge {

    private HgramGapWorkerBridge() {
    }

    /**
     * Updates GapWorker's prefetch cadence after a window refresh-rate change. GapWorker caches
     * the interval on the first attach, so the Hgram refresh toggle needs this package-local hook.
     */
    public static void updateFrameIntervalForRefreshRate(float hz) {
        if (hz < 30.0f) {
            return;
        }
        GapWorker worker = GapWorker.sGapWorker.get();
        if (worker != null) {
            worker.mFrameIntervalNs = (long) (1000000000 / hz);
        }
    }
}
