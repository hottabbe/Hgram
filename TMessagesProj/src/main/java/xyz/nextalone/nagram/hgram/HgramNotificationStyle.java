package xyz.nextalone.nagram.hgram;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.Locale;

/**
 * Single notification media style. BigPictureStyle is the final style for every media preview;
 * AUTO resolves to SYSTEM on all devices, including MIUI/HyperOS, so no custom RemoteViews are
 * injected unless the user explicitly chooses CUSTOM. The optional CUSTOM path keeps the old
 * RemoteViews fallback but sets a BigPictureStyle first for launchers that ignore it.
 */
public final class HgramNotificationStyle {

    private static volatile Boolean miuiFamily;

    private HgramNotificationStyle() {
    }

    public static boolean isMiuiFamily() {
        Boolean cached = miuiFamily;
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            final String manufacturer = Build.MANUFACTURER == null
                    ? "" : Build.MANUFACTURER.toLowerCase(Locale.US);
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
                    || manufacturer.contains("poco")) {
                result = true;
            } else {
                result = !TextUtils.isEmpty(systemProperty("ro.miui.ui.version.name"))
                        || !TextUtils.isEmpty(systemProperty("ro.mi.os.version.name"))
                        || !TextUtils.isEmpty(systemProperty("ro.miui.ui.version.code"));
            }
        } catch (Throwable t) {
            FileLog.e("HgramNotificationStyle: MIUI detection failed");
        }
        miuiFamily = result;
        return result;
    }

    private static String systemProperty(String key) {
        try {
            final Class<?> clazz = Class.forName("android.os.SystemProperties");
            final Object value = clazz.getMethod("get", String.class).invoke(null, key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Configured mode with AUTO fallback; AUTO is resolved separately by chosenPresentation(). */
    public static int resolveMode() {
        final int configured = HgramConfig.getNotifMediaStyle();
        return configured == HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM
                || configured == HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM
                ? configured : HgramConfig.NOTIF_MEDIA_STYLE_AUTO;
    }

    static int chosenPresentation() {
        final int mode = resolveMode();
        if (mode == HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM
                || mode == HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM) {
            return mode;
        }
        // AUTO is the platform style on MIUI/HyperOS too: custom RemoteViews are decorated
        // or collapsed by the OEM, so they are only used on explicit CUSTOM.
        return HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM;
    }

    public static void apply(NotificationCompat.Builder builder, Bitmap bitmap, int seriesCount,
                             CharSequence caption, PendingIntent contentIntent) {
        if (builder == null || bitmap == null) {
            return;
        }
        // BigPictureStyle is always the base: even the custom RemoteViews path keeps a valid
        // platform style for launchers that ignore setCustomBigContentView.
        final NotificationCompat.BigPictureStyle style =
                new NotificationCompat.BigPictureStyle().bigPicture(bitmap);
        if (seriesCount > 1) {
            final CharSequence count = LocaleController.formatPluralString(
                    "NewMessages", seriesCount);
            style.setSummaryText(count);
            builder.setSubText(count);
        }
        // The final notification receives exactly one style. The system path explicitly clears
        // any RemoteViews so HyperOS cannot decorate/collapse a stale custom content view.
        builder.setCustomBigContentView(null);
        builder.setStyle(style);
        // Alert priority belongs to NotificationsController: silent/debounce/LOW/MIN summaries
        // must keep their policy, while a caller that wants HIGH sets it explicitly.
        builder.setShowWhen(true);
        builder.setAutoCancel(true);
        if (chosenPresentation() == HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM) {
            final RemoteViews custom = buildBigContentView(bitmap, caption, contentIntent);
            if (custom != null) {
                builder.setCustomBigContentView(custom);
            }
        }
    }

    public static RemoteViews buildBigContentView(Bitmap bitmap, CharSequence caption,
                                                  PendingIntent contentIntent) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || bitmap == null) {
            return null;
        }
        try {
            final RemoteViews views = new RemoteViews(context.getPackageName(),
                    R.layout.hgram_notification_media);
            views.setImageViewBitmap(R.id.hgram_notif_image, bitmap);
            if (TextUtils.isEmpty(caption)) {
                views.setViewVisibility(R.id.hgram_notif_caption, View.GONE);
            } else {
                views.setViewVisibility(R.id.hgram_notif_caption, View.VISIBLE);
                views.setTextViewText(R.id.hgram_notif_caption, caption);
            }
            if (contentIntent != null) {
                views.setOnClickPendingIntent(R.id.hgram_notif_root, contentIntent);
            }
            final boolean rtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
                    == View.LAYOUT_DIRECTION_RTL;
            views.setInt(R.id.hgram_notif_root, "setLayoutDirection",
                    rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            return views;
        } catch (Throwable t) {
            FileLog.e("HgramNotificationStyle: cannot build custom view");
            return null;
        }
    }

    public static void logPost(String style, int count) {
        if (!org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            return;
        }
        final String channel = Build.VERSION.SDK_INT >= 26
                ? "channel" : "legacy";
        final int configured = HgramConfig.getNotifMediaStyle();
        final int chosen = chosenPresentation();
        FileLog.d("HgramMedia post miui=" + isMiuiFamily()
                + " sdk=" + Build.VERSION.SDK_INT
                + " channel=" + channel
                + " source=" + style
                + " style=" + (configured == HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM
                ? "system" : configured == HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM
                ? "custom" : "auto")
                + " chosen=" + (chosen == HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM ? "custom" : "system")
                + " count=" + count);
    }
}
