package androidx.recyclerview.widget;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GapWorkerIntervalTest {

    private GapWorker worker;

    @Before
    public void setUp() {
        worker = new GapWorker();
        worker.mFrameIntervalNs = 123456789L;
        GapWorker.sGapWorker.set(worker);
    }

    @Test
    public void boostIntervalUsesRequestedRate() {
        HgramGapWorkerBridge.updateFrameIntervalForRefreshRate(120f);
        assertEquals((long) (1000000000 / 120f), worker.mFrameIntervalNs);
    }

    @Test
    public void offIntervalUsesDisplayFallback() {
        HgramGapWorkerBridge.updateFrameIntervalForRefreshRate(60f);
        assertEquals((long) (1000000000 / 60f), worker.mFrameIntervalNs);
    }

    @Test
    public void invalidRateDoesNotChangeInterval() {
        HgramGapWorkerBridge.updateFrameIntervalForRefreshRate(10f);
        assertEquals(123456789L, worker.mFrameIntervalNs);
    }
}
