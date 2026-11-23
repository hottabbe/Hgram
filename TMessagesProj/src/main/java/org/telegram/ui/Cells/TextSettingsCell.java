/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Cells;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

import java.util.ArrayList;

public class TextSettingsCell extends FrameLayout {

    private Theme.ResourcesProvider resourcesProvider;
    private TextView textView;
    private AnimatedTextView valueTextView;
    private ImageView imageView;
    private boolean imageViewIsColorful;
    private BackupImageView valueBackupImageView;
    private ImageView valueImageView;
    public boolean needDivider;
    private boolean canDisable;
    private boolean drawLoading;
    private int padding;
    private int valueTextColor;
    private TextView valueActionTextView;
    private boolean valueActionLayout;
    private boolean valueEllipsizeToHalf;

    private boolean incrementLoadingProgress;
    private float loadingProgress;
    private float drawLoadingProgress;
    private int loadingSize;
    private boolean measureDelay;
    private int changeProgressStartDelay;

    Paint paint;

    public TextSettingsCell(Context context) {
        this(context, 21);
    }

    public TextSettingsCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        this(context, 21, resourcesProvider);
    }

    public TextSettingsCell(Context context, int padding) {
        this(context, padding, null);
    }

    public TextSettingsCell(Context context, int padding, Theme.ResourcesProvider resourcesProvider) {
        this(context, padding,
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteValueText, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider),
                resourcesProvider);
    }

    /** Package-private constructor with explicit colors so unit tests do not init the Theme. */
    TextSettingsCell(Context context, int padding, int textColor, int valueTextColor, int iconColor) {
        this(context, padding, textColor, valueTextColor, iconColor, null);
    }

    private TextSettingsCell(Context context, int padding, int textColor, int valueTextColor, int iconColor, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.padding = padding;
        this.valueTextColor = valueTextColor;

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setLines(1);
        textView.setMaxLines(1);
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        textView.setTextColor(textColor);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, padding, 0, padding, 0));

        valueTextView = new AnimatedTextView(context, true, true, !LocaleController.isRTL);
        valueTextView.setAnimationProperties(.55f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
        valueTextView.setTextSize(AndroidUtilities.dp(16));
        valueTextView.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
        valueTextView.setTextColor(valueTextColor);
        addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, padding - 4, 0, padding - 4, 0));

        imageView = new RLottieImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
        imageView.setVisibility(GONE);
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 16, 0, 16, 0));

        valueImageView = new ImageView(context);
        valueImageView.setScaleType(ImageView.ScaleType.CENTER);
        valueImageView.setVisibility(INVISIBLE);
        valueImageView.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        addView(valueImageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, padding, 0, padding, 0));
    }

    public ImageView getValueImageView() {
        return valueImageView;
    }

    private boolean betterLayout = BuildVars.DEBUG_PRIVATE_VERSION;
    public void setBetterLayout(boolean betterLayout) {
        // I might break something with this, gonna need to further test
        this.betterLayout = betterLayout;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(50) + (needDivider ? 1 : 0));

        int availableWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight() - AndroidUtilities.dp(34);
        int width = betterLayout ? availableWidth : availableWidth / 2;
        if (valueImageView.getVisibility() == VISIBLE) {
            valueImageView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
        }

        if (imageView.getVisibility() == VISIBLE) {
            if (imageViewIsColorful) {
                imageView.measure(MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(28), MeasureSpec.EXACTLY));
            } else {
                imageView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST));
            }
            if (betterLayout) width -= imageView.getMeasuredWidth() + AndroidUtilities.dp(8);
        }

        if (valueBackupImageView != null) {
            valueBackupImageView.measure(MeasureSpec.makeMeasureSpec(valueBackupImageView.getLayoutParams().height, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(valueBackupImageView.getLayoutParams().width, MeasureSpec.EXACTLY));
            if (betterLayout) width -= valueBackupImageView.getMeasuredWidth() + AndroidUtilities.dp(8);
        }
        if (valueActionLayout) {
            width = measureValueActionLayout(availableWidth);
        } else if (valueTextView.getVisibility() == VISIBLE) {
            valueTextView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            final int valueWidth = valueEllipsizeToHalf
                    ? Math.min(valueTextView.getMeasuredWidth(), width)
                    : valueTextView.getMeasuredWidth();
            if (betterLayout) {
                width -= valueWidth + AndroidUtilities.dp(8);
            } else {
                width = availableWidth - valueWidth - AndroidUtilities.dp(8);
            }

            if (valueImageView.getVisibility() == VISIBLE) {
                MarginLayoutParams params = (MarginLayoutParams) valueImageView.getLayoutParams();
                if (LocaleController.isRTL) {
                    params.leftMargin = AndroidUtilities.dp(padding + 4) + valueWidth;
                } else {
                    params.rightMargin = AndroidUtilities.dp(padding + 4) + valueWidth;
                }
            }
        } else {
            width = availableWidth;
        }
        // A negative width turns into a huge EXACTLY spec and lets the title run over the value.
        width = Math.max(0, Math.min(width, availableWidth));
        textView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    private void ensureValueActionView() {
        if (valueActionTextView != null) {
            return;
        }
        valueActionTextView = new TextView(getContext());
        valueActionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        valueActionTextView.setLines(1);
        valueActionTextView.setMaxLines(1);
        valueActionTextView.setSingleLine(true);
        valueActionTextView.setEllipsize(TextUtils.TruncateAt.END);
        valueActionTextView.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
        valueActionTextView.setTextColor(valueTextColor);
        addView(valueActionTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, padding - 4, 0, padding, 0));
    }

    private int measureValueAction(int maxWidth) {
        if (valueActionTextView == null || valueActionTextView.getVisibility() != VISIBLE) {
            return 0;
        }
        final int height = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
        if (maxWidth <= 0) {
            valueActionTextView.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), height);
            return 0;
        }
        int textSize = 16;
        valueActionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize);
        int naturalWidth = (int) Math.ceil(valueActionTextView.getPaint().measureText(valueActionTextView.getText().toString()));
        while (naturalWidth > maxWidth && textSize > 10) {
            textSize--;
            valueActionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize);
            naturalWidth = (int) Math.ceil(valueActionTextView.getPaint().measureText(valueActionTextView.getText().toString()));
        }
        valueActionTextView.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), height);
        return valueActionTextView.getMeasuredWidth();
    }

    /**
     * Layout for the OCR-models row: the status may be ellipsized, while the action on the
     * right always keeps its own space. The title is never allowed to collapse below zero.
     */
    private int measureValueActionLayout(int availableWidth) {
        final int gap = AndroidUtilities.dp(8);
        final int statusMin = Math.min(AndroidUtilities.dp(16), Math.max(0, availableWidth / 4));
        int titleMin = Math.min(AndroidUtilities.dp(48), Math.max(0, availableWidth / 2));
        int actionMax = Math.max(0, availableWidth - titleMin - statusMin - gap * 2);
        int actionWidth = measureValueAction(actionMax);
        if (actionWidth >= actionMax && titleMin > AndroidUtilities.dp(32)) {
            titleMin = Math.min(AndroidUtilities.dp(32), Math.max(0, availableWidth / 2));
            actionMax = Math.max(0, availableWidth - titleMin - statusMin - gap * 2);
            actionWidth = measureValueAction(actionMax);
        }

        int statusWidth = 0;
        if (valueTextView.getVisibility() == VISIBLE) {
            int statusLimit = Math.min(availableWidth / 2, Math.max(0, availableWidth - actionWidth - titleMin - gap * 2));
            statusLimit = Math.max(1, statusLimit);
            valueTextView.setAdaptWidth(true);
            valueTextView.measure(MeasureSpec.makeMeasureSpec(statusLimit, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            statusWidth = valueTextView.getMeasuredWidth();
            if (statusWidth > statusLimit) {
                valueTextView.setAdaptWidth(false);
                valueTextView.measure(MeasureSpec.makeMeasureSpec(statusLimit, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
                statusWidth = Math.min(valueTextView.getMeasuredWidth(), statusLimit);
            }
        }

        final MarginLayoutParams valueParams = (MarginLayoutParams) valueTextView.getLayoutParams();
        if (LocaleController.isRTL) {
            valueParams.leftMargin = AndroidUtilities.dp(padding) + actionWidth + gap;
            valueParams.rightMargin = AndroidUtilities.dp(padding);
        } else {
            valueParams.leftMargin = AndroidUtilities.dp(padding - 4);
            valueParams.rightMargin = AndroidUtilities.dp(padding) + actionWidth + gap;
        }

        final int titleWidth = availableWidth - actionWidth - statusWidth - gap * 2;
        return Math.max(0, Math.min(titleWidth, availableWidth));
    }

    private void resetValueActionLayout() {
        valueActionLayout = false;
        valueEllipsizeToHalf = false;
        valueTextView.setAdaptWidth(true);
        valueTextView.setRelayoutOnBoundsChange(false);
        if (valueActionTextView != null) {
            valueActionTextView.setVisibility(GONE);
        }
        final MarginLayoutParams params = (MarginLayoutParams) valueTextView.getLayoutParams();
        if (params != null) {
            params.leftMargin = AndroidUtilities.dp(padding - 4);
            params.rightMargin = AndroidUtilities.dp(padding);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (measureDelay && getParent() != null) {
            changeProgressStartDelay = (int) ((getTop() / (float) ((View) getParent()).getMeasuredHeight()) * 150f);
        }
    }

    public TextView getTextView() {
        return textView;
    }

    public void setCanDisable(boolean value) {
        canDisable = value;
    }

    public AnimatedTextView getValueTextView() {
        return valueTextView;
    }

    public void setTextColor(int color) {
        textView.setTextColor(color);
    }

    public void setTextValueColor(int color) {
        valueTextColor = color;
        valueTextView.setTextColor(color);
        if (valueActionTextView != null) {
            valueActionTextView.setTextColor(color);
        }
    }

    public void setText(CharSequence text, boolean divider) {
        resetValueActionLayout();
        textView.setText(text);
        valueTextView.setVisibility(INVISIBLE);
        valueImageView.setVisibility(INVISIBLE);
        needDivider = divider;
        setWillNotDraw(!divider);
    }

    public void setTextAndValue(CharSequence text, CharSequence value, boolean divider) {
        setTextAndValue(text, value, false, divider);
    }

    public void setTextAndValue(CharSequence text, CharSequence value, boolean animated, boolean divider) {
        resetValueActionLayout();
        textView.setText(text);
        valueImageView.setVisibility(INVISIBLE);
        if (value != null) {
            valueTextView.setText(value, animated);
            valueTextView.setVisibility(VISIBLE);
        } else {
            valueTextView.setVisibility(INVISIBLE);
        }
        needDivider = divider;
        setWillNotDraw(!divider);
        requestLayout();
    }

    /**
     * Opt-in for a value that must stay inside its half of the row. Only the OCR-models row
     * uses this; every other cell keeps the previous geometry.
     */
    public void setValueEllipsizeToHalf(boolean enabled) {
        valueEllipsizeToHalf = enabled;
        if (!valueActionLayout) {
            valueTextView.setAdaptWidth(!enabled);
            valueTextView.setRelayoutOnBoundsChange(enabled);
        }
        requestLayout();
    }

    /**
     * Value split into an ellipsized status and a fully readable action on the right. Used by
     * the OCR-models row so a long action is not silently cut together with the status.
     */
    public void setTextAndValueAction(CharSequence text, CharSequence value, CharSequence action, boolean divider) {
        resetValueActionLayout();
        ensureValueActionView();
        valueActionLayout = true;
        valueEllipsizeToHalf = true;
        valueTextView.setAdaptWidth(false);
        valueTextView.setRelayoutOnBoundsChange(true);
        textView.setText(text);
        valueImageView.setVisibility(INVISIBLE);
        valueTextView.setText(value != null ? value : "", false);
        valueTextView.setVisibility(VISIBLE);
        valueActionTextView.setText(action != null ? action : "");
        valueActionTextView.setVisibility(VISIBLE);
        needDivider = divider;
        setWillNotDraw(!divider);
        requestLayout();
    }

    public TextView getValueActionTextView() {
        return valueActionTextView;
    }

    public void setTextAndIcon(CharSequence text, int resId, boolean divider) {
        resetValueActionLayout();
        textView.setText(text);
        valueTextView.setVisibility(INVISIBLE);
        if (resId != 0) {
            valueImageView.setVisibility(VISIBLE);
            valueImageView.setImageResource(resId);
        } else {
            valueImageView.setVisibility(INVISIBLE);
        }
        needDivider = divider;
        setWillNotDraw(!divider);
    }

    public void setValue(CharSequence value, boolean animated) {
        valueTextView.setText(value, animated);
    }

    public void setIcon(int resId) {
        MarginLayoutParams params = (MarginLayoutParams) textView.getLayoutParams();
        imageViewIsColorful = false;
        if (resId == 0) {
            imageView.setVisibility(GONE);
            if (LocaleController.isRTL) {
                params.rightMargin = AndroidUtilities.dp(this.padding);
            } else {
                params.leftMargin = AndroidUtilities.dp(this.padding);
            }
        } else {
            imageView.setImageResource(resId);
            imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
            imageView.setBackground(null);
            imageView.setVisibility(VISIBLE);
            if (LocaleController.isRTL) {
                params.rightMargin = AndroidUtilities.dp(58);
            } else {
                params.leftMargin = AndroidUtilities.dp(58);
            }
        }
    }

    public void setColorfulIcon(int resId, int color) {
        MarginLayoutParams params = (MarginLayoutParams) textView.getLayoutParams();
        imageViewIsColorful = true;
        if (resId == 0) {
            imageView.setVisibility(GONE);
            if (LocaleController.isRTL) {
                params.rightMargin = AndroidUtilities.dp(this.padding);
            } else {
                params.leftMargin = AndroidUtilities.dp(this.padding);
            }
        } else {
            imageView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(8), color));
            imageView.setImageResource(resId);
            imageView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY));
            imageView.setVisibility(VISIBLE);
            if (LocaleController.isRTL) {
                params.rightMargin = AndroidUtilities.dp(58);
            } else {
                params.leftMargin = AndroidUtilities.dp(58);
            }
        }
    }

    public void setEnabled(boolean value, ArrayList<Animator> animators) {
        setEnabled(value);
        if (animators != null) {
            animators.add(ObjectAnimator.ofFloat(textView, "alpha", value ? 1.0f : 0.5f));
            if (valueTextView.getVisibility() == VISIBLE) {
                animators.add(ObjectAnimator.ofFloat(valueTextView, "alpha", value ? 1.0f : 0.5f));
            }
            if (valueImageView.getVisibility() == VISIBLE) {
                animators.add(ObjectAnimator.ofFloat(valueImageView, "alpha", value ? 1.0f : 0.5f));
            }
        } else {
            textView.setAlpha(value ? 1.0f : 0.5f);
            if (valueTextView.getVisibility() == VISIBLE) {
                valueTextView.setAlpha(value ? 1.0f : 0.5f);
            }
            if (valueImageView.getVisibility() == VISIBLE) {
                valueImageView.setAlpha(value ? 1.0f : 0.5f);
            }
        }
    }

    @Override
    public void setEnabled(boolean value) {
        super.setEnabled(value);
        textView.setAlpha(value || !canDisable ? 1.0f : 0.5f);
        if (valueTextView.getVisibility() == VISIBLE) {
            valueTextView.setAlpha(value || !canDisable ? 1.0f : 0.5f);
        }
        if (valueImageView.getVisibility() == VISIBLE) {
            valueImageView.setAlpha(value || !canDisable ? 1.0f : 0.5f);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (drawLoading || drawLoadingProgress != 0) {
            if (paint == null) {
                paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Theme.getColor(Theme.key_dialogSearchBackground, resourcesProvider));
            }
            //LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT;
            if (incrementLoadingProgress) {
                loadingProgress += 16 / 1000f;
                if (loadingProgress > 1f) {
                    loadingProgress = 1f;
                    incrementLoadingProgress = false;
                }
            } else {
                loadingProgress -= 16 / 1000f;
                if (loadingProgress < 0) {
                    loadingProgress = 0;
                    incrementLoadingProgress = true;
                }
            }

            if (changeProgressStartDelay > 0) {
                changeProgressStartDelay -= 15;
            } else if (drawLoading && drawLoadingProgress != 1f) {
                drawLoadingProgress += 16 / 150f;
                if (drawLoadingProgress > 1f) {
                    drawLoadingProgress = 1f;
                }
            } else if (!drawLoading && drawLoadingProgress != 0) {
                drawLoadingProgress -= 16 / 150f;
                if (drawLoadingProgress < 0) {
                    drawLoadingProgress = 0;
                }
            }

            float alpha = (0.6f + 0.4f * loadingProgress) * drawLoadingProgress;
            paint.setAlpha((int) (255 * alpha));
            int cy = getMeasuredHeight() >> 1;
            AndroidUtilities.rectTmp.set(getMeasuredWidth() - AndroidUtilities.dp(padding) - AndroidUtilities.dp(loadingSize), cy - AndroidUtilities.dp(3), getMeasuredWidth() - AndroidUtilities.dp(padding), cy + AndroidUtilities.dp(3));
            if (LocaleController.isRTL) {
                AndroidUtilities.rectTmp.left = getMeasuredWidth() - AndroidUtilities.rectTmp.left;
                AndroidUtilities.rectTmp.right = getMeasuredWidth() - AndroidUtilities.rectTmp.right;
            }
            canvas.drawRoundRect(AndroidUtilities.rectTmp, AndroidUtilities.dp(3), AndroidUtilities.dp(3), paint);
            invalidate();
        }
        valueTextView.setAlpha(1f - drawLoadingProgress);
        super.dispatchDraw(canvas);

        if (needDivider) {
            int offset = AndroidUtilities.dp(imageView.getVisibility() == View.VISIBLE ? 58 : 20);
            canvas.drawLine(LocaleController.isRTL ? 0 : offset, getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? offset : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }


    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        String text = textView.getText()
                + (valueTextView != null && valueTextView.getVisibility() == View.VISIBLE ? "\n" + valueTextView.getText() : "");
        if (valueActionTextView != null && valueActionTextView.getVisibility() == View.VISIBLE) {
            text += "\n" + valueActionTextView.getText();
        }
        info.setText(text);
        info.setEnabled(isEnabled());
    }

    public void setDrawLoading(boolean drawLoading, int size, boolean animated) {
        this.drawLoading = drawLoading;
        this.loadingSize = size;

        if (!animated) {
            drawLoadingProgress = drawLoading ? 1f : 0f;
        } else {
            measureDelay = true;
        }
        invalidate();
    }

    public BackupImageView getValueBackupImageView() {
        if (valueBackupImageView == null) {
            valueBackupImageView = new BackupImageView(getContext());
            addView(valueBackupImageView, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, padding - 4, 0, padding - 4, 0));
        }
        return valueBackupImageView;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (valueBackupImageView != null && valueBackupImageView.getImageReceiver() != null && valueBackupImageView.getImageReceiver().getDrawable() instanceof AnimatedEmojiDrawable) {
            ((AnimatedEmojiDrawable) valueBackupImageView.getImageReceiver().getDrawable()).removeView(this);
        }
    }

    public void updateRTL() {
        textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        removeView(textView);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, padding, 0, padding, 0));

        valueTextView.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
        removeView(valueTextView);
        addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, padding, 0, padding, 0));

        removeView(imageView);
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 21, 0, 21, 0));

        removeView(valueImageView);
        addView(valueImageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, padding, 0, padding, 0));
    }
}
