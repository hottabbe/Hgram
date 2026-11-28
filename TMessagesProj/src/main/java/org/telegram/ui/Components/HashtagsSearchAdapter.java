package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.MessagesSearchAdapter;
import org.telegram.ui.Stories.StoriesController;
import xyz.nextalone.nagram.hgram.HgramSecretSpace;

import java.util.ArrayList;

public class HashtagsSearchAdapter extends UniversalAdapter {

    private final int currentAccount;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    public boolean hasList;
    public StoriesController.SearchStoriesList list;

    public HashtagsSearchAdapter(RecyclerListView listView, Context context, int currentAccount, int folderId, Theme.ResourcesProvider resourcesProvider) {
        super(listView, context, currentAccount, 0, null, resourcesProvider);
        super.fillItems = this::fillItems;
        this.currentAccount = currentAccount;
    }

    private boolean hadStories;
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean hasStories = hasList && list != null && list.getLoadedCount() > 0;
        if (hasStories) {
            items.add(MessagesSearchAdapter.StoriesView.Factory.asStoriesList(list));
        }
        hadStories = hasStories;
        int visibleIndex = 0;
        for (int i = 0; i < messages.size(); ++i) {
            final MessageObject message = messages.get(i);
            if (HgramSecretSpace.isVisible(currentAccount, message.getDialogId())) {
                items.add(UItem.asSearchMessage(1 + visibleIndex++, message));
            }
        }
        if (loading || !cursor.endReached()) {
            items.add(UItem.asFlicker(-2, FlickerLoadingView.DIALOG_TYPE));
            items.add(UItem.asFlicker(-3, FlickerLoadingView.DIALOG_TYPE));
            items.add(UItem.asFlicker(-4, FlickerLoadingView.DIALOG_TYPE));
        }
        if (!hadStories && hasStories) {
            AndroidUtilities.runOnUIThread(() -> {
                scrollToTop(true);
            });
        }
    }

    protected boolean loading;
    private int searchId = 0;
    private int reqId = -1;
    private final xyz.nextalone.nagram.hgram.HgramHashtagCursor cursor = new xyz.nextalone.nagram.hgram.HgramHashtagCursor();
    private String lastQuery;
    private String hashtagQuery;
    private Runnable searchRunnable;

    public void setInitialData(String hashtag, ArrayList<MessageObject> messages, int messagesLastRate, int totalCount, TLRPC.InputPeer rawTail, int rawCount) {
        if (TextUtils.equals(hashtag, hashtagQuery)) return;
        cancel();
        this.messages.clear();
        for (MessageObject message : messages) {
            if (HgramSecretSpace.isVisible(currentAccount, message.getDialogId())) {
                this.messages.add(message);
            }
        }
        cursor.initialize(rawTail != null ? rawTail : messages.isEmpty() ? null
                        : MessagesController.getInstance(currentAccount).getInputPeer(messages.get(messages.size() - 1).messageOwner.peer_id),
                messagesLastRate, rawCount > 0 ? rawCount : messages.size(), totalCount);
        hashtagQuery = hashtag;
        update(true);
    }

    private final boolean[] cashtag = new boolean[1];

    public void search(String query) {
        lastQuery = query;
        final String hashtag = getHashtag(query, cashtag);
        if (!TextUtils.equals(this.hashtagQuery, hashtag)) {
            messages.clear();
            cursor.reset();
            cancel();
        } else if (loading) {
            return;
        }
        final int id = ++searchId;
        if (hashtag == null) return;
        loading = true;
        update(true);
        AndroidUtilities.runOnUIThread(searchRunnable = () -> {
            if (id != searchId) {
                return;
            }
            final String finalQuery = (cashtag[0] ? "$" : "#") + hashtagQuery;
            if (list == null || !TextUtils.equals(list.query, finalQuery)) {
                list = new StoriesController.SearchStoriesList(currentAccount, null, finalQuery);
            }
            if (list.getLoadedCount() <= 0) {
                list.load(true, 4);
            }
            hasList = true;
            final TLRPC.TL_channels_searchPosts req = new TLRPC.TL_channels_searchPosts();
            req.flags |= 1;
            req.hashtag = this.hashtagQuery = hashtag;
            req.limit = 10;
            if (cursor.offsetPeer() != null) {
                req.offset_rate = cursor.offsetRate();
                req.offset_peer = cursor.offsetPeer();
            } else {
                req.offset_peer = new TLRPC.TL_inputPeerEmpty();
            }
            reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (id != searchId) {
                    return;
                }
                final boolean wasEmpty = messages.isEmpty();
                loading = false;
                if (res instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) res;
                    int totalCount = 0;
                    if (msgs instanceof TLRPC.TL_messages_messages) {
                        totalCount = ((TLRPC.TL_messages_messages) msgs).messages.size();
                    } else if (msgs instanceof TLRPC.TL_messages_messagesSlice) {
                        totalCount = ((TLRPC.TL_messages_messagesSlice) msgs).count;
                    }
                    MessagesController.getInstance(currentAccount).putUsers(msgs.users, false);
                    MessagesController.getInstance(currentAccount).putChats(msgs.chats, false);
                    final int received = msgs.messages.size();
                    final TLRPC.InputPeer tailPeer = received > 0
                            ? MessagesController.getInstance(currentAccount).getInputPeer(msgs.messages.get(received - 1).peer_id)
                            : null;
                    for (int i = 0; i < received; ++i) {
                        final TLRPC.Message msg = msgs.messages.get(i);
                        final MessageObject messageObject = new MessageObject(currentAccount, msg, false, true);
                        if (!HgramSecretSpace.isVisible(currentAccount, messageObject.getDialogId())) {
                            continue;
                        }
                        messageObject.setQuery(finalQuery);
                        messages.add(messageObject);
                    }
                    // Pagination follows the raw server page/cursor, not the filtered list size.
                    cursor.received(tailPeer, msgs.next_rate, received, totalCount, 10);
                    checkBottom();
                } else {
                    cursor.failed();
                }
                update(true);
                if (wasEmpty) {
                    scrollToTop(false);
                }
            }));
        }, 300);
    }

    public String getHashtag(String query) {
        return getHashtag(query, null);
    }

    public String getHashtag(String query, final boolean[] cashtag) {
        if (cashtag != null) cashtag[0] = false;
        if (query == null || query.isEmpty()) return null;
        String tquery = query.trim();
        if (tquery.length() <= 1) return null;
        if (tquery.charAt(0) != '#' && tquery.charAt(0) != '$') return null;
        if (tquery.indexOf('@') >= 0) return null;
        if (cashtag != null) cashtag[0] = tquery.charAt(0) == '$';
        return tquery.substring(1);
    }

    /** Drops already displayed posts that became hidden after a workspace change. */
    public void filterHidden() {
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!HgramSecretSpace.isVisible(currentAccount, messages.get(i).getDialogId())) {
                messages.remove(i);
                changed = true;
            }
        }
        if (list != null) {
            final int storiesBefore = list.getLoadedCount();
            list.filterHidden();
            if (list.getLoadedCount() != storiesBefore) {
                changed = true;
            }
        }
        if (changed) {
            update(true);
        }
    }

    public void cancel() {
        if (list != null) {
            list.cancel();
        }
        hasList = false;
        if (reqId >= 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(reqId, true);
            reqId = -1;
        }
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        searchId++;
        loading = false;
    }


    public void checkBottom() {
        if (!TextUtils.isEmpty(lastQuery)) {
            if (!cursor.endReached() && !loading && seesLoading()) {
                search(lastQuery);
            }
        }
    }

    public boolean seesLoading() {
        if (listView == null) return false;
        for (int i = 0; i < listView.getChildCount(); ++i) {
            View child = listView.getChildAt(i);
            if (child instanceof FlickerLoadingView) {
                return true;
            }
        }
        return false;
    }

    protected void scrollToTop(boolean ifAtTop) {

    }

}
