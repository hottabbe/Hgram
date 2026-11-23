package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.lerp;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.R;

public class SummaryIcon extends Drawable {

    private static final long LOADING_PERIOD_MS = 900L;

    private boolean on;
    private boolean loading;
    private final AnimatedFloat progress;
    private final Drawable arrow, stars;
    private final Paint loadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 0xFF;

    public SummaryIcon(View view) {
        progress = new AnimatedFloat(view, 420, CubicBezierInterpolator.EASE_OUT_QUINT);

        arrow = view.getContext().getResources().getDrawable(R.drawable.summary_arrow);
        stars = view.getContext().getResources().getDrawable(R.drawable.summary_stars);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (loading) {
            drawLoading(canvas);
            return;
        }

        stars.setBounds(getBounds());
        stars.setAlpha(alpha);
        stars.draw(canvas);

        final float t = progress.set(on);
        final float cx = getBounds().centerX();
        final float cy = getBounds().centerY();
        final float sz = getBounds().width();

        canvas.save();
        if (t < 0.5f) {
            final float s = Math.abs(t - 0.5f) + 0.5f;
            canvas.scale(s, s, cx, cy);
        }

        canvas.save();
        if (t > 0.5f) {
            final float s = (Math.abs(t - 0.5f) + 0.5f);
            canvas.scale(-s, -s, getBounds().left + sz * 0.32f, getBounds().bottom - sz * 0.32f);
            canvas.translate(-sz * (1.0f - s) * 0.4f, sz * (1.0f - s) * 0.4f);
        }
        arrow.setBounds(getBounds());
        arrow.setAlpha(alpha);
        arrow.draw(canvas);
        canvas.restore();

        canvas.save();
        if (t > 0.5f) {
            final float s = (Math.abs(t - 0.5f) + 0.5f);
            canvas.scale(-s, -s, getBounds().right - sz * 0.32f, getBounds().top + sz * 0.32f);
        }
        canvas.rotate(180f, cx, cy);
        if (t > 0.5f) {
            final float s = (Math.abs(t - 0.5f) + 0.5f);
            canvas.translate(-sz * (1.0f - s) * 0.4f, sz * (1.0f - s) * 0.4f);
        }
        arrow.setBounds(getBounds());
        arrow.setAlpha(alpha);
        arrow.draw(canvas);
        canvas.restore();

        canvas.restore();
    }

    /** A rotating 270° arc in the same bounds as the stars; callers animate it by invalidating. */
    private void drawLoading(Canvas canvas) {
        final int size = Math.min(getBounds().width(), getBounds().height());
        if (size <= 0) return;
        final float stroke = Math.max(1f, size * 0.11f);
        final float inset = stroke * 0.5f + size * 0.12f;
        loadingPaint.setStyle(Paint.Style.STROKE);
        loadingPaint.setStrokeWidth(stroke);
        loadingPaint.setStrokeCap(Paint.Cap.ROUND);
        loadingPaint.setColor(0xFFFFFFFF);
        loadingPaint.setAlpha(alpha);
        final float angle = (SystemClock.elapsedRealtime() % LOADING_PERIOD_MS) * 360f / LOADING_PERIOD_MS;
        canvas.drawArc(getBounds().left + inset, getBounds().top + inset,
                getBounds().right - inset, getBounds().bottom - inset,
                angle, 270f, false, loadingPaint);
    }

    public void set(boolean on) {
        set(on, true);
    }
    public void set(boolean on, boolean animated) {
        this.on = on;
        if (!animated) {
            progress.set(on);
        }
    }

    /** Loading swaps the stars/arrows for a spinner; bounds and intrinsic size are unchanged. */
    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    @Override
    public int getIntrinsicWidth() {
        return arrow.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return arrow.getIntrinsicHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        arrow.setColorFilter(colorFilter);
        stars.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }
}
