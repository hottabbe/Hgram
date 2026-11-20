package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.R;

import java.util.Locale;

/** Built-in haptic effect for one place; {@link #AUTO} keeps the legacy amplitude profile. */
public enum HgramHapticEffect {
    AUTO, TICK, CLICK, THUD, POP, RISE, FALL, SPIN;

    /** Stable, locale-independent name used in the CSV profile storage. */
    public String csvName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Never throws: null, blank and unknown values fall back to {@link #AUTO}. */
    public static HgramHapticEffect parse(String value) {
        if (value == null) {
            return AUTO;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return AUTO;
        }
        for (HgramHapticEffect effect : values()) {
            if (effect.csvName().equalsIgnoreCase(trimmed)) {
                return effect;
            }
        }
        return AUTO;
    }

    public int localizedNameRes() {
        switch (this) {
            case TICK:
                return R.string.HgramHapticTypeTick;
            case CLICK:
                return R.string.HgramHapticTypeClick;
            case THUD:
                return R.string.HgramHapticTypeThud;
            case POP:
                return R.string.HgramHapticTypePop;
            case RISE:
                return R.string.HgramHapticTypeRise;
            case FALL:
                return R.string.HgramHapticTypeFall;
            case SPIN:
                return R.string.HgramHapticTypeSpin;
            case AUTO:
            default:
                return R.string.HgramHapticTypeAuto;
        }
    }
}
