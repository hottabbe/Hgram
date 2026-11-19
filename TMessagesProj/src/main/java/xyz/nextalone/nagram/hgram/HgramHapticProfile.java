package xyz.nextalone.nagram.hgram;

import android.content.SharedPreferences;

/** One customizable haptic profile per application place. */
public final class HgramHapticProfile {

    public static final String PLACE_TOGGLES = "toggles";
    public static final String PLACE_LIST_ITEMS = "list_items";
    public static final String PLACE_LONG_PRESS = "long_press";
    public static final String PLACE_MESSAGE_ACTIONS = "message_actions";
    public static final String PLACE_VOICE_RECORD = "voice_record";
    public static final String PLACE_EMOJI_STICKERS = "emoji_stickers";
    public static final String PLACE_ALBUMS_SWIPE = "albums_swipe";
    public static final String PLACE_STORIES = "stories";
    public static final String PLACE_CALLS = "calls";
    public static final String PLACE_CAMERA_PAINT = "camera_paint";
    public static final String PLACE_PASSCODE_LOCK = "passcode_lock";
    public static final String PLACE_ANTIPEEK = "anti_peek";
    public static final String PLACE_SECRET_SPACE = "secret_space";
    public static final String PLACE_TEXT_CURSOR = "text_cursor";
    public static final String PLACE_MINIAPPS = "miniapps";

    /** Backwards-compatible aliases for HAPTIC-2 profiles. */
    public static final String PLACE_TOGGLE = PLACE_TOGGLES;
    public static final String PLACE_ALBUM = PLACE_ALBUMS_SWIPE;
    public static final String PLACE_HINT = PLACE_LONG_PRESS;
    public static final String PLACE_RESET = PLACE_LIST_ITEMS;
    public static final String PLACE_UNLOCK = PLACE_SECRET_SPACE;

    public static final String[] PLACES = {
            PLACE_TOGGLES, PLACE_LIST_ITEMS, PLACE_LONG_PRESS, PLACE_MESSAGE_ACTIONS,
            PLACE_VOICE_RECORD, PLACE_EMOJI_STICKERS, PLACE_ALBUMS_SWIPE, PLACE_STORIES,
            PLACE_CALLS, PLACE_CAMERA_PAINT, PLACE_PASSCODE_LOCK, PLACE_ANTIPEEK,
            PLACE_SECRET_SPACE, PLACE_TEXT_CURSOR, PLACE_MINIAPPS
    };

    public int intensity;
    public int durationMs;
    public int pattern;
    public int gapMs;

    public HgramHapticProfile(int intensity, int durationMs, int pattern, int gapMs) {
        this.intensity = clamp(intensity, 0, 255);
        this.durationMs = clamp(durationMs, 10, 300);
        this.pattern = clamp(pattern, 0, 2);
        this.gapMs = clamp(gapMs, 20, 200);
    }

    public static HgramHapticProfile defaults(String place) {
        if (PLACE_LIST_ITEMS.equals(place)) {
            return new HgramHapticProfile(50, 20, 0, 60);
        }
        if (PLACE_LONG_PRESS.equals(place)) {
            return new HgramHapticProfile(40, 15, 0, 60);
        }
        if (PLACE_MESSAGE_ACTIONS.equals(place)) {
            return new HgramHapticProfile(70, 30, 0, 60);
        }
        if (PLACE_VOICE_RECORD.equals(place)) {
            return new HgramHapticProfile(80, 35, 1, 60);
        }
        if (PLACE_EMOJI_STICKERS.equals(place)) {
            return new HgramHapticProfile(45, 15, 0, 60);
        }
        if (PLACE_ALBUMS_SWIPE.equals(place)) {
            return new HgramHapticProfile(50, 20, 0, 60);
        }
        if (PLACE_STORIES.equals(place)) {
            return new HgramHapticProfile(55, 20, 0, 60);
        }
        if (PLACE_CALLS.equals(place)) {
            return new HgramHapticProfile(90, 40, 0, 60);
        }
        if (PLACE_CAMERA_PAINT.equals(place)) {
            return new HgramHapticProfile(50, 20, 0, 60);
        }
        if (PLACE_PASSCODE_LOCK.equals(place)) {
            return new HgramHapticProfile(80, 30, 0, 60);
        }
        if (PLACE_ANTIPEEK.equals(place)) {
            return new HgramHapticProfile(70, 30, 0, 60);
        }
        if (PLACE_SECRET_SPACE.equals(place)) {
            return new HgramHapticProfile(70, 30, 0, 60);
        }
        if (PLACE_TEXT_CURSOR.equals(place)) {
            return new HgramHapticProfile(40, 10, 0, 60);
        }
        if (PLACE_MINIAPPS.equals(place)) {
            return new HgramHapticProfile(60, 25, 0, 60);
        }
        return new HgramHapticProfile(60, 30, 0, 60);
    }

    public static HgramHapticProfile of(String place) {
        String raw = preferences().getString(key(place), null);
        if (raw != null) {
            String[] parts = raw.split(",");
            if (parts.length == 4) {
                try {
                    return new HgramHapticProfile(Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()),
                            Integer.parseInt(parts[3].trim()));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return defaults(place);
    }

    public HgramHapticProfile copy() {
        return new HgramHapticProfile(intensity, durationMs, pattern, gapMs);
    }

    public void save(String place) {
        preferences().edit().putString(key(place),
                intensity + "," + durationMs + "," + pattern + "," + gapMs).apply();
    }

    public static void reset(String place) {
        preferences().edit().remove(key(place)).apply();
    }

    public static void resetAll() {
        android.content.SharedPreferences.Editor editor = preferences().edit();
        for (String place : PLACES) {
            editor.remove(key(place));
        }
        editor.apply();
    }

    public String summary() {
        String repeat = pattern == 0 ? "single" : pattern == 1 ? "double" : "triple";
        int percent = Math.round(intensity / 255f * 100f);
        return percent + "% · " + durationMs + " ms · " + repeat;
    }

    public static String key(String place) {
        return "haptic_profile_" + (place == null ? PLACE_TOGGLE : place);
    }

    private static SharedPreferences preferences() {
        return HgramConfig.preferences();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
