package xyz.nextalone.nagram.hgram;

import java.lang.ref.WeakReference;

/**
 * Bridges download-worker callbacks to a viewer without keeping it (or its Activity) alive.
 * The worker only captures this object; the viewer is reachable through a weak reference and
 * is explicitly detached when the viewer is destroyed or abandons its download session.
 */
public final class HgramOcrDownloadCallback {

    public interface Target {
        void onOcrModelsDownloadProgress(long generation, String photoKey, long jobId, int progress);

        void onOcrModelsDownloadFinished(long generation, String photoKey, long jobId, boolean success);
    }

    public interface Poster {
        void post(Runnable runnable);
    }

    private final WeakReference<Target> target;
    private final long generation;
    private final String photoKey;
    private final Poster poster;

    public HgramOcrDownloadCallback(Target target, long generation, String photoKey, Poster poster) {
        this.target = new WeakReference<>(target);
        this.generation = generation;
        this.photoKey = photoKey;
        this.poster = poster;
    }

    /** Drops the viewer reference; later worker callbacks become no-ops. */
    public void detach() {
        target.clear();
    }

    boolean isDetachedForTests() {
        return target.get() == null;
    }

    public void onProgress(long jobId, int progress) {
        if (target.get() == null) {
            return;
        }
        poster.post(() -> {
            final Target current = target.get();
            if (current != null) {
                current.onOcrModelsDownloadProgress(generation, photoKey, jobId, progress);
            }
        });
    }

    public void onFinished(long jobId, boolean success) {
        if (target.get() == null) {
            return;
        }
        poster.post(() -> {
            final Target current = target.get();
            if (current != null) {
                current.onOcrModelsDownloadFinished(generation, photoKey, jobId, success);
            }
        });
    }
}
