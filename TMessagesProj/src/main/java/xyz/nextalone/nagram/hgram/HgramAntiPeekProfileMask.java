package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Opaque profile cover used while anti-peek is active. */
public class HgramAntiPeekProfileMask extends View {

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    public HgramAntiPeekProfileMask(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!HgramConfig.isAntiPeekActive()) {
            return;
        }
        backgroundPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);

        textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textPaint.setTextSize(AndroidUtilities.dp(16));
        textPaint.setTextAlign(Paint.Align.CENTER);
        String text = LocaleController.getString(R.string.HgramAntiPeekHidden);
        canvas.drawText(text, getWidth() / 2f, getHeight() / 2f, textPaint);
    }
}
