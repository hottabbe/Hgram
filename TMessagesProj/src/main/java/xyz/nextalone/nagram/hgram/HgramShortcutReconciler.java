package xyz.nextalone.nagram.hgram;

import android.content.Intent;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure reconcile pass for owned shortcuts. It compares actual shortcut state from a snapshot
 * with the desired state and mutates only real differences, batching all update/enable/disable
 * calls made by a single pass.
 */
public final class HgramShortcutReconciler {

    public interface HiddenLookup {
        boolean isHidden(int account, long dialogId);
    }

    public interface EntityResolver {
        Entity resolve(int account, long dialogId, int type);
    }

    public interface Backend {
        List<Snapshot> listPinned();

        List<Snapshot> listDynamic();

        boolean updateShortcuts(List<Snapshot> shortcuts);

        boolean enableShortcuts(List<String> ids);

        void disableShortcuts(List<String> ids, CharSequence message);

        void removeDynamicShortcuts(List<String> ids);
    }

    public static final class Snapshot {
        public final String id;
        public final CharSequence shortLabel;
        public final CharSequence longLabel;
        public final int iconRes;
        public final boolean enabled;
        public final Intent intent;

        public Snapshot(String id, CharSequence shortLabel, CharSequence longLabel, int iconRes, boolean enabled, Intent intent) {
            this.id = id;
            this.shortLabel = shortLabel;
            this.longLabel = longLabel;
            this.iconRes = iconRes;
            this.enabled = enabled;
            this.intent = intent;
        }
    }

    public static final class Entity {
        public final String name;
        public final int iconRes;

        public Entity(String name, int iconRes) {
            this.name = name;
            this.iconRes = iconRes;
        }
    }

    public static final class Result {
        public final boolean trackingChanged;
        public final boolean dynamicNeedsRebuild;

        Result(boolean trackingChanged, boolean dynamicNeedsRebuild) {
            this.trackingChanged = trackingChanged;
            this.dynamicNeedsRebuild = dynamicNeedsRebuild;
        }
    }

    private HgramShortcutReconciler() {
    }

