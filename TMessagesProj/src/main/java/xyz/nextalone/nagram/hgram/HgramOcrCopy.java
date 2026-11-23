package xyz.nextalone.nagram.hgram;

/**
 * Copy entry point for the inline OCR layout. The only allowed side effect is the injected
 * clipboard sink: this class must never reference Intents, ShareAlert or an ActionMode.
 */
public final class HgramOcrCopy {

    public interface ClipboardSink {
        void write(String text);
    }

    private HgramOcrCopy() {
    }

    /** Returns true when a non-empty text was handed to the clipboard sink. */
    public static boolean copy(String text, ClipboardSink sink) {
        if (text == null || text.isEmpty() || sink == null) {
            return false;
        }
        sink.write(text);
        return true;
    }
}
