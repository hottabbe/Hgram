package xyz.nextalone.nagram.hgram;

import java.util.List;

/** Compatibility gate for applying a read-receipt snapshot to an already bound cell. */
public final class HgramShortChainPayload {

    private HgramShortChainPayload() {
    }

    public static boolean canApply(boolean bound, int boundKey, int boundAnchor,
                                   List<Integer> boundIds, int boundMessageId,
                                   long cellDialogId, long chatDialogId, boolean collapsed,
                                   HgramShortMessageMerger.Chain snapshot) {
        if (!bound || snapshot == null || boundIds == null) return false;
        if (cellDialogId != chatDialogId) return false;
        if (boundKey != snapshot.key || boundAnchor != snapshot.anchorId) return false;
        if (!boundIds.equals(snapshot.ids)) return false;
        if (!snapshot.ids.contains(boundMessageId)) return false;
        // A collapsed presentation only exists on the anchor row; split members keep their
        // own view type until a regular bind/attach decides otherwise.
        return !collapsed || boundMessageId == snapshot.anchorId;
    }
}
