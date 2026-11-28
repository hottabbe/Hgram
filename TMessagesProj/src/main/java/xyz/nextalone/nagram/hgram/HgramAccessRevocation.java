package xyz.nextalone.nagram.hgram;

import android.view.View;

/** Synchronously blanks every surface that can keep a story visible during close animation. */
public final class HgramAccessRevocation {

    private HgramAccessRevocation() {
    }

    public static void hide(View windowView, View aspectRatioFrameLayout, View surfaceView, View textureView, View storiesViewPager) {
        hideOne(windowView);
        hideOne(aspectRatioFrameLayout);
        hideOne(surfaceView);
        hideOne(textureView);
        hideOne(storiesViewPager);
    }

    private static void hideOne(View view) {
        if (view == null) {
            return;
        }
        view.setAlpha(0f);
        view.setVisibility(View.INVISIBLE);
        view.invalidate();
    }
}
