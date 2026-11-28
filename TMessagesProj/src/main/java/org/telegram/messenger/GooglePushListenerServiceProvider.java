package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

public class GooglePushListenerServiceProvider implements PushListenerController.IPushListenerServiceProvider {

    private static final int MAX_TOKEN_RETRIES = 3;

    private Boolean hasServices;

    public GooglePushListenerServiceProvider() {}

    @Override
    public String getLogTitle() {
        return "Google Play Services";
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_FIREBASE;
    }

    @Override
    public void onRequestPushToken() {
        String currentPushString = SharedConfig.pushString;
        if (!TextUtils.isEmpty(currentPushString)) {
            if (BuildVars.DEBUG_PRIVATE_VERSION && BuildVars.LOGS_ENABLED) {
                FileLog.d("FCM regId = " + currentPushString);
            }
        } else {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("FCM Registration not found.");
            }
        }
        Utilities.globalQueue.postRunnable(() -> requestToken(0));
    }

    private void requestToken(int attempt) {
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                FirebaseApp.initializeApp(ApplicationLoader.applicationContext);
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
                if (!task.isSuccessful() || TextUtils.isEmpty(task.getResult())) {
                    Exception error = task.getException();
                    if (error != null) {
                        FileLog.e("Failed to get FCM registration token", error);
                                }
                                SharedConfig.pushStringStatus = "__FIREBASE_FAILED__";
                    // A transient token fetch failure must not unregister a previously working token.
                    if (attempt < MAX_TOKEN_RETRIES) {
                        long delay = 2_000L << attempt;
                        Utilities.globalQueue.postRunnable(() -> requestToken(attempt + 1), delay);
                    }
                                return;
                            }
                            String token = task.getResult();
                SharedConfig.pushStringStatus = "";
                                PushListenerController.sendRegistrationToServer(getPushType(), token);
                        });
            } catch (Throwable e) {
                FileLog.e(e);
            if (attempt < MAX_TOKEN_RETRIES) {
                Utilities.globalQueue.postRunnable(() -> requestToken(attempt + 1), 2_000L << attempt);
            }
            }
    }

    @Override
    public boolean hasServices() {
        if (hasServices == null) {
            try {
                int resultCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ApplicationLoader.applicationContext);
                hasServices = resultCode == ConnectionResult.SUCCESS;
            } catch (Exception e) {
                FileLog.e(e);
                hasServices = false;
            }
        }
        return hasServices;
    }
}
