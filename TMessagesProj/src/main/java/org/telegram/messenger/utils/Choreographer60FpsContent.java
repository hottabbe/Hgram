package org.telegram.messenger.utils;

import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;

import android.util.SparseArray;

import androidx.annotation.Nullable;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;

import me.vkryl.core.reference.ReferenceList;

/**
 * A thin wrapper around Android {@link Choreographer} that delivers animation
 * callbacks at a stable ~60 fps regardless of the display refresh rate.
 *
 * <p>Callbacks with the same fps share a single schedule — they always fire
 * on the same tick, minimising the number of screen invalidations.
 *
 * <p>Must be used on the main thread only.
 */
public final class Choreographer60FpsContent implements Choreographer.FrameCallback {

    // ── Target frame rate ─────────────────────────────────────────────────────

    /**
     * Highest animation rate the dispatcher may use, fps.
     *
     * <p>The rate is not a fixed constant: it follows the refresh rate the window actually runs at,
     * so a 120 Hz screen still gets 120 fps animations while a 60 Hz screen is not asked for
     * twice as many frames as it can display. Requesting more frames than the display can show
     * only adds work to the UI thread and makes scrolling look uneven.
     */
    private static final int  MAX_FPS          = 120;

    /** Rate used before the display rate is known, fps. */
    private static final int  DEFAULT_FPS      = 60;

    /** Current animation rate, fps. Must be touched on the main thread only. */
    private static int sTargetFps = DEFAULT_FPS;

    /**
     * Duration of one target frame in nanoseconds. Kept in sync with {@link #sTargetFps}.
     */
    private static long sFrameIntervalNs = 1_000_000_000L / DEFAULT_FPS;


    /**
     * Adjusts the animation rate to the refresh rate the display actually runs at.
     *
     * <p>Must be called on the main thread. Rates below 60 fps are ignored: the dispatcher never
     * renders faster than the display, so asking for more frames than the screen shows only
     * wastes UI thread time and makes scrolling look uneven.
     */
    public static void setTargetFps(float fps) {
        checkMainThread();
        int target = (int) Math.round(fps);
        target = Math.max(DEFAULT_FPS, Math.min(MAX_FPS, target));
        if (target == sTargetFps) {
            return;
        }
        sTargetFps = target;
        sFrameIntervalNs = 1_000_000_000L / target;
    }

    /** The animation rate currently used by the dispatcher, fps. */
    public static int getTargetFps() {
        return sTargetFps;
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static Choreographer60FpsContent sInstance;

    public static Choreographer60FpsContent getInstance() {
        checkMainThread();
        if (sInstance == null) {
            sInstance = new Choreographer60FpsContent();
        }
        return sInstance;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final Choreographer mChoreographer = Choreographer.getInstance();

    /**
     * Persistent callback groups keyed by fps.
     * All callbacks in the same group share one fixed schedule and always fire together,
     * so N animations at the same fps produce exactly one invalidate wave per period.
     */
    private final SparseArray<CallbackGroup> mGroups = new SparseArray<>();

    private final ReferenceList<Drawable> mDrawablesToInvalidate      = new ReferenceList<>();
    private final ReferenceList<Drawable> mDrawablesToInvalidate30fps = new ReferenceList<>();
    private final ReferenceList<View>     mViewsToInvalidate          = new ReferenceList<>();

    /** Nanoseconds accumulated since the last dispatched 60fps frame. */
    private long mAccumulatedNs;

    /** Timestamp of the previous VSYNC event; 0 means "not started yet". */
    private long mLastVsyncNs;

    /** Current tick in the repeating 60-tick cycle; also used for legacy 30fps drawables. */
    private int mCounter;

    // ── Public interface ──────────────────────────────────────────────────────

    /** Callback interface, mirrors {@link Choreographer.FrameCallback}. */
    public interface FrameCallback {
        /**
         * Called at the requested fps.
         *
         * @param frameTimeNanos frame start time in {@link System#nanoTime()} domain, ns
         */
        void doFrame(long frameTimeNanos);
    }

    public void postInvalidateDrawable(Drawable drawable) {
        checkMainThread();
        mDrawablesToInvalidate.add(drawable);
    }

    public void postInvalidateDrawable30fps(Drawable drawable) {
        checkMainThread();
        mDrawablesToInvalidate30fps.add(drawable);
    }

    public void postInvalidateView(View view) {
        checkMainThread();
        mViewsToInvalidate.add(view);
    }

    /**
     * Subscribes a persistent callback at ~60 fps.
     */
    public void addFrameCallback(FrameCallback callback) {
        addFrameCallback(callback, sTargetFps);
    }

    /**
     * Subscribes a persistent Runnable callback at ~60 fps.
     */
    public void addFrameCallback(Runnable callback) {
        addFrameCallback(callback, sTargetFps);
    }

    public void addFrameCallbackOnce(Runnable callback, int fps) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        fps = Math.max(1, Math.min(fps, sTargetFps));
        removeFrameCallbackOnce(callback); // remove from any existing group first
        CallbackGroup group = getOrCreateGroup(fps);
        if (group.runnableCallbacksOnce == null) {
            group.runnableCallbacksOnce = new ReferenceList<>();
        }
        group.runnableCallbacksOnce.add(callback);
    }


    /**
     * Subscribes a persistent Runnable callback at the given fps.
     *
     * @param callback the {@link Runnable} to run on each tick
     * @param fps      desired rate in frames per second; clamped to [1, the current display rate]
     */
    public void addFrameCallback(Runnable callback, int fps) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        fps = Math.max(1, Math.min(fps, sTargetFps));
        removeFrameCallback(callback); // remove from any existing group first
        getOrCreateGroup(fps).runnableCallbacks.add(callback);
    }

