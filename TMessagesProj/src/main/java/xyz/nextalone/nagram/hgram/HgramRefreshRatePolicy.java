package xyz.nextalone.nagram.hgram;

/** Pure refresh-rate gate; keeps the runtime policy testable without a device. */
public final class HgramRefreshRatePolicy {

    private HgramRefreshRatePolicy() {
    }

    public static final class Result {
        public final int preferredDisplayModeId;
        public final int targetFps;
        public final boolean updateFrameInterval;
        public final float frameIntervalHz;
        public final boolean updateLayout;

        Result(int preferredDisplayModeId, int targetFps,
               boolean updateFrameInterval, float frameIntervalHz, boolean updateLayout) {
            this.preferredDisplayModeId = preferredDisplayModeId;
            this.targetFps = targetFps;
            this.updateFrameInterval = updateFrameInterval;
            this.frameIntervalHz = frameIntervalHz;
            this.updateLayout = updateLayout;
        }
    }

    /** Selects the highest-refresh mode with the current resolution; 0 when none matches. */
    public static int pickPinnedModeId(int[] ids, int[] widths, int[] heights, float[] rates,
                                       int currentWidth, int currentHeight) {
        int bestId = 0;
        float bestRate = -1f;
        final int count = Math.min(Math.min(ids.length, widths.length),
                Math.min(heights.length, rates.length));
        for (int i = 0; i < count; i++) {
            if (widths[i] != currentWidth || heights[i] != currentHeight) {
                continue;
            }
            if (rates[i] > bestRate) {
                bestRate = rates[i];
                bestId = ids[i];
            }
        }
        return bestId;
    }

    public static Result resolve(boolean boost, boolean wasBoost, int previousModeId,
                                 int pinnedModeId, float rate, boolean rateUnchanged,
                                 float upstreamFrameHz) {
        final int newModeId = boost ? pinnedModeId : 0;
        final boolean modeChanged = newModeId != previousModeId;
        return new Result(newModeId,
                boost ? Math.round(rate) : 60,
                boost || wasBoost,
                boost ? rate : upstreamFrameHz,
                !rateUnchanged || modeChanged);
    }
}
