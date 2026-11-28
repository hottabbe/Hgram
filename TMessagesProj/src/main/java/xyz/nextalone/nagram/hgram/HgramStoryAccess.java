package xyz.nextalone.nagram.hgram;

/** Account-bound story entry policy used by deep links and push callbacks. */
public final class HgramStoryAccess {

    private HgramStoryAccess() {
    }

    public static boolean canOpen(int storyAccount, int currentAccount, boolean locked, long peerId) {
        return storyAccount == currentAccount
                && !locked
                && HgramSecretSpace.canOpenDialog(storyAccount, peerId);
    }
}
