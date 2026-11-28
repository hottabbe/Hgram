package xyz.nextalone.nagram.hgram;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;

/**
 * Keeps the selected bubble (or a whole album/group of bubbles) visually lifted while the
 * attached message menu is open. The actual view hierarchy belongs to ChatActivity; this
 * class only owns the list of translated cells, the animator and the drawing invalidation.
 */
public final class HgramScrimTranslationAnchor {
    private final ViewGroup chatListView;
    private final View contentView;
    private final ArrayList<View> translatedViews = new ArrayList<>();
    private View scrimView;
    private ValueAnimator translationAnimator;
    private float translationY;

    public HgramScrimTranslationAnchor(ViewGroup chatListView, View contentView) {
        this.chatListView = chatListView;
        this.contentView = contentView;
    }

    public void setScrimView(View view) {
        if (scrimView == view) {
            return;
        }
        reset();
        scrimView = view;
    }

    public float getTranslationY() {
        return translationY;
    }

    public void collect() {
        translatedViews.clear();
        if (scrimView == null) {
            return;
        }
        if (scrimView instanceof ChatMessageCell && chatListView != null) {
            final org.telegram.messenger.MessageObject.GroupedMessages group =
                    ((ChatMessageCell) scrimView).getCurrentMessagesGroup();
            if (group != null) {
                for (int i = 0, n = chatListView.getChildCount(); i < n; i++) {
                    final View child = chatListView.getChildAt(i);
                    if (child instanceof ChatMessageCell
                            && ((ChatMessageCell) child).getCurrentMessagesGroup() == group) {
                        translatedViews.add(child);
                    }
                }
            }
        }
        if (translatedViews.isEmpty()) {
            translatedViews.add(scrimView);
        }
        for (int i = 0, n = translatedViews.size(); i < n; i++) {
            final View view = translatedViews.get(i);
            if (view instanceof ChatMessageCell) {
                ((ChatMessageCell) view).setFullyDraw(true);
            }
        }
    }

    public void apply(float translationY) {
        if (translatedViews.isEmpty() && scrimView != null) {
            collect();
        }
        for (int i = 0, n = translatedViews.size(); i < n; i++) {
            translatedViews.get(i).invalidate();
        }
        this.translationY = translationY;
        invalidateRoots();
    }

    public void reset() {
        if (translationAnimator != null) {
            translationAnimator.removeAllListeners();
            translationAnimator.cancel();
            translationAnimator = null;
        }
        restoreAfterBlurCapture();
        for (int i = 0, n = translatedViews.size(); i < n; i++) {
            final View view = translatedViews.get(i);
            if (view instanceof ChatMessageCell) {
                ((ChatMessageCell) view).setFullyDraw(false);
            }
            view.invalidate();
        }
        translatedViews.clear();
        translationY = 0f;
        invalidateRoots();
    }

    /** Releases the selected view and all listeners; used when the fragment is destroyed. */
    public void release() {
        reset();
        scrimView = null;
    }

    private final ArrayList<View> blurViews = new ArrayList<>();
    private final ArrayList<Float> blurAlphas = new ArrayList<>();

    /** Hides the selected bubble/group for the blur capture; inline menu is hidden by ChatActivity. */
    public void hideForBlurCapture() {
        if (translatedViews.isEmpty()) {
            collect();
        }
        restoreAfterBlurCapture();
        for (int i = 0, n = translatedViews.size(); i < n; i++) {
            final View view = translatedViews.get(i);
            blurViews.add(view);
            blurAlphas.add(view.getAlpha());
            view.setAlpha(0f);
        }
    }

    public void restoreAfterBlurCapture() {
        for (int i = 0, n = blurViews.size(); i < n; i++) {
            blurViews.get(i).setAlpha(blurAlphas.get(i));
        }
        blurViews.clear();
        blurAlphas.clear();
    }

    public void animate(float target, boolean animated) {
        if (translationAnimator != null) {
            translationAnimator.removeAllListeners();
            translationAnimator.cancel();
            translationAnimator = null;
        }
        if (scrimView == null) {
            reset();
            return;
        }
        if (!animated) {
            collect();
            apply(target);
            return;
        }
        collect();
        translationAnimator = ValueAnimator.ofFloat(translationY, target);
        translationAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        translationAnimator.setDuration(260);
        translationAnimator.addUpdateListener(animation ->
                apply((float) animation.getAnimatedValue()));
        translationAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (translationAnimator == animation) {
                    translationAnimator = null;
                }
            }
        });
        translationAnimator.start();
    }

    private void invalidateRoots() {
        if (contentView != null) {
            contentView.invalidate();
        }
        if (chatListView != null) {
            chatListView.invalidate();
        }
    }
}
