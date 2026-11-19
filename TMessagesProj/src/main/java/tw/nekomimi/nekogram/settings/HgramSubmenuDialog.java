package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import java.util.ArrayList;
import java.util.List;

/**
 * One themed submenu for Hgram settings: options, switches, sliders and a footer
 * with Telegram-style buttons. Replaces raw AlertDialog.setItems dialogs so text
 * and button borders stay readable on dark, light and AMOLED themes.
 */
public final class HgramSubmenuDialog {

    public interface ValueFormatter {
        String format(int value);
    }

    public interface OnValueChanged {
        void onChanged(int value);
    }

    public interface OnToggle {
        void onToggle(boolean checked);
    }

    public static final class SliderRow {
        private final LinearLayout view;
        private final TextView valueView;
        private final SeekBar seekBar;
        private final int min;
        private final int step;
        private final ValueFormatter formatter;
        private OnValueChanged listener;
        private boolean notify = true;

        private SliderRow(Context context, Theme.ResourcesProvider resourcesProvider,
                          CharSequence label, CharSequence hint, int min, int max, int step,
                          int value, ValueFormatter formatter) {
            this.min = min;
            this.step = Math.max(1, step);
            this.formatter = formatter;

            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8),
                    AndroidUtilities.dp(20), AndroidUtilities.dp(2));

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText(label);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            title.setTypeface(AndroidUtilities.bold());
            header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            valueView.setTextColor(Theme.getColor(Theme.key_dialogTextGray, resourcesProvider));
            valueView.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
            header.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            view.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (!TextUtils.isEmpty(hint)) {
                TextView hintView = new TextView(context);
                hintView.setText(hint);
                hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                hintView.setTextColor(Theme.getColor(Theme.key_dialogTextGray, resourcesProvider));
                hintView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
                view.addView(hintView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            seekBar = new SeekBar(context);
            seekBar.setMax(Math.max(0, (max - min) / this.step));
            seekBar.setProgressTintList(ColorStateList.valueOf(
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider)));
            seekBar.setThumbTintList(ColorStateList.valueOf(
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider)));
            seekBar.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
            view.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            setValue(value, false);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    updateValueText();
                    if (notify && listener != null) {
                        listener.onChanged(getValue());
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                }
            });
        }

        public int getValue() {
            return min + seekBar.getProgress() * step;
        }

        public void setValue(int value, boolean notifyListener) {
            final int snapped = Math.round((value - min) / (float) step) * step + min;
            notify = false;
            seekBar.setProgress(Math.max(0, Math.min(seekBar.getMax(), (snapped - min) / step)));
            updateValueText();
            notify = true;
            if (notifyListener && listener != null) {
                listener.onChanged(getValue());
            }
        }

        public void setOnValueChanged(OnValueChanged listener) {
            this.listener = listener;
        }

        public void setEnabled(boolean enabled) {
            seekBar.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.45f);
        }

        public void setVisible(boolean visible) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        public View getView() {
            return view;
        }

        private void updateValueText() {
            valueView.setText(formatter == null ? String.valueOf(getValue()) : formatter.format(getValue()));
        }
    }

    public static final class Builder {
        private final Context context;
        private final Theme.ResourcesProvider resourcesProvider;
        private final LinearLayout items;
        private final List<View> footer = new ArrayList<>();
        private final List<AlertDialog[]> optionDialogs = new ArrayList<>();
        private CharSequence title;
        private CharSequence message;
        private Runnable dismissListener;

        public Builder(Context context, Theme.ResourcesProvider resourcesProvider) {
            this.context = context;
            this.resourcesProvider = resourcesProvider;
            this.items = new LinearLayout(context);
            this.items.setOrientation(LinearLayout.VERTICAL);
        }

        public Builder title(CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder message(CharSequence message) {
            this.message = message;
            return this;
        }

        public Builder option(CharSequence label, boolean checked, Runnable action) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
            row.setBackground(Theme.createRadSelectorDrawable(
                    0x00000000, Theme.getColor(Theme.key_listSelector, resourcesProvider),
                    0, 0));

            TextView text = new TextView(context);
            text.setText(label);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView check = new TextView(context);
            check.setText(checked ? "✓" : "");
            check.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            check.setTypeface(AndroidUtilities.bold());
            check.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
            check.setGravity(Gravity.CENTER);
            row.addView(check, LayoutHelper.createLinear(24, LayoutHelper.WRAP_CONTENT));

            row.setMinimumHeight(AndroidUtilities.dp(48));
            row.setClickable(true);
            row.setFocusable(true);
            final AlertDialog[] holder = new AlertDialog[1];
            optionDialogs.add(holder);
            row.setOnClickListener(v -> {
                if (action != null) action.run();
                if (holder[0] != null) holder[0].dismiss();
            });
            items.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return this;
        }

        public Builder switchItem(CharSequence label, boolean checked, OnToggle toggle) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
            row.setMinimumHeight(AndroidUtilities.dp(48));

            TextView text = new TextView(context);
            text.setText(label);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            text.setMaxLines(2);
            text.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            Switch switchView = new Switch(context, resourcesProvider);
            switchView.setColors(
                    Theme.key_switchTrack,
                    Theme.key_switchTrackChecked,
                    Theme.key_windowBackgroundWhite,
                    Theme.key_windowBackgroundWhite);
            switchView.setChecked(checked, false);
            switchView.setOnCheckedChangeListener((view, isChecked) -> {
                if (toggle != null) toggle.onToggle(isChecked);
            });
            row.addView(switchView, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL));
            row.setOnClickListener(v -> switchView.setChecked(!switchView.isChecked(), true));
            items.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return this;
        }

        public SliderRow slider(CharSequence label, CharSequence hint, int min, int max, int step,
                                int value, ValueFormatter formatter) {
            SliderRow row = new SliderRow(context, resourcesProvider, label, hint, min, max, step,
                    value, formatter);
            items.addView(row.getView(), LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return row;
        }

        public Builder footerButton(CharSequence label, Runnable action) {
            return footerButton(label, action, false);
        }

        public Builder footerButton(CharSequence label, Runnable action, boolean dismiss) {
            TextView button = new TextView(context);
            button.setText(label);
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextColor(Theme.getColor(Theme.key_dialogButton, resourcesProvider));
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(true);
            button.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            button.setBackground(Theme.createRadSelectorDrawable(
                    0x00000000, Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider),
                    0, 0));
            final AlertDialog[] holder = new AlertDialog[1];
            button.setOnClickListener(v -> {
                if (action != null) action.run();
                if (dismiss && holder[0] != null) holder[0].dismiss();
            });
            footer.add(button);
            button.setTag(holder);
            return this;
        }

        public Builder onDismiss(Runnable listener) {
            this.dismissListener = listener;
            return this;
        }

        public AlertDialog show() {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));

            if (!TextUtils.isEmpty(message)) {
                TextView messageView = new TextView(context);
                messageView.setText(message);
                messageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                messageView.setTextColor(Theme.getColor(Theme.key_dialogTextGray, resourcesProvider));
                messageView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4),
                        AndroidUtilities.dp(24), AndroidUtilities.dp(8));
                root.addView(messageView, LayoutHelper.createLinear(
                        LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(false);
            scroll.addView(items, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (!footer.isEmpty()) {
                View divider = new View(context);
                divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
                root.addView(divider, LayoutHelper.createLinear(
                        LayoutHelper.MATCH_PARENT, 1));

                LinearLayout footerRow = new LinearLayout(context);
                footerRow.setOrientation(LinearLayout.HORIZONTAL);
                footerRow.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
                for (View button : footer) {
                    footerRow.addView(button, LayoutHelper.createLinear(
                            0, 48, 1f));
                }
                root.addView(footerRow, LayoutHelper.createLinear(
                        LayoutHelper.MATCH_PARENT, 48));
            }

            AlertDialog dialog = new AlertDialog.Builder(context, resourcesProvider)
                    .setTitle(title)
                    .setView(root)
                    .create();

            for (View button : footer) {
                Object tag = button.getTag();
                if (tag instanceof AlertDialog[]) {
                    ((AlertDialog[]) tag)[0] = dialog;
                }
            }
            for (AlertDialog[] holder : optionDialogs) {
                holder[0] = dialog;
            }
            dialog.setOnDismissListener(d -> {
                if (dismissListener != null) dismissListener.run();
            });
            dialog.show();
            return dialog;
        }
    }

    private HgramSubmenuDialog() {
    }
}
