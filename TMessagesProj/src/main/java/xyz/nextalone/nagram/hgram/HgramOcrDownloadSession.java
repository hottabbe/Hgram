package xyz.nextalone.nagram.hgram;

/**
 * Immutable-ish identity for the OCR model download owned or observed by one PhotoViewer session.
 * Every photo/session switch bumps {@link #generation()}; callbacks captured for an older
 * generation can never complete the current session. Only a job id assigned by
 * {@link #own(long, long)} for the current generation may be cancelled by the viewer.
 */
public final class HgramOcrDownloadSession {

    private long generation;
    private String photoKey = "";
    private long ownedJobId;

    public long generation() {
        return generation;
    }

    public String photoKey() {
        return photoKey;
    }

    /** Starts a new session generation for the supplied stable photo key. */
    public long switchTo(String photoKey) {
        generation++;
        this.photoKey = photoKey == null ? "" : photoKey;
        return generation;
    }

    public boolean isCurrent(long generation, String photoKey) {
        return this.generation == generation
                && this.photoKey.equals(photoKey == null ? "" : photoKey);
    }

    public boolean isCurrent(String photoKey) {
        return isCurrent(generation, photoKey);
    }

    /**
     * Claims the supplied job for the current generation. A stale generation (the callback raced a
     * photo switch) is ignored so a new session cannot adopt an old transfer.
     */
    public void own(long generation, long jobId) {
        if (this.generation == generation && jobId != 0) {
            ownedJobId = jobId;
        }
    }

    public long ownedJobId() {
        return ownedJobId;
    }

    public boolean owns(long jobId) {
        return jobId != 0 && ownedJobId == jobId;
    }

    /** Returns the owned job once; the caller may cancel it, then the session no longer owns it. */
    public long detachAndTakeOwned() {
        final long jobId = ownedJobId;
        ownedJobId = 0;
        return jobId;
    }
}
