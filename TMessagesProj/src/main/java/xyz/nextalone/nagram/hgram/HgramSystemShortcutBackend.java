package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutManager;
import android.text.TextUtils;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * System ShortcutManager implementation of the reconcile backend.
 */
public final class HgramSystemShortcutBackend implements HgramShortcutReconciler.Backend {

    private final Context context;

    public HgramSystemShortcutBackend(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public List<HgramShortcutReconciler.Snapshot> listPinned() {
        return list(ShortcutManagerCompat.FLAG_MATCH_PINNED);
    }

    @Override
    public List<HgramShortcutReconciler.Snapshot> listDynamic() {
        return list(ShortcutManagerCompat.FLAG_MATCH_DYNAMIC);
    }

    private List<HgramShortcutReconciler.Snapshot> list(int flags) {
        final ArrayList<HgramShortcutReconciler.Snapshot> result = new ArrayList<>();
        try {
            final List<ShortcutInfoCompat> shortcuts = ShortcutManagerCompat.getShortcuts(context, flags);
            if (shortcuts == null) {
                return result;
            }
            for (int i = 0; i < shortcuts.size(); i++) {
                final ShortcutInfoCompat info = shortcuts.get(i);
                if (info == null || TextUtils.isEmpty(info.getId())) {
                    continue;
                }
                final IconCompat icon = info.getIcon();
                int iconRes = 0;
                if (icon != null) {
                    try {
                        iconRes = icon.getResId();
                    } catch (Throwable ignored) {
                        iconRes = 0;
                    }
                }
                result.add(new HgramShortcutReconciler.Snapshot(
                        info.getId(),
                        info.getShortLabel(),
                        info.getLongLabel(),
                        iconRes,
                        info.isEnabled(),
                        info.getIntent()));
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        return result;
    }

    @Override
    public boolean updateShortcuts(List<HgramShortcutReconciler.Snapshot> shortcuts) {
        if (shortcuts == null || shortcuts.isEmpty()) {
            return true;
        }
        final ArrayList<ShortcutInfoCompat> updated = new ArrayList<>(shortcuts.size());
        for (int i = 0; i < shortcuts.size(); i++) {
            final HgramShortcutReconciler.Snapshot shortcut = shortcuts.get(i);
            if (shortcut == null || shortcut.id == null || shortcut.intent == null) {
                continue;
            }
            updated.add(new ShortcutInfoCompat.Builder(context, shortcut.id)
                    .setShortLabel(shortcut.shortLabel)
                    .setLongLabel(shortcut.longLabel)
                    .setIcon(IconCompat.createWithResource(context, shortcut.iconRes))
                    .setIntent(shortcut.intent)
                    .build());
        }
        if (updated.isEmpty()) {
            return true;
        }
        try {
            return ShortcutManagerCompat.updateShortcuts(context, updated);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    @Override
    public boolean enableShortcuts(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        try {
            final ShortcutManager manager = context.getSystemService(ShortcutManager.class);
            if (manager != null) {
                manager.enableShortcuts(new ArrayList<>(ids));
                return true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    @Override
    public void disableShortcuts(List<String> ids, CharSequence message) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            final ShortcutManager manager = context.getSystemService(ShortcutManager.class);
            if (manager != null) {
                manager.disableShortcuts(new ArrayList<>(ids), message);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void removeDynamicShortcuts(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(context, new ArrayList<>(ids));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
