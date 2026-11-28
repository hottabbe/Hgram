package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLRPC;

/**
 * The tap-to-lift message menu is only meaningful for private chats and groups.
 * Broadcast channels keep the upstream popup behavior and never move a bubble.
 */
public final class HgramChatLiftPolicy {
    private HgramChatLiftPolicy() {
    }

    public static boolean supports(TLRPC.Chat chat) {
        return !ChatObject.isChannelAndNotMegaGroup(chat);
    }
}
