package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.UserConfig;

/**
 * @deprecated Use {@link HgramSecretSpace} with an explicit account.
 */
@Deprecated
public final class HgramDecoySpace {

    private HgramDecoySpace() {
    }

    private static int account() {
        return Math.max(0, UserConfig.selectedAccount);
    }

    public static boolean isHidden(long dialogId) {
        return HgramSecretSpace.isHidden(account(), dialogId);
    }

    public static boolean toggle(long dialogId) {
        return HgramSecretSpace.toggle(account(), dialogId);
    }

    public static int hiddenCount() {
        return HgramSecretSpace.hiddenCount(account());
    }

    public static void clear() {
        HgramSecretSpace.clear(account());
    }
}
