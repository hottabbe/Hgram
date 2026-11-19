package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.UserConfig;

/**
 * Pure account-aware decisions for pinned/dynamic Telegram shortcuts.
 * Kept free of Android UI and shortcut-manager dependencies so it can be unit tested.
 */
public final class HgramShortcutState {

    public static final int SKIP = 0;
    public static final int NEUTRAL = 1;
    public static final int RESTORE = 2;
    public static final int NOOP = 3;

    public static final int TYPE_UNKNOWN = -1;
    public static final int TYPE_USER_OR_CHAT = 0;
    public static final int TYPE_ATTACHED_BOT = 1;

    private HgramShortcutState() {
    }

    /**
     * Returns the shortcut type encoded in its id, or TYPE_UNKNOWN for ids we do not own.
     */
    public static int typeForId(String id) {
        if (id == null) {
            return TYPE_UNKNOWN;
        }
        if (id.startsWith("sdid_") || id.startsWith("ndid_") || id.startsWith("did3_")) {
            return TYPE_USER_OR_CHAT;
        }
        if (id.startsWith("bdid_")) {
            return TYPE_ATTACHED_BOT;
        }
        return TYPE_UNKNOWN;
    }

    /**
     * Returns the dialog/bot id encoded in a shortcut id, or 0 when the id is not ours.
     */
    public static long dialogIdForId(String id) {
        if (typeForId(id) == TYPE_UNKNOWN) {
            return 0;
        }
        final int separator = id.indexOf('_');
        if (separator < 0 || separator + 1 >= id.length()) {
            return 0;
        }
        try {
            return Long.parseLong(id.substring(separator + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Decides what a reconcile pass must do with a shortcut whose owner came from its intent.
     * Invalid owner or dialog id is fail-closed: keep the shortcut neutral instead of restoring a real name.
     */
    public static int decide(int ownerAccount, long dialogId, boolean hidden) {
        if (ownerAccount < 0 || ownerAccount >= UserConfig.MAX_ACCOUNT_COUNT || dialogId == 0) {
            return SKIP;
        }
        return hidden ? NEUTRAL : RESTORE;
    }

    /**
     * Reconcile decision that avoids churn: a visible shortcut is restored only when this
     * process previously neutralized it. Everything else is a no-op.
     */
    public static int reconcileDecision(int ownerAccount, long dialogId, boolean hidden, boolean neutralized) {
        final int decision = decide(ownerAccount, dialogId, hidden);
        if (decision != RESTORE) {
            return decision;
        }
        return neutralized ? RESTORE : NOOP;
    }
}