    public static Result reconcile(Backend backend, EntityResolver resolver, HiddenLookup hiddenLookup,
                                   Set<String> neutralized, CharSequence hiddenLabel, int hiddenIconRes) {
        final Set<String> before = new HashSet<>(neutralized);
        final HashMap<String, Snapshot> pinnedById = new HashMap<>();
        List<Snapshot> pinned;
        try {
            pinned = backend.listPinned();
        } catch (Throwable t) {
            FileLog.e("HgramShortcutReconciler: pinned shortcuts unavailable, pass skipped");
            return new Result(false, false);
        }
        if (pinned == null) {
            // A failed read must never look like "no shortcuts present": retain the tracking set.
            FileLog.e("HgramShortcutReconciler: pinned shortcuts unavailable, pass skipped");
            return new Result(false, false);
        }
        for (int i = 0; i < pinned.size(); i++) {
            final Snapshot shortcut = pinned.get(i);
            if (shortcut != null && shortcut.id != null) {
                pinnedById.put(shortcut.id, shortcut);
            }
        }

        final HashSet<String> present = new HashSet<>();
        final ArrayList<Snapshot> updates = new ArrayList<>();
        final ArrayList<String> disable = new ArrayList<>();
        final ArrayList<String> enable = new ArrayList<>();
        final HashSet<String> pendingRestore = new HashSet<>();
        boolean trackingChanged = false;

        for (Snapshot shortcut : pinnedById.values()) {
            final String id = shortcut.id;
            final int type = HgramShortcutState.typeForId(id);
            final long dialogId = HgramShortcutState.dialogIdForId(id);
            if (type == HgramShortcutState.TYPE_UNKNOWN || dialogId == 0) {
                if (neutralized.remove(id)) {
                    trackingChanged = true;
                }
                continue;
            }
            present.add(id);
            final int owner = shortcut.intent == null ? -1 : shortcut.intent.getIntExtra("currentAccount", -1);
            final boolean hidden = hiddenLookup.isHidden(owner, dialogId);
            final int decision = HgramShortcutState.reconcileDecision(owner, dialogId, hidden, neutralized.contains(id));

            if (decision == HgramShortcutState.SKIP || decision == HgramShortcutState.NEUTRAL) {
                if (neutralized.add(id)) {
                    trackingChanged = true;
                }
                // The icon resource is often unavailable from a snapshot, so neutral state is
                // judged by labels + enabled flag + tracking, never by comparing the icon.
                if (!hasNeutralLabels(shortcut, hiddenLabel) || shortcut.enabled) {
                    updates.add(withLabels(shortcut, hiddenLabel, hiddenLabel, hiddenIconRes));
                }
                if (shortcut.enabled) {
                    disable.add(id);
                }
            } else if (decision == HgramShortcutState.RESTORE) {
                final Entity entity = resolver.resolve(owner, dialogId, type);
                if (entity == null || TextUtils.isEmpty(entity.name)) {
                    // Missing entity: keep it neutral and retry on a later pass.
                    if (neutralized.add(id)) {
                        trackingChanged = true;
                    }
                    if (!hasNeutralLabels(shortcut, hiddenLabel) || shortcut.enabled) {
                        updates.add(withLabels(shortcut, hiddenLabel, hiddenLabel, hiddenIconRes));
                    }
                    if (shortcut.enabled) {
                        disable.add(id);
                    }
                } else {
                    final boolean tracked = neutralized.contains(id);
                    final boolean labelsMatch = TextUtils.equals(shortcut.shortLabel, entity.name)
                            && TextUtils.equals(shortcut.longLabel, entity.name);
                    if (tracked || !labelsMatch) {
                        updates.add(withLabels(shortcut, entity.name, entity.name, entity.iconRes));
                        pendingRestore.add(id);
                    }
                    if (!shortcut.enabled) {
                        enable.add(id);
                        pendingRestore.add(id);
                    } else if (!tracked && labelsMatch) {
                        // Visible but never neutralized: leave it completely untouched.
                        pendingRestore.remove(id);
                    }
                }
            }
        }

        boolean updatedOk = true;
        if (!updates.isEmpty()) {
            try {
                updatedOk = backend.updateShortcuts(updates);
            } catch (Throwable ignored) {
                updatedOk = false;
            }
        }
        // A failed label update must not leave a real name enabled.
        if (!disable.isEmpty()) {
            try {
                backend.disableShortcuts(disable, hiddenLabel);
            } catch (Throwable ignored) {
            }
        }
        boolean enabledOk = true;
        if (!enable.isEmpty() && updatedOk) {
            try {
                enabledOk = backend.enableShortcuts(enable);
            } catch (Throwable ignored) {
                enabledOk = false;
            }
        } else if (!enable.isEmpty()) {
            enabledOk = false;
        }
        if (updatedOk && enabledOk && !pendingRestore.isEmpty()) {
            for (String id : pendingRestore) {
                if (neutralized.remove(id)) {
                    trackingChanged = true;
                }
            }
        }

        final ArrayList<String> removeDynamic = new ArrayList<>();
        List<Snapshot> dynamic = null;
        try {
            dynamic = backend.listDynamic();
        } catch (Throwable ignored) {
        }
        if (dynamic != null) {
            for (int i = 0; i < dynamic.size(); i++) {
                final Snapshot shortcut = dynamic.get(i);
                if (shortcut == null || shortcut.id == null) {
                    continue;
                }
                final int type = HgramShortcutState.typeForId(shortcut.id);
                final long dialogId = HgramShortcutState.dialogIdForId(shortcut.id);
                if (type == HgramShortcutState.TYPE_UNKNOWN || dialogId == 0) {
                    continue;
                }
                final int owner = shortcut.intent == null ? -1 : shortcut.intent.getIntExtra("currentAccount", -1);
                if (HgramShortcutState.decide(owner, dialogId, hiddenLookup.isHidden(owner, dialogId)) != HgramShortcutState.RESTORE) {
                    removeDynamic.add(shortcut.id);
                }
            }
        }
        if (!removeDynamic.isEmpty()) {
            try {
                backend.removeDynamicShortcuts(removeDynamic);
            } catch (Throwable ignored) {
            }
        }

        if (neutralized.retainAll(present)) {
            trackingChanged = true;
        }
        final boolean dynamicNeedsRebuild = !removeDynamic.isEmpty() || !before.equals(neutralized);
        return new Result(trackingChanged, dynamicNeedsRebuild);
    }

    private static boolean hasNeutralLabels(Snapshot shortcut, CharSequence hiddenLabel) {
        return TextUtils.equals(shortcut.shortLabel, hiddenLabel)
                && TextUtils.equals(shortcut.longLabel, hiddenLabel);
    }

    private static Snapshot withLabels(Snapshot shortcut, CharSequence shortLabel, CharSequence longLabel, int iconRes) {
        return new Snapshot(shortcut.id, shortLabel, longLabel, iconRes, shortcut.enabled, shortcut.intent);
    }
}