    /**
     * Subscribes a persistent callback at the given fps.
     *
     * <p>Callbacks sharing the same fps value share a single schedule and
     * are guaranteed to fire on the same tick — this minimises screen invalidations
     * when multiple animations run at the same rate.
     *
     * @param callback the {@link FrameCallback} to register
     * @param fps      desired rate in frames per second; clamped to [1, the current display rate]
     */
    public void addFrameCallback(FrameCallback callback, int fps) {
        checkMainThread();
        fps = Math.max(1, Math.min(fps, sTargetFps));
        removeFrameCallback(callback); // remove from any existing group first
        getOrCreateGroup(fps).callbacks.add(callback);
    }

    /**
     * Unsubscribes a persistent Runnable callback from whichever group it belongs to.
     * Empty groups are removed automatically.
     */
    public void removeFrameCallback(Runnable callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.runnableCallbacks.remove(callback)) {
                return;
            }
        }
    }

    public void removeFrameCallbackOnce(Runnable callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.runnableCallbacksOnce != null && group.runnableCallbacksOnce.remove(callback)) {
                return;
            }
        }
    }

    /**
     * Unsubscribes a persistent callback from whichever group it belongs to.
     * Empty groups are removed automatically.
     */
    public void removeFrameCallback(FrameCallback callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.callbacks.remove(callback)) {
                return;
            }
        }
    }

    // ── Private implementation ────────────────────────────────────────────────

    private Choreographer60FpsContent() {
        mChoreographer.postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // Nagram: dispatch on every vsync (display rate). Each fps group fires when its own due time is reached.
                dispatchFrame(frameTimeNanos);
        mChoreographer.postFrameCallback(this);
    }

    private void dispatchFrame(long frameTimeNanos) {
        // Tolerance: a vsync that is slightly early still counts as "due", so jitter never skips a tick.
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.nextDueNs == 0) {
                group.nextDueNs = frameTimeNanos;
            }
            final boolean fire = frameTimeNanos + group.intervalNs / 4 >= group.nextDueNs;
            if (fire) {
                do {
                    group.nextDueNs += group.intervalNs;
                } while (group.nextDueNs <= frameTimeNanos + group.intervalNs / 4);
                if (group.runnableCallbacksOnce != null) {
                    ReferenceList<Runnable> referenceList = group.runnableCallbacksOnce;
                    group.runnableCallbacksOnce = null;
                    for (Runnable runnable : referenceList) {
                        runnable.run();
                    }
                }

                for (FrameCallback cb : group.callbacks) {
                    cb.doFrame(frameTimeNanos);
                }
                for (Runnable runnable : group.runnableCallbacks) {
                    runnable.run();
                }
            }
        }

        // View / drawable invalidations.
        for (View view : mViewsToInvalidate) {
            view.invalidate();
        }
        for (Drawable drawable : mDrawablesToInvalidate) {
            drawable.invalidateSelf();
        }
        mViewsToInvalidate.clear();
        mDrawablesToInvalidate.clear();

        // Legacy 30fps drawables (time-based, so they keep 30 fps on any refresh rate).
        if (next30fpsDueNs == 0) {
            next30fpsDueNs = frameTimeNanos;
        }
        if (frameTimeNanos + sFrameIntervalNs / 2 >= next30fpsDueNs) {
            while (next30fpsDueNs <= frameTimeNanos + sFrameIntervalNs / 2) {
                next30fpsDueNs += 33_333_333L;
            }
            for (Drawable drawable : mDrawablesToInvalidate30fps) {
                drawable.invalidateSelf();
            }
            mDrawablesToInvalidate30fps.clear();
        }

        mCounter++;
        if (mCounter == sTargetFps) {
            mCounter = 0;
        }
    }

    /** Nagram: next due time for 30 fps drawables, ns (0 = not started). */
    private long next30fpsDueNs;

    private CallbackGroup getOrCreateGroup(int fps) {
        fps = Math.max(1, Math.min(fps, sTargetFps));
        CallbackGroup group = mGroups.get(fps);
        if (group == null) {
            group = new CallbackGroup(1_000_000_000L / fps);
            mGroups.put(fps, group);
        }
        return group;
    }

    private static long[] buildFrameMasks() {
        long[] masks = new long[MAX_FPS];
        for (int index = 0; index < masks.length; index++) {
            masks[index] = buildFrameMask(index + 1);
        }
        return masks;
    }

    /**
     * Keeps the nearest regular divisor-of-60 schedule and adds/removes leap frames.
     * Ties select the lower base fps. Schedules depend only on fps, never on subscribers.
     */
    private static long buildFrameMask(int fps) {
        int baseFps = 1;
        for (int candidate = 2; candidate <= MAX_FPS; candidate++) {
            if (MAX_FPS % candidate == 0
                    && Math.abs(candidate - fps) < Math.abs(baseFps - fps)) {
                baseFps = candidate;
            }
        }

        int stride = MAX_FPS / baseFps;
        long mask = 0;
        for (int tick = 0; tick < MAX_FPS; tick += stride) {
            mask |= 1L << tick;
        }

        int correction = fps - baseFps;
        int leapFrames = Math.abs(correction);
        for (int i = 0; i < leapFrames; i++) {
            // Centre each correction in its part of the cycle.
            int targetTick = (2 * i + 1) * MAX_FPS / (2 * leapFrames);
            for (int distance = 0; distance < MAX_FPS; distance++) {
                boolean selected = false;
                for (int direction = 0; direction < 2; direction++) {
                    int tick = (targetTick + (direction == 0 ? distance : -distance)
                            + MAX_FPS) % MAX_FPS;
                    long bit = 1L << tick;
                    if (correction > 0) {
                        // Even-stride grids share odd leap slots, including 30 and 15 fps.
                        if ((mask & bit) != 0 || (stride % 2 == 0 && tick % 2 == 0)) {
                            continue;
                        }
                        mask |= bit;
                    } else {
                        if ((mask & bit) == 0) {
                            continue;
                        }
                        mask &= ~bit;
                    }
                    selected = true;
                    break;
                }
                if (selected) {
                    break;
                }
            }
        }
        return mask;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** Fixed frame schedule shared by all callbacks at the same fps. */
    private static final class CallbackGroup {
        /** Nagram: time between two fires of this group, ns. */
        final long intervalNs;
        /** Nagram: next due time of this group in the vsync timeline, ns (0 = not started). */
        long nextDueNs;

        final ReferenceList<FrameCallback> callbacks = new ReferenceList<>();
        final ReferenceList<Runnable> runnableCallbacks = new ReferenceList<>();
        @Nullable
        ReferenceList<Runnable> runnableCallbacksOnce;

        CallbackGroup(long intervalNs) {
            this.intervalNs = intervalNs;
        }
    }

    private static void checkMainThread() {
        if (BuildVars.DEBUG_PRIVATE_VERSION || BuildVars.DEBUG_VERSION) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("Choreographer60FpsContent must be used on the main thread");
            }
        }
    }
}