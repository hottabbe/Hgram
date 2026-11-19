package xyz.nextalone.nagram.hgram;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;

import tw.nekomimi.nekogram.settings.HgramSettingsActivity;

/**
 * Anti-peek hold gesture: the phone must stay screen-down within the configured
 * [minHold, maxHold] window, then the return to screen-up toggles the mask. A short bump,
 * a too-long hold or a dead-zone wobble never toggles twice. The gesture is only processed
 * while a chat list or a chat fragment owns the foreground scope.
 */
public final class HgramAntiPeek {

    static final int ORIENTATION_UNDEFINED = 0;
    static final int ORIENTATION_SCREEN_DOWN = 1;
    static final int ORIENTATION_SCREEN_UP = 2;

    private static final float LOW_PASS_ALPHA = 0.35f;
    private static final float GRAVITY_MIN = 5.0f;
    private static final float GRAVITY_MAX = 13.5f;
    static final long MIN_GESTURE_TOGGLE_INTERVAL_MS = 500L;
    private static final float UNMASK_HYSTERESIS = 0.15f;
    private static final Object SCOPE_LOCK = new Object();
    private static final ArrayList<Object> scopeFragments = new ArrayList<>();
    private static final ArrayList<Object> closingSheets = new ArrayList<>();
    private static volatile boolean scopeAllowed;
    private static final OverlayGuard overlayGuard = new OverlayGuard();
    private static final HoldState holdState = new HoldState();

    // z / |g| thresholds: cos(60), cos(45), cos(30).
    private static final float DOWN_THRESHOLD_LOW = 0.5f;
    private static final float DOWN_THRESHOLD_MEDIUM = 0.707f;
    private static final float DOWN_THRESHOLD_HIGH = 0.866f;

    private static volatile boolean active;
    private static volatile boolean activatedByGesture;
    private static Runnable temporaryRestoreRunnable;
    private static boolean temporaryRevealActive;
    private static boolean temporaryGestureOrigin;
    private static boolean temporaryRevealWasActive;

    private static SensorManager sensorManager;
    private static Sensor accelerometer;
    private static volatile boolean listenerRegistered;

    private static boolean filterInitialized;
    private static float filteredX;
    private static float filteredY;
    private static float filteredZ;
    private static int lastLoggedState = -1;
    private static boolean lastLoggedMask;

