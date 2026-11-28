package xyz.nextalone.nagram.hgram;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

/** Opaque placeholder for an inline video/round player while anti-peek is active. */
public final class HgramAntiPeekVideoMask {

    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private HgramAntiPeekVideoMask() {
    }

    public static boolean draw(Canvas canvas, View container, int color, boolean round) {
        if (!HgramConfig.isAntiPeekActive() || canvas == null || container == null) {
            return false;
        }
        final float width = container.getWidth() * container.getScaleX();
        final float height = container.getHeight() * container.getScaleY();
        if (width <= 0 || height <= 0) {
            return false;
        }
        final float centerX = container.getLeft() + container.getWidth() / 2f;
        final float centerY = container.getTop() + container.getHeight() / 2f;
        final float left = centerX - width / 2f;
        final float top = centerY - height / 2f;
        final float right = centerX + width / 2f;
        final float bottom = centerY + height / 2f;
        paint.setColor(color);
        if (round) {
            canvas.drawCircle(centerX, centerY, Math.min(width, height) / 2f, paint);
        } else {
            canvas.drawRoundRect(left, top, right, bottom, AndroidUtilities.dp(12), AndroidUtilities.dp(12), paint);
        }
        return true;
    }
}
