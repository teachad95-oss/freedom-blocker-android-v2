package com.freedom.blocker;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Device Administrator receiver.
 *
 * - onDisableRequested: if a session is active, return a warning string that Android
 *   displays in the "Deactivate device admin?" dialog. The user CAN still proceed
 *   (Android cannot fully block them), but the warning makes it clear this is unsafe.
 *
 * - onDisabled: if somehow disabled during an active session, we re-prompt to reinstate
 *   admin rights by launching MainActivity, which will show the grant dialog again.
 */
public class FreedomDeviceAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "FreedomDeviceAdmin";

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        SessionManager sm = SessionManager.getInstance(context);
        if (sm.isSessionActive()) {
            Log.w(TAG, "Admin disable requested during active session!");
            return "⚠ Freedom Blocker has an active blocking session. "
                 + "Removing admin rights will not stop the session, "
                 + "but the app will no longer be protected from uninstall. "
                 + "Your session ends at " + formatEpoch(sm.getEndEpochMs()) + ".";
        }
        return null; // allow silently
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.w(TAG, "Device admin disabled");
        SessionManager sm = SessionManager.getInstance(context);
        if (sm.isSessionPendingOrActive()) {
            // Re-prompt the user to re-grant device admin immediately
            Toast.makeText(context,
                    "Session active! Please re-enable Device Admin to protect Freedom Blocker.",
                    Toast.LENGTH_LONG).show();
            Intent reEnable = new Intent(context, MainActivity.class);
            reEnable.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            reEnable.putExtra(MainActivity.EXTRA_REQUEST_DEVICE_ADMIN, true);
            context.startActivity(reEnable);
        }
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.d(TAG, "Device admin enabled");
    }

    private static String formatEpoch(long epochMs) {
        java.text.SimpleDateFormat sdf =
            new java.text.SimpleDateFormat("HH:mm, dd MMM", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(epochMs));
    }
}
