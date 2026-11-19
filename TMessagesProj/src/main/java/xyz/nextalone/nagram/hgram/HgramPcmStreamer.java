package xyz.nextalone.nagram.hgram;

/**
 * Bounded PCM16 pipeline for offline voice processing.
 *
 * <p>Decoded audio is resampled to mono PCM16 and handed to the sink in fixed-size chunks as soon
 * as they fill. The streamer never accumulates the recording, so the retained buffer stays constant
 * no matter how long the recording is.
 */
public final class HgramPcmStreamer {

    /** Consumer of streamed PCM chunks. */
    public interface Sink {
        /**
         * @param chunk mono PCM16 little-endian samples; the array is reused once the call returns
         * @param length number of valid bytes in {@code chunk}
         * @return false to stop processing
         */
        boolean accept(byte[] chunk, int length) throws Exception;
    }

    /** 0.5 s of mono PCM16 at 16 kHz, matching the chunk size used by the offline recognizers. */
    public static final int CHUNK_BYTES = 8000;

    private final int targetRate;
    private final byte[] chunk = new byte[CHUNK_BYTES];
    private int chunkSize;
    private long phase;

    public HgramPcmStreamer(int targetRate) {
        if (targetRate <= 0) {
            throw new IllegalArgumentException("Invalid target sample rate");
        }
        this.targetRate = targetRate;
    }

    /** Bytes currently held by the streamer; bounded by {@link #CHUNK_BYTES}. */
    public int retainedBytes() {
        return chunkSize;
    }

    /**
     * Resamples a mono sample recorded at {@code sourceRate} and emits full chunks to the sink.
     *
     * @return false when the sink asked to stop
     */
    public boolean writeSample(float sample, int sourceRate, Sink sink) throws Exception {
        phase += targetRate;
        while (sourceRate > 0 && phase >= sourceRate) {
            phase -= sourceRate;
            short pcm = (short) (Math.max(-1f, Math.min(1f, sample)) * 32767f);
            chunk[chunkSize++] = (byte) (pcm & 0xff);
            chunk[chunkSize++] = (byte) ((pcm >>> 8) & 0xff);
            if (chunkSize == CHUNK_BYTES) {
                if (!sink.accept(chunk, CHUNK_BYTES)) {
                    return false;
                }
                chunkSize = 0;
            }
        }
        return true;
    }

    /**
     * Resamples mono samples recorded at {@code sourceRate} and emits full chunks to the sink.
     *
     * @return false when the sink asked to stop
     */
    public boolean write(float[] samples, int sourceRate, Sink sink) throws Exception {
        for (float sample : samples) {
            if (!writeSample(sample, sourceRate, sink)) {
                return false;
            }
        }
        return true;
    }

    /** Emits the trailing partial chunk, if any. */
    public boolean flush(Sink sink) throws Exception {
        if (chunkSize == 0) {
            return true;
        }
        int length = chunkSize;
        chunkSize = 0;
        return sink.accept(chunk, length);
    }

    /** Converts mono PCM16 little-endian samples to normalized floats. */
    public static float[] pcm16ToSamples(byte[] data) {
        return pcm16ToSamples(data, data.length);
    }

    /**
     * Converts the first {@code length} bytes of mono PCM16 little-endian data to normalized floats.
     *
     * @param data source buffer
     * @param length number of valid bytes in {@code data}; must be even
     */
    public static float[] pcm16ToSamples(byte[] data, int length) {
        int count = length / 2;
        float[] samples = new float[count];
        for (int i = 0; i < count; i++) {
            samples[i] = pcm16Sample(data[2 * i] & 0xff, data[2 * i + 1] & 0xff);
        }
        return samples;
    }

    /** Converts one little-endian PCM16 sample to a normalized float. */
    public static float pcm16Sample(int low, int high) {
        return (short) ((high << 8) | low) / 32768f;
    }
}
