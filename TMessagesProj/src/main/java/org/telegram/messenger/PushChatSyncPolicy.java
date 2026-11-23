package org.telegram.messenger;

/**
 * Pure eligibility policy for turning a push preview into a real message placeholder.
 *
 * This class intentionally has no Android UI / database dependencies: the push parser fills
 * {@link Input} and asks {@link #isEligible(Input)} before anything is written to storage.
 */
public final class PushChatSyncPolicy {

    private PushChatSyncPolicy() {
    }

    public enum ChatState {
        /** The chat is not loaded yet (the channel info is unavailable). */
        UNKNOWN,
        /** The current account is a participant of the channel / supergroup. */
        MEMBER,
        /** The current account left or was kicked from the channel. */
        NOT_MEMBER,
        /** The chat is a "min" (not a full) channel object. */
        MIN
    }

    /**
     * Everything the push parser knows about the incoming notification.
     * Fields are intentionally public: the integration code just fills this value object.
     */
    public static final class Input {
        public long dialogId;
        /** Server message id from the push (custom.msg_id). */
        public int msgId;
        /** Server random id from the push (custom.random_id); secondary, informational. */
        public long randomId;
        /** Last read inbox id for the dialog; pushes at or below it are already read. */
        public int readMax;
        /** Channel id from the push, 0 for private chats and basic groups. */
        public long channelId;

        public boolean encryptedDialog;
        public boolean globalSecretChat;
        public boolean reactionPush;
        public boolean storyReactionPush;
        public boolean storyPush;
        public boolean liveStoryPush;
        public boolean storyMentionPush;
        public boolean oauthPush;
        /** The push belongs to a user with UserObject.OAUTH flag. */
        public boolean oauthUser;
        /** custom "schedule" is present. */
        public boolean scheduled;
        /** custom "edit_date" is present. */
        public boolean edited;
        /** Service notification for PINNED_* / pin actions. */
        public boolean pinnedService;
        /** Participation state when channelId != 0. */
        public ChatState chatState = ChatState.UNKNOWN;
    }

    public static boolean isEligible(Input input) {
        if (input == null || input.dialogId == 0) {
            return false;
        }
        // Secret chats never expose their content to push notifications.
        if (input.encryptedDialog || input.globalSecretChat) {
            return false;
        }
        // Reactions and stories reference an existing message/story; a push must not overwrite it.
        if (input.reactionPush || input.storyReactionPush || input.storyPush
                || input.liveStoryPush || input.storyMentionPush) {
            return false;
        }
        // OAuth service pushes have no normal message id-space.
        if (input.oauthPush || input.oauthUser) {
            return false;
        }
        // Scheduled messages, edits and PINNED_* services are not new normal messages.
        if (input.scheduled || input.edited || input.pinnedService) {
            return false;
        }
        // msg_id == 0 && random_id != 0 means our own outgoing message from another device.
        if (input.msgId <= 0) {
            return false;
        }
        if (input.msgId <= input.readMax) {
            return false;
        }
        // For channels, only write when we know we are a participant and have a full chat object.
        return input.channelId == 0 || input.chatState == ChatState.MEMBER;
    }
}
