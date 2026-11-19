package xyz.nextalone.nagram.hgram;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Lightweight PCM compressor/limiter for ExoPlayer audio output. */
@UnstableApi
public final class HgramLoudnessAudioProcessor extends BaseAudioProcessor {

    private static final float TARGET_LEVEL = 0.22f;
    private static final float NOISE_FLOOR = 0.0015f;
    private static final float MAX_GAIN = 6.0f;
    private static final float LIMITER = 0.97f;

    private float envelope;
    private float gain = 1f;

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException("hgram loudness processor requires PCM16 or PCM float", inputAudioFormat);
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return;
        }
        int bytes = inputBuffer.remaining();
        ByteOrder originalOrder = inputBuffer.order();
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = replaceOutputBuffer(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int samples = bytes / (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ? 4 : 2);
        for (int i = 0; i < samples; i++) {
            float sample = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                    ? inputBuffer.getFloat()
                    : inputBuffer.getShort() / 32768f;
            sample = HgramAudioMath.sanitize(sample);
            float magnitude = Math.abs(sample);
            if (magnitude > envelope) {
                envelope += (magnitude - envelope) * 0.4f;
            } else {
                envelope *= 0.9985f;
            }

            float targetGain = 1f;
            if (envelope > TARGET_LEVEL) {
                targetGain = Math.max(0.25f, TARGET_LEVEL / envelope);
            } else if (envelope > NOISE_FLOOR) {
                targetGain = Math.min(MAX_GAIN, TARGET_LEVEL / envelope);
            }
            float smoothing = targetGain < gain ? 0.2f : 0.003f;
            gain += (targetGain - gain) * smoothing;
            float outputSample = Math.max(-LIMITER, Math.min(LIMITER, sample * gain));
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                output.putFloat(outputSample);
            } else {
                output.putShort((short) (outputSample * 32767f));
            }
        }
        inputBuffer.order(originalOrder);
        output.flip();
    }

    @Override
    protected void onFlush() {
        envelope = 0f;
        gain = 1f;
    }

    @Override
    protected void onReset() {
        envelope = 0f;
        gain = 1f;
    }
}
