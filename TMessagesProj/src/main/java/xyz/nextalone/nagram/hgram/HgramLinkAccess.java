package xyz.nextalone.nagram.hgram;

/** Account/lock/workspace gate for deep links that are presented asynchronously. */
public final class HgramLinkAccess {

    private HgramLinkAccess() {
    }

    public static boolean canOpen(int account, int currentAccount, boolean appLocked, long dialogId) {
        return account == currentAccount
                && !appLocked
                && HgramSecretSpace.canOpenDialog(account, dialogId);
    }
}
