package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        android.content.SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("hgram_settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("hgram_icon_v1", false)) {
            setIcon(LauncherIcon.DEFAULT);
            prefs.edit().putBoolean("hgram_icon_v1", true).apply();
        }
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        DEFAULT("DefaultIcon", R.drawable.hgram_launcher, R.drawable.hgram_mark, R.string.HgramIconBlue),
        GOOGLE("GoogleIcon", R.drawable.hgram_icon_dark, R.drawable.hgram_mark_white, R.string.HgramIconDarkBlue),
        COLORFUL("ColorfulIcon", R.drawable.hgram_icon_glass, R.drawable.hgram_mark_glass, R.string.HgramIconOriginal),
        DARKGREEN("DarkGreenIcon", R.drawable.hgram_icon_mint, R.drawable.hgram_mark, R.string.HgramIconMint),
        NEON("NeonIcon", R.drawable.hgram_icon_neon, R.drawable.hgram_mark_neon, R.string.HgramIconNeon),
        NIELLO("NielloIcon", R.drawable.hgram_icon_graphite, R.drawable.hgram_mark_white, R.string.HgramIconGraphite),
        BLUE("BlueIcon", R.drawable.hgram_icon_sunset, R.drawable.hgram_mark_white, R.string.HgramIconSunset),
        DARKBLUE("DarkBlueIcon", R.drawable.hgram_icon_midnight, R.drawable.hgram_mark, R.string.HgramIconMidnight),
        BLURBLUE("BlurBlueIcon", R.drawable.hgram_icon_violet, R.drawable.hgram_mark_white, R.string.HgramIconViolet),
        NOX("PremiumIcon", R.drawable.hgram_icon_pixel, R.drawable.hgram_mark_pixel, R.string.HgramIconPixel);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }

        public boolean isNekoX() {
            return true; // Hgram variants are complete icons, not Telegram background/foreground pairs.
        }
    }
}
