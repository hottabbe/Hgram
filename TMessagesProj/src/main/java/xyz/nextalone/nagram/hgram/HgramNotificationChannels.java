package xyz.nextalone.nagram.hgram;

import android.app.NotificationManager;
import android.net.Uri;

import org.telegram.messenger.Utilities;

/**
 * Pure helpers for the notification-channel importance policy. The same effective importance
 * must be used for creation, comparison and settings hashing, otherwise an auto-promoted
 * channel looks like a user edit on the next notification and gets recreated with a new id.
 */
public final class HgramNotificationChannels {

    private HgramNotificationChannels() {
    }

    public static int effectiveImportance(int importance, boolean silent,
                                          boolean promoteChatChannel) {
        if (!HgramConfig.isNotificationImportanceBoostEnabled()) {
            return importance;
        }
        if (!silent && promoteChatChannel
                && importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            return Math.max(importance, NotificationManager.IMPORTANCE_HIGH);
        }
        return importance;
    }

    /**
     * In-app channels compare the requested importance with the real channel. LOW/MIN/NONE/MAX are
     * explicit user choices and must survive this comparison; any other mismatch is recreated.
     */
    public static boolean shouldOverwriteInApp(int effectiveImportance, int channelImportance) {
        return channelImportance != effectiveImportance
                && !isExplicitUserImportance(channelImportance);
    }

    /**
     * LOW/MIN/NONE/MAX are explicit user choices in the system channel settings. They must be
     * accepted as-is for in-app custom channels; otherwise the next validation rebuilds the
     * requested HIGH hash and recreates the channel with a new id.
     */
    public static boolean acceptsSystemOverride(boolean isInApp, boolean isDefault,
                                                int actualImportance) {
        return isInApp && !isDefault && isExplicitUserImportance(actualImportance);
    }

    /**
     * Importance for a channel that has to be (re)created. When an in-app custom channel already
     * has an accepted system override, a new sound URI changes the lookup key and forces a new
     * channel; the new channel must inherit the user's actual importance instead of HIGH.
     */
    public static int creationImportance(boolean isInApp, boolean isDefault,
                                         int requestedImportance, int acceptedImportance) {
        if (!isInApp || isDefault) {
            return requestedImportance;
        }
        return isExplicitUserImportance(acceptedImportance)
                ? acceptedImportance : requestedImportance;
    }

    /**
     * Resolves the accepted system importance that may still be used locally in the current
     * validation pass. The real channel is authoritative once it exists: an explicit
     * LOW/MIN/NONE/MAX is kept, while DEFAULT/HIGH or an unset/UNSPECIFIED value means the user
     * reset the override and the stale stored value must not be carried into a recreate.
     * With no existing channel ({@code -1}) the stored value is used so a sound-key change can
     * still inherit an accepted override from the previous channel.
     */
    public static int effectiveAcceptedImportance(int storedAcceptedImportance,
                                                  int actualChannelImportance) {
        if (isExplicitUserImportance(actualChannelImportance)) {
            return actualChannelImportance;
        }
        if (actualChannelImportance == -1) {
            return isExplicitUserImportance(storedAcceptedImportance)
                    ? storedAcceptedImportance : -1;
        }
        return -1;
    }

    /**
     * Final hash used by the delete/recreate decision. Once an in-app custom channel has an
     * accepted system override, its real settings hash is authoritative; rebuilding it from the
     * requested settings would lose the user's LOW/MIN/NONE/MAX choice.
     */
    public static String finalChannelSettingsHash(boolean isInApp, boolean isDefault,
                                                  int actualImportance, String actualSettingsHash,
                                                  long[] vibrationPattern, int ledColor, Uri sound,
                                                  int requestedImportance, boolean secret) {
        if (acceptsSystemOverride(isInApp, isDefault, actualImportance)
                && actualSettingsHash != null) {
            return actualSettingsHash;
        }
        return channelSettingsHash(vibrationPattern, ledColor, sound, requestedImportance, secret);
    }

    private static boolean isExplicitUserImportance(int importance) {
        return importance == NotificationManager.IMPORTANCE_LOW
                || importance == NotificationManager.IMPORTANCE_MIN
                || importance == NotificationManager.IMPORTANCE_NONE
                || importance == NotificationManager.IMPORTANCE_MAX;
    }

    public static String channelSettingsHash(long[] vibrationPattern, int ledColor, Uri sound,
                                             int importance, boolean secret) {
        final StringBuilder settings = new StringBuilder();
        if (vibrationPattern != null) {
            for (long value : vibrationPattern) {
                settings.append(value);
            }
        }
        settings.append(ledColor);
        if (sound != null) {
            settings.append(sound);
        }
        settings.append(importance);
        if (secret) {
            settings.append("secret");
        }
        return Utilities.MD5(settings.toString());
    }
}
