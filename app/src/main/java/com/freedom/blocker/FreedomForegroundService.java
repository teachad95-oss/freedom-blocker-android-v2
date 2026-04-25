package com.freedom.blocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps the session alive.
 *
 * Key behaviours:
 *  - Starts with START_STICKY so Android relaunches it if killed.
 *  - Posts a persistent (non-dismissible) notification for the session duration.
 *  - Polls every 2 s: if the session has expired it clears the session and stops itself.
 *  - stopWithTask="false" in Manifest means it survives the user swiping the app away.
 */
public class FreedomForegroundService extends Service {

    private static final String TAG           = "FreedomService";
    public  static final String CHANNEL_ID    = "freedom_channel";
    public  static final int    NOTIF_ID      = 1001;
    private static final long   CHECK_INTERVAL = 2_000L; // 2 seconds

    private Handler        handler;
    private SessionManager sessionManager;

    @Override
    public void onCreate() {
        super.onCreate();
        sessionManager = SessionManager.getInstance(this);
        handler        = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        Log.d(TAG, "Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        handler.removeCallbacks(checkRunnable);
        handler.post(checkRunnable);
        Log.d(TAG, "Started (startId=" + startId + ")");
        return START_STICKY; // <-- auto-restart after process kill
    }

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (sessionManager.isSessionPendingOrActive()) {
                // Refresh notification countdown
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIF_ID, buildNotification());
                handler.postDelayed(this, CHECK_INTERVAL);
            } else {
                // Session has naturally expired
                Log.d(TAG, "Session expired — stopping foreground service");
                sessionManager.clearSession();
                stopForeground(true);
                stopSelf();
            }
        }
    };

    private Notification buildNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body;
        if (sessionManager.isSessionActive()) {
            body = "Blocking active — " + formatDuration(sessionManager.getRemainingMs()) + " remaining";
        } else if (sessionManager.isSessionScheduled()) {
            long until = sessionManager.getStartEpochMs() - System.currentTimeMillis();
            body = "Session starts in " + formatDuration(until);
        } else {
            body = "Monitoring…";
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🔴 Freedom Blocker Active")
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)           // cannot be swiped away
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Freedom Blocker", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Active blocking session notification");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        long s = (ms % 60_000)    / 1_000;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(checkRunnable);
        Log.d(TAG, "Destroyed — START_STICKY will relaunch");

        // Belt-and-suspenders: broadcast so WatchdogReceiver can restart us immediately
        if (sessionManager.isSessionPendingOrActive()) {
            sendBroadcast(new Intent("com.freedom.blocker.RESTART_SERVICE"));
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
