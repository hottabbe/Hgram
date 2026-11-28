package xyz.nextalone.nagram.hgram;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.Stories.StoriesController;

import java.util.ArrayList;

/** Single visibility gate for story grids, headers and search adapters. */
public final class HgramStoriesAccess {

    private HgramStoriesAccess() {
    }

    public static int visibleCount(StoriesController.StoriesList list) {
        if (list instanceof StoriesController.SearchStoriesList) {
            return ((StoriesController.SearchStoriesList) list).getVisibleCount();
        }
        return list == null ? 0 : list.messageObjects.size();
    }

    public static MessageObject visibleMessageObject(StoriesController.StoriesList list, int position) {
        if (list == null || position < 0) {
            return null;
        }
        final MessageObject message;
        if (list instanceof StoriesController.SearchStoriesList) {
            message = ((StoriesController.SearchStoriesList) list).getVisibleMessageObject(position);
        } else if (position < list.messageObjects.size()) {
            message = list.messageObjects.get(position);
        } else {
            return null;
        }
        if (message == null || !HgramSecretSpace.canOpenDialog(list.currentAccount, message.getDialogId())) {
            return null;
        }
        return message;
    }

    public static ArrayList<MessageObject> visibleMessages(StoriesController.StoriesList list, int limit) {
        final ArrayList<MessageObject> result = new ArrayList<>();
        final int count = Math.min(visibleCount(list), limit);
        for (int i = 0; i < count; i++) {
            final MessageObject message = visibleMessageObject(list, i);
            if (message != null) {
                result.add(message);
            }
        }
        return result;
    }
}
