package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.Components.ChatScrimPopupContainerLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ReactionsContainerLayout;

/**
 * Full-screen, non-window overlay that hosts the attached message menu inside the chat's
 * content view. Keeping the menu in the view hierarchy lets it travel together with the
 * lifted bubble and lets the blur capture exclude it.
 */
public final class HgramScrimInlineMenuOverlay extends FrameLayout {
    public interface DismissListener {
        void onDismissed(HgramScrimInlineMenuOverlay overlay);
    }

    private final ChatScrimPopupContainerLayout container;
    private final ReactionsContainerLayout reactionsLayout;
    private final ActionBarMenuSubItem[] focusItems;
    private final Runnable closeAction;
    private final DismissListener dismissListener;
    private boolean dismissed;

    private HgramScrimInlineMenuOverlay(Context context,
                                       ChatScrimPopupContainerLayout container,
                                       ReactionsContainerLayout reactionsLayout,
                                       ActionBarMenuSubItem[] focusItems,
                                       Runnable closeAction,
                                       DismissListener dismissListener) {
        super(context);
        this.container = container;
        this.reactionsLayout = reactionsLayout;
        this.focusItems = focusItems;
        this.closeAction = closeAction;
        this.dismissListener = dismissListener;
    }

    public static HgramScrimInlineMenuOverlay show(FrameLayout parent,
                                                  ChatScrimPopupContainerLayout container,
                                                  int x, int y,
                                                  ReactionsContainerLayout reactionsLayout,
                                                  ActionBarMenuSubItem[] focusItems,
                                                  Runnable closeAction,
                                                  DismissListener dismissListener) {
        final HgramScrimInlineMenuOverlay overlay = new HgramScrimInlineMenuOverlay(
                parent.getContext(), container, reactionsLayout, focusItems, closeAction, dismissListener);
        overlay.attach(parent, x, y);
        return overlay;
    }

    public ChatScrimPopupContainerLayout getContainer() {
        return container;
    }

    private void attach(FrameLayout parent, int x, int y) {
        if (container.getParent() instanceof ViewGroup) {
            ((ViewGroup) container.getParent()).removeView(container);
        }
        setClipChildren(false);
        setClipToPadding(false);
        final FrameLayout.LayoutParams menuParams =
                new FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        menuParams.leftMargin = x - parent.getPaddingLeft();
        menuParams.topMargin = y - parent.getPaddingTop();
        addView(container, menuParams);
        parent.addView(this, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));
        container.post(() -> {
            if (dismissed || getParent() == null) {
                return;
            }
            final int overlayTop = getTop();
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
            final int wantedTopMargin = y - overlayTop;
            if (lp.topMargin != wantedTopMargin) {
                lp.topMargin = wantedTopMargin;
                container.setLayoutParams(lp);
                parent.invalidate();
            }
        });
        container.setAlpha(0f);
        container.setTranslationY(-AndroidUtilities.dp(8));
        container.animate().alpha(1f).translationY(0f).setDuration(180)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        if (reactionsLayout != null) {
            reactionsLayout.startEnterAnimation(true);
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (dismissed || getParent() == null || focusItems == null || focusItems.length == 0
                    || focusItems[0] == null) {
                return;
            }
            focusItems[0].requestFocus();
            focusItems[0].performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            focusItems[0].sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        }, 420);
        parent.invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Once the close animation starts, no child may receive another tap.
        if (dismissed) {
            return true;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (!isInsideMenu(container, ev.getX(), ev.getY())) {
                if (closeAction != null) {
                    closeAction.run();
                }
                return true;
            }
        }
        super.dispatchTouchEvent(ev);
        return true;
    }

    static boolean isInsideMenu(View menu, float x, float y) {
        // View.getMatrix() already contains translationX/Y; only subtract the layout origin.
        float localX = x - menu.getLeft();
        float localY = y - menu.getTop();
        final android.graphics.Matrix inverse = new android.graphics.Matrix();
        if (menu.getMatrix().invert(inverse)) {
            final float[] point = {localX, localY};
            inverse.mapPoints(point);
            localX = point[0];
            localY = point[1];
        }
        return localX >= 0 && localY >= 0 && localX < menu.getWidth() && localY < menu.getHeight();
    }

    public void dismiss(boolean animated) {
        if (dismissed) {
            return;
        }
        dismissed = true;
        if (animated && container != null) {
            container.animate().alpha(0f).translationY(-AndroidUtilities.dp(8))
                    .setDuration(160)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT)
                    .withEndAction(this::removeNow)
                    .start();
        } else {
            removeNow();
        }
    }

    private void removeNow() {
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        if (getParent() != null) {
            return;
        }
        if (dismissListener != null) {
            dismissListener.onDismissed(this);
        }
    }
}
