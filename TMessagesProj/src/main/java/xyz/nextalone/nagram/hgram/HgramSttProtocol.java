package xyz.nextalone.nagram.hgram;

/**
 * Wire protocol between the main process and the isolated {@code :stt} transcription process.
 * Only primitives/strings are transferred, so the service has no dependency on MessageObject
 * or any other main-process state.
 */
public final class HgramSttProtocol {
    public static final int MSG_START = 1;
    public static final int MSG_CANCEL = 2;
    public static final int MSG_PARTIAL = 3;
    public static final int MSG_DONE = 4;
    public static final int MSG_ERROR = 5;
    public static final int MSG_STARTED = 6;

    public static final int ERROR_NONE = 0;
    public static final int ERROR_BUSY = 1;
    public static final int ERROR_LOW_MEMORY = 2;
    public static final int ERROR_PROCESS = 3;
    public static final int ERROR_GENERAL = 4;

    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_ENGINE = "engine";
    public static final String EXTRA_LANGUAGE = "language";
    public static final String EXTRA_CACHE_FREE_MB = "cache_free_mb";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_CANCELLED = "cancelled";
    public static final String EXTRA_ERROR = "error";

    private HgramSttProtocol() {
    }
}
