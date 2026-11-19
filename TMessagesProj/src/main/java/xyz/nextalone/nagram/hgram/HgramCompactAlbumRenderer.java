package xyz.nextalone.nagram.hgram;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;

import java.util.List;

/** Draws a swipeable album as a compact stack while leaving item selection to ChatMessageCell. */
public final class HgramCompactAlbumRenderer {

    private android.graphics.Typeface counterTypeface;

    private String counterLabel;
    private int counterLabelIndex = -1;
    private int counterLabelCount = -1;
    private final Paint counterBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint counterText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF counterBounds = new RectF();
    private final RectF clipBounds = new RectF();
    private final RectF letterboxBounds = new RectF();
    private final Paint letterboxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path badgePath = new Path();

    public static String counterLabel(int index, int count) {
        int safeCount = Math.max(1, count);
        int current = (index % safeCount + safeCount) % safeCount;
        return (current + 1) + "/" + safeCount;
    }

    public static float effectiveWidth(float imageWidth, float containerWidth) {
        return Math.max(1f, imageWidth > 0f ? imageWidth : containerWidth);
    }

    public static float swipeThreshold(float width, float minPx) {
        return Math.max(Math.max(1f, minPx), Math.max(1f, width) * 0.25f);
    }

    public static boolean isForward(float offset, boolean rtl) {
        return rtl ? offset > 0f : offset < 0f;
    }

    public void draw(Canvas canvas, List<ImageReceiver> images, List<ImageReceiver> blurredImages, boolean[] playable,
                     int index, float x, float y, float width, float height,
                     float swipeOffset, float density, int chipColor, int textColor, Path bubbleClip) {
        if (images == null || images.isEmpty() || width <= 0 || height <= 0) {
            return;
        }
        int count = images.size();
        int current = (index % count + count) % count;
        float progress = Math.min(1f, Math.abs(swipeOffset) / (width * 0.72f));
        float cardWidth = width * 0.94f;
        float cardHeight = Math.max(1, height - 12 * density);
        float heroX = x + (width - cardWidth) * 0.5f;
        float heroY = y;

        int save = canvas.save();
        if (bubbleClip != null) {
            canvas.clipPath(bubbleClip);
        }
        clipBounds.set(x, y, x + width, y + height);
        canvas.clipRect(clipBounds);

        int direction = swipeOffset > 0 ? -1 : 1;
        int incoming = (current + direction + count) % count;
        for (int depth = Math.min(3, count - 1); depth >= 1; depth--) {
            int back = (current + direction * depth + count) % count;
            drawStackCard(canvas, images.get(back), blurredImages, false, back, heroX, heroY,
                    cardWidth, cardHeight, depth, 1f, density);
        }

        // Sink and blur the outgoing card; lift and sharpen the next one in place.
        // No horizontal translation: both cards stay centred within the stack.
        drawStackCard(canvas, images.get(current), blurredImages,
                playable != null && current < playable.length && playable[current],
                current, heroX, heroY, cardWidth, cardHeight, 3f * progress, progress, density);
        if (progress > 0) {
            int layer = canvas.saveLayerAlpha(clipBounds, Math.round(255 * progress));
            drawStackCard(canvas, images.get(incoming), blurredImages,
                    playable != null && incoming < playable.length && playable[incoming],
                    incoming, heroX, heroY, cardWidth, cardHeight, 1f - progress, 1f - progress, density);
            canvas.restoreToCount(layer);
        }

        if (counterLabel == null || counterLabelIndex != current || counterLabelCount != count) {
            counterLabel = counterLabel(current, count);
            counterLabelIndex = current;
            counterLabelCount = count;
        }
        final String label = counterLabel;
        counterText.setTextSize(12 * density);
        if (counterTypeface == null) {
            counterTypeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL);
        }
        counterText.setTypeface(counterTypeface);
        counterText.setColor(textColor);
        float labelWidth = counterText.measureText(label);
        float chipWidth = labelWidth + 18 * density;
        float chipHeight = 24 * density;
        float chipLeft = x + (width - chipWidth) * 0.5f;
        float chipTop = y + height - chipHeight - 7 * density;
        counterBounds.set(chipLeft, chipTop, chipLeft + chipWidth, chipTop + chipHeight);
        counterBackground.setColor(chipColor);
        canvas.drawRoundRect(counterBounds, chipHeight * 0.5f, chipHeight * 0.5f, counterBackground);
        canvas.drawText(label, chipLeft + 9 * density, chipTop + (chipHeight - counterText.ascent() - counterText.descent()) * 0.5f, counterText);
        canvas.restoreToCount(save);
    }

    private void drawStackCard(Canvas canvas, ImageReceiver image, List<ImageReceiver> blurredImages,
                               boolean playable, int index, float x, float y, float width, float height,
                               float depth, float blur, float density) {
        float scale = 1f - depth * 0.02f;
        float cardWidth = width * scale;
        float cardHeight = height * scale;
        float cardX = x + (width - cardWidth) * 0.5f;
        float cardY = y + height - cardHeight + depth * 4 * density;
        drawCard(canvas, image, cardX, cardY, cardWidth, cardHeight, 1f, density, playable);
        if (blur > 0 && blurredImages != null && index < blurredImages.size()) {
            drawCard(canvas, blurredImages.get(index), cardX, cardY, cardWidth, cardHeight, blur, density, false);
        }
    }

    private void drawCard(Canvas canvas, ImageReceiver receiver, float x, float y, float width, float height,
                          float alpha, float density, boolean playable) {
        float drawX = x;
        float drawY = y;
        float drawWidth = width;
        float drawHeight = height;
        final int bitmapWidth = receiver.getBitmapWidth();
        final int bitmapHeight = receiver.getBitmapHeight();
        if (bitmapWidth > 1 && bitmapHeight > 1) {
            // Fit the whole frame; never crop and never upscale a smaller bitmap.
            float scale = Math.min(1f, Math.min(width / bitmapWidth, height / bitmapHeight));
            drawWidth = bitmapWidth * scale;
            drawHeight = bitmapHeight * scale;
            drawX = x + (width - drawWidth) * 0.5f;
            drawY = y + (height - drawHeight) * 0.5f;
            letterboxBounds.set(x, y, x + width, y + height);
            letterboxPaint.setColor(0x66000000);
            canvas.drawRoundRect(letterboxBounds, 9 * density, 9 * density, letterboxPaint);
        }
        receiver.setImageCoords(drawX, drawY, drawWidth, drawHeight);
        receiver.setAlpha(alpha);
        receiver.setVisible(true, false);
        receiver.draw(canvas);
        receiver.setAlpha(1f);
        if (playable) {
            drawPlayBadge(canvas, x, y, width, height, density);
        }
    }

    private void drawPlayBadge(Canvas canvas, float x, float y, float width, float height, float density) {
        float radius = 11 * density;
        float cx = x + 12 * density + radius;
        float cy = y + height - 12 * density - radius;
        badgePaint.setColor(0x99000000);
        canvas.drawCircle(cx, cy, radius, badgePaint);
        badgePaint.setColor(0xffffffff);
        badgePath.reset();
        badgePath.moveTo(cx - 3 * density, cy - 5 * density);
        badgePath.lineTo(cx + 5 * density, cy);
        badgePath.lineTo(cx - 3 * density, cy + 5 * density);
        badgePath.close();
        canvas.drawPath(badgePath, badgePaint);
    }
}
