package xyz.nextalone.nagram.hgram;

/** Single effective content-protection policy shared by OCR overlay rendering and copy. */
public final class HgramOcrProtection {

    public static final int TTL_PROTECTION_THRESHOLD = 60 * 60;

    private HgramOcrProtection() {
    }

    /** Protected avatar viewers have no message object, so the predicate cannot rely on it. */
    public static boolean isProtectedAvatar(long avatarsDialogId, boolean peerNoForwardsWithOverride) {
        return avatarsDialogId != 0 && peerNoForwardsWithOverride;
    }

    /**
     * @param messageNoForwards peer-level noforwards, message noforwards or revealed media
     * @param avatarNoForwards {@link #isProtectedAvatar(long, boolean)} result
     * @param encryptedChat current secret chat
     * @param ttl message TTL in seconds
     * @param hasSecureDocuments viewer holds secure documents
     * @param embedVideo embedded video viewer
     * @param allowCopyOverrides {@code NekoXConfig.disableFlagSecure || NaConfig.getForceCopy}
     */
    public static boolean isCopyProtected(
            boolean messageNoForwards,
            boolean avatarNoForwards,
            boolean encryptedChat,
            int ttl,
            boolean hasSecureDocuments,
            boolean embedVideo,
            boolean allowCopyOverrides) {
        if (embedVideo || hasSecureDocuments || encryptedChat || avatarNoForwards) {
            return true;
        }
        if (ttl != 0 && ttl < TTL_PROTECTION_THRESHOLD) {
            return true;
        }
        // As in the existing copy policy, the explicit overrides relax peer/message noforwards only.
        return messageNoForwards && !allowCopyOverrides;
    }
}
