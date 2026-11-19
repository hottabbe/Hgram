package xyz.nextalone.nagram.hgram;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Session-only expansion state for short-message chains. Never persisted. */
public final class HgramShortChainExpansion {
    private static final int MAX_ENTRIES = 256;
    private static final String DEFAULT_CONVERSATION = "";
    private final HashMap<String, HashSet<Integer>> expanded = new HashMap<>();

    public boolean contains(int key) {
        return contains(DEFAULT_CONVERSATION, key);
    }

    public boolean contains(String conversation, int key) {
        final HashSet<Integer> keys = expanded.get(conversation);
        return keys != null && keys.contains(key);
    }

    /** Returns true when the chain is now expanded, false when it was collapsed. */
    public boolean toggle(int key) {
        return toggle(DEFAULT_CONVERSATION, key);
    }

    public boolean toggle(String conversation, int key) {
        HashSet<Integer> keys = expanded.get(conversation);
        if (keys == null) {
            keys = new HashSet<>();
            expanded.put(conversation, keys);
        }
        final boolean result;
        if (keys.add(key)) {
            result = true;
            if (totalSize() > MAX_ENTRIES) {
                expanded.clear();
            }
        } else {
            keys.remove(key);
            if (keys.isEmpty()) {
                expanded.remove(conversation);
            }
            result = false;
        }
        return result;
    }

    public void collapse(String conversation, int key) {
        final HashSet<Integer> keys = expanded.get(conversation);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) expanded.remove(conversation);
        }
    }

    public void retainAll(Collection<Integer> liveKeys) {
        retainAll(DEFAULT_CONVERSATION, liveKeys);
    }

    /** Skips cleanup while the real chain set is not known (empty pre-fill on reopen). */
    public void retainAllIfKnown(String conversation, Collection<Integer> liveKeys, boolean known) {
        if (known) retainAll(conversation, liveKeys);
    }

    public void retainAll(String conversation, Collection<Integer> liveKeys) {
        final HashSet<Integer> keys = expanded.get(conversation);
        if (keys == null) return;
        keys.retainAll(liveKeys);
        if (keys.isEmpty()) {
            expanded.remove(conversation);
        }
        if (totalSize() > MAX_ENTRIES) {
            expanded.clear();
        }
    }

    public void clear(String conversation) {
        expanded.remove(conversation);
    }

    public void clear() {
        expanded.clear();
    }

    public int size() {
        return totalSize();
    }

    public int size(String conversation) {
        final HashSet<Integer> keys = expanded.get(conversation);
        return keys == null ? 0 : keys.size();
    }

    int capacity() {
        return MAX_ENTRIES;
    }

    private int totalSize() {
        int size = 0;
        for (HashSet<Integer> keys : expanded.values()) size += keys.size();
        return size;
    }
}
