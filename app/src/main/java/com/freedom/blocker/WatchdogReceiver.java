package com.freedom.blocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives BOOT_COMPLETED, MY_PACKAGE_REPLACED, and our internal
 * RESTART_SERVICE broadcast (sent by the foreground service onDestroy).
 *
 * If a session is pending/active, immediately restarts FreedomForegroundService
 * and reschedules the WatchdogJobService.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    private static final String TAG = "FreedomWatchdogReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : String.valueOf(intent.getAction());
        Log.d(TAG, "onReceive: " + action);

        SessionManager sm = SessionManager.getInstance(context);
        if (sm.isSessionPendingOrActive()) {
            Log.i(TAG, "Session is active — restarting FreedomForegroundService");
            Intent svc = new Intent(context, FreedomForegroundService.class);
            context.startForegroundService(svc);
            WatchdogJobService.schedule(context);
        }
    }
}
