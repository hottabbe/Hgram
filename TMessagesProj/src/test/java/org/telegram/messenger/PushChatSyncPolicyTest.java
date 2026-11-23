package org.telegram.messenger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PushChatSyncPolicyTest {

    private static PushChatSyncPolicy.Input normalPush() {
        PushChatSyncPolicy.Input input = new PushChatSyncPolicy.Input();
        input.dialogId = 777L;
        input.msgId = 1001;
        input.randomId = 0;
        input.readMax = 1000;
        input.chatState = PushChatSyncPolicy.ChatState.MEMBER;
        return input;
    }

    private static void assertRejected(PushChatSyncPolicy.Input input, String reason) {
        assertFalse(reason, PushChatSyncPolicy.isEligible(input));
    }

    @Test
    public void textPush_isEligible() {
        assertTrue(PushChatSyncPolicy.isEligible(normalPush()));

        // OAuth and PINNED_* service pushes share the text branch but are not new messages.
        PushChatSyncPolicy.Input oauthPush = normalPush();
        oauthPush.oauthPush = true;
        assertRejected(oauthPush, "oauth service push");

        PushChatSyncPolicy.Input oauthUser = normalPush();
        oauthUser.oauthUser = true;
        assertRejected(oauthUser, "oauth user push");

        PushChatSyncPolicy.Input pinned = normalPush();
        pinned.pinnedService = true;
        assertRejected(pinned, "PINNED_* service push");
    }

    @Test
    public void reactionPush_isRejected() {
        PushChatSyncPolicy.Input input = normalPush();
        input.reactionPush = true;
        assertRejected(input, "reaction push must not overwrite the reacted message");
    }

    @Test
    public void storyPush_isRejected() {
        PushChatSyncPolicy.Input storyReaction = normalPush();
        storyReaction.storyReactionPush = true;
        assertRejected(storyReaction, "story reaction push");

        PushChatSyncPolicy.Input story = normalPush();
        story.storyPush = true;
        assertRejected(story, "story push");

        PushChatSyncPolicy.Input liveStory = normalPush();
        liveStory.liveStoryPush = true;
        assertRejected(liveStory, "live story push");

        PushChatSyncPolicy.Input mention = normalPush();
        mention.storyMentionPush = true;
        assertRejected(mention, "story mention push");
    }

    @Test
    public void secretChat_isRejected() {
        PushChatSyncPolicy.Input encrypted = normalPush();
        encrypted.encryptedDialog = true;
        assertRejected(encrypted, "encrypted dialog");

        PushChatSyncPolicy.Input globalSecret = normalPush();
        globalSecret.globalSecretChat = true;
        assertRejected(globalSecret, "global secret chat id");
    }

    @Test
    public void scheduledPush_isRejected() {
        PushChatSyncPolicy.Input input = normalPush();
        input.scheduled = true;
        assertRejected(input, "custom schedule push");
    }

    @Test
    public void editPush_isRejected() {
        PushChatSyncPolicy.Input input = normalPush();
        input.edited = true;
        assertRejected(input, "custom edit_date push");
    }

    @Test
    public void readMessage_isRejected() {
        PushChatSyncPolicy.Input exactlyRead = normalPush();
        exactlyRead.readMax = exactlyRead.msgId;
        assertRejected(exactlyRead, "msg_id == read max");

        PushChatSyncPolicy.Input oldMessage = normalPush();
        oldMessage.readMax = oldMessage.msgId + 10;
        assertRejected(oldMessage, "msg_id below read max");
    }

    @Test
    public void zeroMid_isRejected() {
        PushChatSyncPolicy.Input ownOutgoing = normalPush();
        ownOutgoing.msgId = 0;
        ownOutgoing.randomId = 123456789L;
        assertRejected(ownOutgoing, "msg_id == 0 with random_id (own message from another device)");

        PushChatSyncPolicy.Input negative = normalPush();
        negative.msgId = -5;
        assertRejected(negative, "negative msg_id");
    }

    @Test
    public void channelNotMember_isRejected() {
        PushChatSyncPolicy.Input unknown = normalPush();
        unknown.channelId = 42;
        unknown.chatState = PushChatSyncPolicy.ChatState.UNKNOWN;
        assertRejected(unknown, "channel chat is not loaded");

        PushChatSyncPolicy.Input notMember = normalPush();
        notMember.channelId = 42;
        notMember.chatState = PushChatSyncPolicy.ChatState.NOT_MEMBER;
        assertRejected(notMember, "channel chat is left/kicked");

        PushChatSyncPolicy.Input min = normalPush();
        min.channelId = 42;
        min.chatState = PushChatSyncPolicy.ChatState.MIN;
        assertRejected(min, "min channel object");

        PushChatSyncPolicy.Input member = normalPush();
        member.channelId = 42;
        member.chatState = PushChatSyncPolicy.ChatState.MEMBER;
        assertTrue("participating channel push", PushChatSyncPolicy.isEligible(member));
    }
}