    private static final SensorEventListener listener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length < 3
                    || !listenerRegistered || !HgramConfig.isAntiPeekGestureEnabled()
                    || !syncForegroundScope()) {
                return;
            }

            final float x = event.values[0];
            final float y = event.values[1];
            final float z = event.values[2];
            if (!filterInitialized) {
                filteredX = x;
                filteredY = y;
                filteredZ = z;
                filterInitialized = true;
            } else {
                filteredX += (x - filteredX) * LOW_PASS_ALPHA;
                filteredY += (y - filteredY) * LOW_PASS_ALPHA;
                filteredZ += (z - filteredZ) * LOW_PASS_ALPHA;
            }

            final float magnitude = (float) Math.sqrt(
                    filteredX * filteredX + filteredY * filteredY + filteredZ * filteredZ);
            final int sensitivity = HgramConfig.getAntiPeekSensitivity();
            final float ratio = magnitude > 0f ? filteredZ / magnitude : 0f;
            final int sample = classifySample(filteredZ, magnitude, sensitivity);
            final long now = SystemClock.elapsedRealtime();

            final boolean toggleHappened = holdState.onSample(
                    sample, now,
                    HgramConfig.getAntiPeekHoldMinMs(),
                    HgramConfig.getAntiPeekHoldMaxMs());
            if (toggleHappened) {
                final boolean enabling = !active;
                activatedByGesture = enabling;
                toggle();
                if (enabling) {
                    vibrateOnActivate();
                }
            }
            logIfChanged(sensitivity, ratio, magnitude);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private HgramAntiPeek() {
    }

    private static void logIfChanged(int sensitivity, float ratio, float magnitude) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        if (holdState.orientation != lastLoggedState || active != lastLoggedMask) {
            lastLoggedState = holdState.orientation;
            lastLoggedMask = active;
            logState(sensitivity, ratio, magnitude);
        }
    }

    private static void logState(int sensitivity, float ratio, float magnitude) {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        final String level = sensitivity == HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH
                ? "high" : sensitivity == HgramConfig.ANTI_PEEK_SENSITIVITY_LOW
                ? "low" : "medium";
        final int tilt = sensitivity == HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH
                ? 30 : sensitivity == HgramConfig.ANTI_PEEK_SENSITIVITY_LOW ? 60 : 45;
        FileLog.d("HgramAntiPeek tilt=" + tilt + " level=" + level
                + " ratio=" + ratio + " g=" + magnitude + " mask=" + (active ? "on" : "off"));
    }

    static boolean isGestureScopeClass(String className) {
        return "org.telegram.ui.DialogsActivity".equals(className)
                || "org.telegram.ui.ChatActivity".equals(className)
                || "org.telegram.ui.TopicsFragment".equals(className);
    }

    public static void onScopeResume(Object token, String className) {
        if (token == null || !isGestureScopeClass(className)) {
            return;
        }
        synchronized (SCOPE_LOCK) {
            for (Object existing : scopeFragments) {
                if (existing == token) {
                    return;
                }
            }
            scopeFragments.add(token);
        }
        // A new fragment took over the foreground: a hold started on the old screen must not
        // survive the navigation. Keep the real last-toggle time so the 500 ms cooldown remains.
        resetOrientationState();
        updateScopeAllowed();
    }

    public static void onScopePause(Object token) {
        if (token == null) {
            return;
        }
        boolean removed = false;
        synchronized (SCOPE_LOCK) {
            for (int i = 0; i < scopeFragments.size(); i++) {
                if (scopeFragments.get(i) == token) {
                    scopeFragments.remove(i);
                    removed = true;
                    break;
                }
            }
        }
        if (removed) {
            resetOrientationState();
        }
        updateScopeAllowed();
    }

    /**
     * Owner-scoped PhotoViewer blocker. A viewer may only release its own block, so destroying
     * a PiP viewer cannot unblock a different still-open viewer.
     */
    public static void setOverlayBlocking(Object owner, boolean blocking) {
        final boolean changed = blocking
                ? overlayGuard.acquire(owner)
                : overlayGuard.release(owner);
        if (changed) {
            updateScopeAllowed();
        }
    }

    /**
     * Marks a sheet as closing. The fragment stack drops the sheet before its close animation
     * finishes, so the resolver would otherwise fall through to the chat underneath for 200-250 ms.
     */
    public static void onSheetDismissStarted(Object token) {
        if (token == null) {
            return;
        }
        synchronized (SCOPE_LOCK) {
            for (Object existing : closingSheets) {
                if (existing == token) {
                    return;
                }
            }
            closingSheets.add(token);
        }
        updateScopeAllowed();
    }

    /**
     * Clears the closing guard after the sheet window is actually dismissed, cancelled or
     * reopened. Idempotent and safe for an unknown token.
     */
    public static void onSheetClosed(Object token) {
        if (token == null) {
            return;
        }
        boolean removed = false;
        synchronized (SCOPE_LOCK) {
            for (int i = 0; i < closingSheets.size(); i++) {
                if (closingSheets.get(i) == token) {
                    closingSheets.remove(i);
                    removed = true;
                    break;
                }
            }
        }
        if (removed) {
            updateScopeAllowed();
        }
    }

    static boolean hasClosingSheet() {
        synchronized (SCOPE_LOCK) {
            return !closingSheets.isEmpty();
        }
    }

    private static void clearScopeTokens() {
        synchronized (SCOPE_LOCK) {
            scopeFragments.clear();
            closingSheets.clear();
        }
        updateScopeAllowed();
    }

    private static void updateScopeAllowed() {
        syncForegroundScope();
    }

    /**
     * Recomputes eligibility from the actual foreground fragment. Tokens are only lifecycle
     * hints; a resumed chat token under a tablet settings layer must not permit the gesture.
     */
    private static boolean syncForegroundScope() {
        final boolean allowed = isGestureScopeAllowedNow();
        if (scopeAllowed != allowed) {
            scopeAllowed = allowed;
            holdState.invalidatePending();
            filterInitialized = false;
        }
        return allowed;
    }

    private static boolean isGestureScopeAllowedNow() {
        try {
            final BaseFragment foreground = resolveForegroundFragment(LaunchActivity.instance);
            return isGestureScopeAllowed(overlayGuard.isBlocked(), hasClosingSheet(),
                    foreground != null ? foreground.getClass().getName() : null);
        } catch (NullPointerException | IndexOutOfBoundsException | IllegalStateException ignored) {
            // The activity/layout stack can be tearing down while the sensor callback is in flight.
            return false;
        }
    }

    static boolean isGestureScopeAllowed(boolean overlayBlocked, boolean sheetClosing,
                                         String foregroundClass) {
        return !overlayBlocked && !sheetClosing && isGestureScopeClass(foregroundClass);
    }

    static BaseFragment resolveForegroundFragment(LaunchActivity activity) {
        if (activity == null) {
            return null;
        }
        final INavigationLayout mainLayout = activity.actionBarLayout;
        final INavigationLayout activeLayout = activity.getActionBarLayout();
        // getActionBarLayout() returns the active sheet when one is open. The sheet is above
        // the tablet base layers, so it must win over a right-pane ChatActivity. If the sheet
        // exists but is not measured yet, fail closed instead of falling through to the chat.
        final boolean sheetOpen = activeLayout != mainLayout;
        final BaseFragment sheetTop = sheetOpen ? visibleTop(activeLayout) : null;
        final BaseFragment layersTop = visibleTop(activity.getLayersActionBarLayout());
        final BaseFragment rightTop = visibleTop(activity.getRightActionBarLayout());
        final BaseFragment mainTop = visibleTop(mainLayout);
        final BaseFragment foreground = chooseForeground(
                sheetOpen, sheetTop,
                layersTop != null, layersTop,
                rightTop != null, rightTop,
                mainTop);
        return unwrapTabs(foreground);
    }

    private static BaseFragment visibleTop(INavigationLayout layout) {
        if (layout == null) {
            return null;
        }
        final View view = layout.getView();
        if (view == null || view.getVisibility() != View.VISIBLE
                || !view.isShown() || !view.isEnabled() || view.getAlpha() <= 0f
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        return layout.getLastFragment();
    }

    private static BaseFragment unwrapTabs(BaseFragment fragment) {
        if (fragment instanceof MainTabsActivity) {
            return ((MainTabsActivity) fragment).getCurrentVisibleFragment();
        }
        return fragment;
    }

    static <T> T chooseForeground(boolean sheetVisible, T sheetTop,
                                  boolean layersVisible, T layersTop,
                                  boolean rightVisible, T rightTop, T mainTop) {
        if (sheetVisible) {
            // An active sheet always owns the foreground. A null/unmeasured top must block
            // rather than fall through to a right-pane chat underneath the sheet.
            return sheetTop;
        }
        if (layersVisible && layersTop != null) {
            return layersTop;
        }
        if (rightVisible && rightTop != null) {
            return rightTop;
        }
        return mainTop;
    }

    /**
     * Pure hold FSM. Package-private for unit tests; no Android dependencies.
     */
    static final class HoldState {
        int orientation = ORIENTATION_UNDEFINED;
        long downSince = -1;
        boolean armed;
        long lastToggleAt = -MIN_GESTURE_TOGGLE_INTERVAL_MS;

        synchronized boolean onSample(int sample, long now, int minHoldMs, int maxHoldMs) {
            if (sample == ORIENTATION_SCREEN_DOWN) {
                if (orientation == ORIENTATION_SCREEN_DOWN) {
                    return false;
                }
                if (downSince < 0 && armed) {
                    downSince = now;
                    armed = false;
                }
                orientation = ORIENTATION_SCREEN_DOWN;
                return false;
            }
            if (sample == ORIENTATION_SCREEN_UP) {
                boolean toggle = false;
                if (downSince >= 0) {
                    final long held = now - downSince;
                    toggle = held >= minHoldMs && held <= maxHoldMs
                            && now - lastToggleAt >= MIN_GESTURE_TOGGLE_INTERVAL_MS;
                }
                if (toggle) {
                    lastToggleAt = now;
                }
                orientation = ORIENTATION_SCREEN_UP;
                downSince = -1;
                armed = true;
                return toggle;
            }
            // Dead zone: keep an active hold alive so the real return-up edge can fire,
            // but never arm a fresh cycle. Only an explicit UP may arm after reset/start.
            orientation = ORIENTATION_UNDEFINED;
            return false;
        }

        synchronized void invalidatePending() {
            orientation = ORIENTATION_UNDEFINED;
            downSince = -1;
            armed = false;
        }

        synchronized void reset() {
            // Do not clear lastToggleAt: a scope/lifecycle reset must keep the real 500 ms
            // cooldown so a quick second toggle cannot slip through.
            invalidatePending();
        }
    }

    static final class OverlayGuard {
        private final ArrayList<Object> owners = new ArrayList<>();

        synchronized boolean acquire(Object newOwner) {
            if (newOwner == null) {
                return false;
            }
            for (int i = 0; i < owners.size(); i++) {
                if (owners.get(i) == newOwner) {
                    return false;
                }
            }
            owners.add(newOwner);
            return true;
        }

        synchronized boolean release(Object expectedOwner) {
            if (expectedOwner == null) {
                return false;
            }
            for (int i = 0; i < owners.size(); i++) {
                if (owners.get(i) == expectedOwner) {
                    owners.remove(i);
                    return true;
                }
            }
            return false;
        }

        synchronized boolean isBlocked() {
            return !owners.isEmpty();
        }

        synchronized Object getOwner() {
            return owners.isEmpty() ? null : owners.get(0);
        }
    }

    /**
     * Classifies one filtered sample. Package-private for unit tests; no allocations.
     */
    static int classifySample(float z, float magnitude, int sensitivity) {
        if (magnitude < GRAVITY_MIN || magnitude > GRAVITY_MAX) {
            return ORIENTATION_UNDEFINED;
        }
        final float ratio = z / magnitude;
        if (ratio <= -downThreshold(sensitivity)) {
            return ORIENTATION_SCREEN_DOWN;
        }
        if (ratio >= -upThreshold(sensitivity)) {
            return ORIENTATION_SCREEN_UP;
        }
        return ORIENTATION_UNDEFINED;
    }

    static float downThreshold(int sensitivity) {
        if (sensitivity == HgramConfig.ANTI_PEEK_SENSITIVITY_LOW) {
            return DOWN_THRESHOLD_LOW;
        }
        if (sensitivity == HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH) {
            return DOWN_THRESHOLD_HIGH;
        }
        return DOWN_THRESHOLD_MEDIUM;
    }

    static float upThreshold(int sensitivity) {
        return Math.max(0.05f, downThreshold(sensitivity) - UNMASK_HYSTERESIS);
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        setActive(value, false);
    }

    public static void setActive(boolean value, boolean silent) {
        if (!value && temporaryRevealActive) {
            // Turning the mask (or the gesture) off must also cancel a pending show5s restore.
            cancelTemporaryReveal(false);
        }
        if (active == value) {
            return;
        }
        if (value && temporaryRevealActive) {
            // Gesture/menu turned the mask on during a temporary reveal: cancel the timer.
            if (temporaryRestoreRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(temporaryRestoreRunnable);
                temporaryRestoreRunnable = null;
            }
            temporaryRevealActive = false;
        }
        active = value;
        if (!value) {
            // Remember that the mask is no longer gesture-owned for show5s origin tracking;
            // screen-up samples never change the mask anyway.
            activatedByGesture = false;
        }
        notifyUiChanged();
        if (value && !silent) {
            // The setting is called "notification on activation"; deactivation is silent.
            showToast(R.string.HgramAntiPeekOn);
        }
    }

    public static void toggle() {
        setActive(!active);
    }

    public static boolean isTemporaryRevealActive() {
        return temporaryRevealActive;
    }

    static void runTemporaryRestoreNowForTest() {
        final Runnable runnable = temporaryRestoreRunnable;
        temporaryRestoreRunnable = null;
        if (runnable != null) {
            runnable.run();
        }
    }

    public static boolean isActivatedByGesture() {
        return activatedByGesture;
    }

    /**
     * Temporarily removes the mask and restores it after the delay. Repeated calls restart the
     * single timer without losing the original gesture/manual origin.
     */
    public static void showTemporarily(long delayMs, Runnable onRestored) {
        if (active) {
            temporaryGestureOrigin = activatedByGesture;
            temporaryRevealWasActive = true;
        } else if (!temporaryRevealActive) {
            return;
        }
        if (temporaryRestoreRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(temporaryRestoreRunnable);
        }
        setActive(false, true);
        temporaryRevealActive = true;
        temporaryRestoreRunnable = () -> {
            temporaryRestoreRunnable = null;
            temporaryRevealActive = false;
            final boolean allowed = temporaryRevealWasActive
                    && (!temporaryGestureOrigin || HgramConfig.isAntiPeekGestureEnabled());
            temporaryRevealWasActive = false;
            if (allowed) {
                setActive(true, true);
                activatedByGesture = temporaryGestureOrigin;
            }
            if (allowed && onRestored != null) {
                onRestored.run();
            }
        };
        AndroidUtilities.runOnUIThread(temporaryRestoreRunnable, delayMs);
    }

    public static void cancelTemporaryReveal(boolean restoreMask) {
        final boolean wasTemporary = temporaryRevealActive;
        final boolean allowed = temporaryRevealWasActive
                && (!temporaryGestureOrigin || HgramConfig.isAntiPeekGestureEnabled());
        if (temporaryRestoreRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(temporaryRestoreRunnable);
            temporaryRestoreRunnable = null;
        }
        temporaryRevealActive = false;
        temporaryRevealWasActive = false;
        if (restoreMask && wasTemporary && allowed) {
            setActive(true, true);
            activatedByGesture = temporaryGestureOrigin;
        }
    }

    private static void vibrateOnActivate() {
        AndroidUtilities.runOnUIThread(HgramAntiPeek::vibrateNow);
    }

    private static void vibrateNow() {
        if (!HgramConfig.isAntiPeekHapticEnabled()) {
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        HgramHaptics.play(context, HgramHapticProfile.PLACE_ANTIPEEK);
    }

    public static void start(Context context) {
        if (!HgramConfig.isAntiPeekGestureEnabled() || sensorManager != null) {
            return;
        }
        try {
            final Context appContext = context != null
                    ? context.getApplicationContext()
                    : ApplicationLoader.applicationContext;
            if (appContext == null) {
                return;
            }
            final SensorManager manager =
                    (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
            final Sensor sensor = manager != null
                    ? manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    : null;
            if (manager == null || sensor == null) {
                resetSensor();
                return;
            }
            sensorManager = manager;
            accelerometer = sensor;
            resetOrientationState();
            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)) {
                resetSensor();
                return;
            }
            listenerRegistered = true;
        } catch (Throwable ignored) {
            resetSensor();
        }
    }

    public static void stop() {
        cancelTemporaryReveal(false);
        clearScopeTokens();
        listenerRegistered = false;
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(listener);
            }
        } catch (Throwable ignored) {
            // The sensor may already be gone when the process is winding down.
        } finally {
            resetSensor();
        }
    }

    public static void applySettingChange() {
        if (HgramConfig.isAntiPeekGestureEnabled()) {
            final ForegroundDetector detector = ForegroundDetector.getInstance();
            if (detector != null && detector.isForeground()) {
                start(ApplicationLoader.applicationContext);
            } else {
                stop();
            }
        } else {
            stop();
            setActive(false);
        }
    }

    private static void resetSensor() {
        sensorManager = null;
        accelerometer = null;
        listenerRegistered = false;
        resetOrientationState();
    }

    private static void resetOrientationState() {
        holdState.reset();
        lastLoggedState = -1;
        lastLoggedMask = false;
        filterInitialized = false;
        filteredX = 0f;
        filteredY = 0f;
        filteredZ = 0f;
    }

    private static void notifyUiChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            final LaunchActivity activity = LaunchActivity.instance;
            if (activity != null) {
                if (activity.frameLayout != null) {
                    activity.frameLayout.invalidate();
                }
                if (activity.actionBarLayout != null && activity.actionBarLayout.getView() != null) {
                    activity.actionBarLayout.getView().invalidate();
                }
            }

            final int mask = MessagesController.UPDATE_MASK_AVATAR
                    | MessagesController.UPDATE_MASK_NAME
                    | MessagesController.UPDATE_MASK_STATUS
                    | MessagesController.UPDATE_MASK_PHONE;
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    NotificationCenter.getInstance(account)
                            .postNotificationName(NotificationCenter.updateInterfaces, mask);
                }
            }
            NotificationCenter.getGlobalInstance()
                    .postNotificationName(NotificationCenter.updateInterfaces, mask);
            NotificationCenter.getGlobalInstance()
                    .postNotificationName(NotificationCenter.dialogsNeedReload);

            final BaseFragment top = resolveForegroundFragment(activity);
            if (top instanceof DialogsActivity) {
                ((DialogsActivity) top).hgramForceRefresh();
            } else if (top instanceof HgramSettingsActivity) {
                ((HgramSettingsActivity) top).refreshAntiPeekRow();
            }
            if (activity != null && activity.frameLayout != null) {
                AndroidUtilities.runOnUIThread(activity.frameLayout::invalidate, 60);
            }
        });
    }

    private static void showToast(int resId) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!HgramConfig.isAntiPeekShowToast()) {
                return;
            }
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            try {
                Toast.makeText(context, LocaleController.getString(resId), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // Toast is cosmetic; do not let a missing native/locale resource break state changes.
            }
        });
    }
}
