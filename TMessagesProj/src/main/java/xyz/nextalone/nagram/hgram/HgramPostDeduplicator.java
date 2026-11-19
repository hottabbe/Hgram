package xyz.nextalone.nagram.hgram;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A bounded, in-memory semantic index of channel posts that have actually been drawn. */
public final class HgramPostDeduplicator {

    public static final long RETENTION_MS = 2 * 60 * 60 * 1000L;
    private static final int MAX_POSTS = 1200;
    private static final double SIMILARITY_THRESHOLD = 0.32;
    private static final ConcurrentHashMap<Integer, HgramPostDeduplicator> INSTANCES = new ConcurrentHashMap<>();

    private final LinkedHashMap<String, SeenPost> posts = new LinkedHashMap<>();

    public static HgramPostDeduplicator getInstance(int account) {
        return INSTANCES.computeIfAbsent(account, ignored -> new HgramPostDeduplicator());
    }

    /** Returns the first semantically matching post from a different channel within the retention window. */
    public synchronized Match findSimilar(String text, long dialogId, long nowElapsedRealtime) {
        return prepare(text, dialogId, nowElapsedRealtime).match;
    }

    /** Computes the fingerprint once for the draw and the subsequent seen-post insertion. */
    public synchronized PreparedPost prepare(String text, long dialogId, long nowElapsedRealtime) {
        HgramTextSemantics.Fingerprint fingerprint = HgramTextSemantics.fingerprint(text);
        if (fingerprint == null) {
            return new PreparedPost(null, dialogId, null);
        }
        prune(nowElapsedRealtime);
        Match best = null;
        double bestScore = 0;
        for (SeenPost post : posts.values()) {
            if (post.dialogId == dialogId) {
                continue;
            }
            double score = HgramTextSemantics.similarity(fingerprint, post.fingerprint);
            int sharedEntities = intersectionSize(fingerprint.entities, post.fingerprint.entities);
            int sharedTopics = intersectionSize(fingerprint.topics, post.fingerprint.topics);
            double stemContainment = HgramTextSemantics.stemContainment(fingerprint, post.fingerprint);
            // Different wording is expected. Require shared facts/topics so broad news vocabulary
            // alone cannot make unrelated posts collapse together.
            if (sharedEntities == 0 && sharedTopics == 0 && stemContainment < 0.78
                    || sharedEntities == 0 && sharedTopics > 0 && stemContainment < 0.30) {
                continue;
            }
            if (score < SIMILARITY_THRESHOLD || sharedEntities == 0 && sharedTopics == 0 && score < 0.72) {
                continue;
            }
            if (score >= SIMILARITY_THRESHOLD && score > bestScore) {
                bestScore = score;
                best = new Match(post.sourceLabel, post.dialogId, post.messageId);
            }
        }
        return new PreparedPost(fingerprint, dialogId, best);
    }

    private static int intersectionSize(java.util.Set<String> first, java.util.Set<String> second) {
        java.util.Set<String> smaller = first.size() <= second.size() ? first : second;
        java.util.Set<String> larger = smaller == first ? second : first;
        int count = 0;
        for (String value : smaller) {
            if (larger.contains(value)) count++;
        }
        return count;
    }

    /** Adds a post only after its normal message cell has been drawn. */
    public synchronized void rememberSeen(String text, long dialogId, int messageId, String sourceLabel, long nowElapsedRealtime) {
        rememberSeen(new PreparedPost(HgramTextSemantics.fingerprint(text), dialogId, null), messageId, sourceLabel, nowElapsedRealtime);
    }

    public synchronized void rememberSeen(PreparedPost prepared, int messageId, String sourceLabel, long nowElapsedRealtime) {
        HgramTextSemantics.Fingerprint fingerprint = prepared.fingerprint;
        long dialogId = prepared.dialogId;
        if (fingerprint == null) {
            return;
        }
        prune(nowElapsedRealtime);
        String key = dialogId + ":" + messageId;
        SeenPost existing = posts.get(key);
        if (existing != null) {
            posts.put(key, new SeenPost(fingerprint, dialogId, messageId, sourceLabel, existing.seenAt));
            return;
        }
        while (posts.size() >= MAX_POSTS) {
            Iterator<String> oldest = posts.keySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
        }
        posts.put(key, new SeenPost(fingerprint, dialogId, messageId, sourceLabel, nowElapsedRealtime));
    }

    /** Forget an edited message so its old contents cannot match later posts. */
    public synchronized void removeSeen(long dialogId, int messageId) {
        posts.remove(dialogId + ":" + messageId);
    }

    public synchronized void clear() {
        posts.clear();
    }

    private void prune(long nowElapsedRealtime) {
        Iterator<Map.Entry<String, SeenPost>> iterator = posts.entrySet().iterator();
        while (iterator.hasNext()) {
            SeenPost post = iterator.next().getValue();
            if (nowElapsedRealtime < post.seenAt || nowElapsedRealtime - post.seenAt >= RETENTION_MS) {
                iterator.remove();
            }
        }
    }

    public static final class PreparedPost {
        private final HgramTextSemantics.Fingerprint fingerprint;
        private final long dialogId;
        public final Match match;

        private PreparedPost(HgramTextSemantics.Fingerprint fingerprint, long dialogId, Match match) {
            this.fingerprint = fingerprint;
            this.dialogId = dialogId;
            this.match = match;
        }
    }

    public static final class Match {
        public final String sourceLabel;
        public final long dialogId;
        public final int messageId;

        private Match(String sourceLabel, long dialogId, int messageId) {
            this.sourceLabel = sourceLabel;
            this.dialogId = dialogId;
            this.messageId = messageId;
        }
    }

    private static final class SeenPost {
        final HgramTextSemantics.Fingerprint fingerprint;
        final long dialogId;
        final int messageId;
        final String sourceLabel;
        final long seenAt;

        SeenPost(HgramTextSemantics.Fingerprint fingerprint, long dialogId, int messageId, String sourceLabel, long seenAt) {
            this.fingerprint = fingerprint;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.sourceLabel = sourceLabel;
            this.seenAt = seenAt;
        }
    }
}
