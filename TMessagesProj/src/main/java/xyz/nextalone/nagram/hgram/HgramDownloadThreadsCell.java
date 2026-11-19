package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

/**
 * Slider row for the number of parallel streams used by the file transfer code.
 * Used twice in the network section: download streams and upload streams.
 */
public final class HgramDownloadThreadsCell extends LinearLayout {
    private final TextView title, value, hint;
    private final SeekBarView slider;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean upload;

    public HgramDownloadThreadsCell(Context context) {
        this(context, null, false);
    }

    public HgramDownloadThreadsCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        this(context, resourcesProvider, false);
    }

    public HgramDownloadThreadsCell(Context context, Theme.ResourcesProvider resourcesProvider, boolean upload) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.upload = upload;
        setLayoutParams(new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setOrientation(VERTICAL);
        setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(14), AndroidUtilities.dp(21), AndroidUtilities.dp(16));
        FrameLayout header = new FrameLayout(context);
        title = new TextView(context);
        title.setTextSize(16);
        title.setText(LocaleController.getString(upload ? R.string.HgramUploadThreads : R.string.HgramDownloadThreads));
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 52, 0));
        title.setGravity(Gravity.CENTER_VERTICAL);
        value = new TextView(context);
        value.setGravity(Gravity.CENTER);
        value.setTextSize(16);
        value.setTypeface(AndroidUtilities.bold());
        header.addView(value, LayoutHelper.createFrame(42, 30, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(36)));
        slider = new SeekBarView(context, resourcesProvider);
        slider.setReportChanges(true);
        slider.setSeparatorsCount(16);
        slider.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override public void onSeekBarDrag(boolean stop, float progress) {
                int count = Math.max(1, Math.min(16, Math.round(progress * 15) + 1));
                if (count != getThreads()) {
                    setThreads(count);
                }
                updateValue(count);
            }
            @Override public int getStepsCount() { return 16; }
            @Override public boolean needVisuallyDivideSteps() { return true; }
            @Override public CharSequence getContentDescription() { return title.getText() + ": " + getThreads(); }
        });
        addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(44)));
        FrameLayout scale = new FrameLayout(context);
        TextView min = new TextView(context);
        min.setText(LocaleController.formatString(R.string.HgramDownloadThreadsCount, 1)); min.setTextSize(12);
        min.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        scale.addView(min, LayoutHelper.createFrame(24, 18, Gravity.LEFT));
        TextView max = new TextView(context);
        max.setText(LocaleController.formatString(R.string.HgramDownloadThreadsCount, 16)); max.setTextSize(12); max.setGravity(Gravity.RIGHT);
        max.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        scale.addView(max, LayoutHelper.createFrame(24, 18, Gravity.RIGHT));
        addView(scale, new LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(18)));
        hint = new TextView(context);
        hint.setTextSize(13);
        hint.setText(LocaleController.getString(upload ? R.string.HgramUploadThreadsInfo : R.string.HgramDownloadThreadsInfo));
        addView(hint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        bind();
    }

    private int getThreads() {
        return upload ? HgramConfig.getUploadThreads() : HgramConfig.getDownloadThreads();
    }

    private void setThreads(int count) {
        if (upload) {
            HgramConfig.setUploadThreads(count);
        } else {
            HgramConfig.setDownloadThreads(count);
        }
    }

    private int color(int key) { return Theme.getColor(key, resourcesProvider); }

    public void bind() {
        setBackgroundColor(color(Theme.key_windowBackgroundWhite));
        title.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        hint.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        int accent = color(Theme.key_windowBackgroundWhiteBlueText);
        value.setTextColor(accent);
        value.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(9), (accent & 0x00ffffff) | 0x18000000));
        slider.setColors(color(Theme.key_switchTrack), accent);
        slider.setProgress((getThreads() - 1) / 15f);
        updateValue(getThreads());
    }

    private void updateValue(int count) { value.setText(LocaleController.formatString(R.string.HgramDownloadThreadsCount, count)); }
}
