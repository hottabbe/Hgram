package xyz.nextalone.nagram.hgram;

final class HgramAudioMath {
    private HgramAudioMath() {
    }

    static float sanitize(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? 0f : value;
    }
}
