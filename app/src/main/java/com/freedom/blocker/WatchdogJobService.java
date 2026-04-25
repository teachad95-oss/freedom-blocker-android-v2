package com.freedom.blocker;

import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

/**
 * Periodic watchdog job (minimum ~15 min on Android).
 * If a session is active/pending and FreedomForegroundService is not running,
 * it restarts the service immediately.
 */
public class WatchdogJobService extends JobService {

    private static final String TAG    = "FreedomWatchdog";
    public  static final int    JOB_ID = 4242;

    /** Schedule (or re-schedule) the periodic watchdog job. */
    public static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        // Cancel existing so we don't stack duplicates
        js.cancel(JOB_ID);

        JobInfo job = new JobInfo.Builder(
                JOB_ID, new ComponentName(ctx, WatchdogJobService.class))
                .setPeriodic(15 * 60 * 1_000L)          // every 15 min (OS minimum)
                .setPersisted(true)                       // survives reboots
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build();

        int result = js.schedule(job);
        Log.d(TAG, "Watchdog scheduled: " + (result == JobScheduler.RESULT_SUCCESS ? "OK" : "FAILED"));
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        SessionManager sm = SessionManager.getInstance(this);

        if (sm.isSessionPendingOrActive()) {
            if (!isForegroundServiceRunning()) {
                Log.w(TAG, "Service not running during active session — restarting");
                Intent svc = new Intent(this, FreedomForegroundService.class);
                startForegroundService(svc);
            }
        }

        jobFinished(params, false);
        return false; // work done synchronously
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if interrupted
    }

    @SuppressWarnings("deprecation")
    private boolean isForegroundServiceRunning() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> services =
                am.getRunningServices(Integer.MAX_VALUE);
        for (ActivityManager.RunningServiceInfo s : services) {
            if (FreedomForegroundService.class.getName().equals(s.service.getClassName())) {
                return s.foreground;
            }
        }
        return false;
    }
}
