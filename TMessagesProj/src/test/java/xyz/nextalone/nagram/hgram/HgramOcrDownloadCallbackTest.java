package xyz.nextalone.nagram.hgram;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HgramOcrDownloadCallbackTest {

    private static final class Target implements HgramOcrDownloadCallback.Target {
        int progress;
        int finished;

        @Override
        public void onOcrModelsDownloadProgress(long generation, String photoKey, long jobId, int progress) {
            this.progress += progress;
        }

        @Override
        public void onOcrModelsDownloadFinished(long generation, String photoKey, long jobId, boolean success) {
            finished++;
        }
    }

    @Test
    public void detachedCallbackDoesNotHoldOrPublishViewer() {
        final Target target = new Target();
        final HgramOcrDownloadCallback callback = new HgramOcrDownloadCallback(
                target, 7L, "m:1:2", Runnable::run);

        callback.onProgress(11L, 25);
        callback.onFinished(11L, true);
        assertEquals(25, target.progress);
        assertEquals(1, target.finished);

        callback.detach();
        assertTrue(callback.isDetachedForTests());
        callback.onProgress(12L, 50);
        callback.onFinished(12L, true);
        assertEquals("detached viewer must not receive progress", 25, target.progress);
        assertEquals("detached viewer must not receive completion", 1, target.finished);
    }
}
