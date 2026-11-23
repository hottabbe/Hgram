package org.telegram.messenger;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Stage 1 of push -> chat sync.
 *
 * Turns an FCM push into an ordinary (non-FCM) placeholder message, stores it through
 * {@link MessagesStorage#putPushPlaceholder(TLRPC.Message, Utilities.Callback)} and, only when the
 * row was really inserted, shows it in the dialogs list and the opened chat.
 *
 * The real message always wins: the placeholder is insert-if-absent and the storage commit marks
 * {@link PushPlaceholderRegistry} (both the durable pending row and the one-shot UI replacement
 * right) before this class publishes anything. The preview is then replaced through
 * {@link NotificationCenter#replaceMessagesObjects} when the update/difference arrives.
 */
public final class PushChatSync {

    private PushChatSync() {
    }

    /**
     * Called from {@link PushListenerController} on {@link Utilities#stageQueue} right after the
     * notification {@link MessageObject} was built and before notifications are processed.
     * Never blocks the push {@code countDownLatch}.
     */
    public static void onPushMessage(final int account, final MessageObject push) {
        if (push == null || push.messageOwner == null) {
            return;
        }
        final TLRPC.Message source = push.messageOwner;
        final int mid = source.id;
        final long sourceDialogId = source.dialog_id != 0 ? source.dialog_id : MessageObject.getDialogId(source);
        if (account < 0 || mid <= 0 || sourceDialogId == 0) {
            return;
        }
        // The parser can overwrite dialog_id with custom "from_id" even for channel posts; the peer
        // is authoritative for the placeholder row, while the parsed dialog id still detects
        // secret chats (their dialog_id is not a peer id).
        long dialogId = DialogObject.getPeerDialogId(source.peer_id);
        if (dialogId == 0) {
            dialogId = sourceDialogId;
        }
        final long resolvedDialogId = dialogId;
        final MessagesController messagesController = MessagesController.getInstance(account);
        long channelId = source.peer_id != null ? source.peer_id.channel_id : 0;
        TLRPC.Chat chat = channelId != 0 ? messagesController.getChat(channelId) : null;
        Integer readMaxValue = messagesController.dialogs_read_inbox_max.get(resolvedDialogId);
        // The in-memory read max is only a fast hint: the authoritative barrier is the
        // dialogs.inbox_max query performed by MessagesStorage#putPushPlaceholder on the storage
        // queue. A stale/zero cache value must never be the only thing that lets a read message in.
        PushChatSyncPolicy.Input input = buildPolicyInput(push, resolvedDialogId, sourceDialogId, channelId,
                chat, readMaxValue == null ? 0 : readMaxValue);
        if (!PushChatSyncPolicy.isEligible(input)) {
            return;
        }
        final TLRPC.TL_message placeholder = buildPlaceholder(source, resolvedDialogId);
        if (placeholder == null) {
            return;
        }
        MessagesStorage.getInstance(account).putPushPlaceholder(placeholder, inserted -> {
            if (!inserted) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> publishPlaceholder(account, resolvedDialogId, mid, placeholder));
        });
    }

    private static PushChatSyncPolicy.Input buildPolicyInput(MessageObject push, long dialogId, long sourceDialogId,
                                                             long channelId, TLRPC.Chat chat, int readMax) {
        TLRPC.Message source = push.messageOwner;
        PushChatSyncPolicy.Input input = new PushChatSyncPolicy.Input();
        input.dialogId = dialogId;
        input.msgId = source.id;
        input.randomId = source.random_id;
        input.readMax = readMax;
        input.channelId = channelId;
        input.encryptedDialog = DialogObject.isEncryptedDialog(sourceDialogId);
        input.globalSecretChat = sourceDialogId == NotificationsController.globalSecretChatId;
        input.reactionPush = push.isReactionPush;
        input.storyReactionPush = push.isStoryReactionPush;
        input.storyPush = push.isStoryPush;
        input.liveStoryPush = push.isLiveStoryPush;
        input.storyMentionPush = push.isStoryMentionPush;
        input.oauthPush = push.isOauthPush;
        input.oauthUser = dialogId == UserObject.OAUTH;
        input.scheduled = source.from_scheduled;
        input.edited = push.localEdit;
        input.pinnedService = source.action instanceof TLRPC.TL_messageActionPinMessage;
        if (channelId != 0) {
            if (chat == null) {
                input.chatState = PushChatSyncPolicy.ChatState.UNKNOWN;
            } else if (chat.min) {
                input.chatState = PushChatSyncPolicy.ChatState.MIN;
            } else if (ChatObject.isNotInChat(chat)) {
                input.chatState = PushChatSyncPolicy.ChatState.NOT_MEMBER;
            } else {
                input.chatState = PushChatSyncPolicy.ChatState.MEMBER;
            }
        }
        return input;
    }

    /** Package-private for the TL round-trip test: this is the only production placeholder builder. */
    static TLRPC.TL_message buildPlaceholder(TLRPC.Message source, long dialogId) {
        if (source.peer_id == null) {
            return null;
        }
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = source.id;
        // The preview is a different object than the pushed message. Keeping the incoming
        // random_id would let the stale randoms_v2 mapping rename the real message later.
        message.random_id = 0;
        message.dialog_id = dialogId;
        message.message = source.message;
        message.date = source.date;
        message.peer_id = copyPeer(source.peer_id);
        message.from_id = copyPeer(source.from_id);
        if (message.from_id == null) {
            message.from_id = copyPeer(source.peer_id);
        }
        // Copy only pushed presentation bits; 0x80000000 is the FCM "supergroup" marker and must
        // not leak into a regular message row.
        message.flags = source.flags & 0x7FFFFFFF;
        message.flags &= ~1;       // out
        message.flags &= ~(1 << 17); // from_scheduled
        // The preview never carries fwd_from, so the forwarded bit must not survive from the push.
        message.flags &= ~TLRPC.MESSAGE_FLAG_FWD;
        // Only a forum topic reply is copied below; any other incoming reply bit would serialize a
        // null reply_to, so it starts cleared and is set explicitly for the topic case.
        message.flags &= ~TLRPC.MESSAGE_FLAG_REPLY;
        if (message.from_id != null) {
            message.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        }
        message.post = source.post;
        message.silent = source.silent;
        message.unread = true;
        message.mentioned = source.mentioned;
        message.media_unread = source.mentioned;
        if (source.reply_to != null && source.reply_to.forum_topic) {
            TLRPC.TL_messageReplyHeader replyHeader = new TLRPC.TL_messageReplyHeader();
            replyHeader.forum_topic = true;
            // The header flags are serialized as-is (serializeToStream only re-derives FLAG_2/3/9/12/13),
            // so both id bits must be set explicitly or the ids are dropped on the wire and in SQLite.
            if (source.reply_to.reply_to_top_id != 0) {
                replyHeader.reply_to_top_id = source.reply_to.reply_to_top_id;
                replyHeader.flags |= TLObject.FLAG_1;
            }
            if (source.reply_to.reply_to_msg_id != 0) {
                replyHeader.reply_to_msg_id = source.reply_to.reply_to_msg_id;
                replyHeader.flags |= TLObject.FLAG_4;
            }
            message.reply_to = replyHeader;
            // TLRPC.MESSAGE_FLAG_REPLY == 1 << 3 is the outer "has reply_to" bit. Bit 2 (value 4)
            // is the forwarded-message bit and must never be used for a topic reply.
            message.flags |= TLRPC.MESSAGE_FLAG_REPLY;
        }
        return message;
    }

    private static TLRPC.Peer copyPeer(TLRPC.Peer peer) {
        if (peer == null) {
            return null;
        }
        TLRPC.Peer copy;
        if (peer instanceof TLRPC.TL_peerUser) {
            copy = new TLRPC.TL_peerUser();
        } else if (peer instanceof TLRPC.TL_peerChat) {
            copy = new TLRPC.TL_peerChat();
        } else if (peer instanceof TLRPC.TL_peerChannel) {
            copy = new TLRPC.TL_peerChannel();
        } else {
            return peer;
        }
        copy.user_id = peer.user_id;
        copy.chat_id = peer.chat_id;
        copy.channel_id = peer.channel_id;
        return copy;
    }

    private static void publishPlaceholder(int account, long dialogId, int mid, TLRPC.TL_message placeholder) {
        MessagesController messagesController = MessagesController.getInstance(account);
        MessageObject object = new MessageObject(account, placeholder, false, false);
        // The storage commit marks the registry exactly once; this delayed callback must neither
        // re-mark nor publish when a real row already won the race. If storage cleared the durable
        // pending marker (real message written) the preview is not shown and its marker is not
        // resurrected.
        if (!shouldPublishPlaceholder(MessagesStorage.getInstance(account).getPushPlaceholderRegistry(),
                dialogId, mid, hasRealMessage(messagesController, dialogId, mid))) {
            return;
        }
        ArrayList<MessageObject> messages = new ArrayList<>(1);
        messages.add(object);
        messagesController.updateInterfaceWithMessages(dialogId, messages, 0, true);
        // updateInterfaceWithMessages re-sorts in-memory lists; the dialogs list needs the event to
        // repaint the new preview and move the dialog up.
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        messagesController.maybeRequestDifferenceForPush(dialogId);
    }

    /**
     * PUSH-CHAT-3: gate for the delayed push UI callback. The durable pending marker is set by the
     * storage commit and cleared only by the authoritative real write, so its absence means the
     * preview must not be published and must not be re-marked. A real object already visible in
     * the UI (update/difference beat the callback) has the same effect.
     * Package-private for the restart tests.
     */
    static boolean shouldPublishPlaceholder(PushPlaceholderRegistry registry, long dialogId, int mid,
                                            boolean realMessageInUi) {
        return registry != null && !realMessageInUi && registry.hasPending(dialogId, mid);
    }

    private static boolean hasRealMessage(MessagesController messagesController, long dialogId, int mid) {
        MessageObject cached = messagesController.dialogMessagesByIds.get(mid);
        if (cached != null && cached.getDialogId() == dialogId && !cached.isFcmMessage()) {
            return true;
        }
        ArrayList<MessageObject> dialogMessages = messagesController.dialogMessage.get(dialogId);
        if (dialogMessages != null) {
            for (int a = 0, size = dialogMessages.size(); a < size; a++) {
                MessageObject object = dialogMessages.get(a);
                if (object != null && object.getId() == mid && !object.isFcmMessage()) {
                    return true;
                }
            }
        }
        return false;
    }
}
