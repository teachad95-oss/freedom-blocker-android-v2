package com.freedom.blocker;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists and queries the current blocking session.
 * A "session" is defined by a start epoch (ms) and end epoch (ms).
 * Overnight sessions are handled by the caller adding 24 h to the end epoch.
 */
public class SessionManager {

    private static final String PREFS_NAME  = "freedom_session";
    private static final String KEY_START   = "session_start_ms";
    private static final String KEY_END     = "session_end_ms";
    private static final String KEY_ACTIVE  = "session_active";

    private final SharedPreferences prefs;
    private static volatile SessionManager instance;

    private SessionManager(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static SessionManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (SessionManager.class) {
                if (instance == null) instance = new SessionManager(ctx);
            }
        }
        return instance;
    }

    /** Persist a new session. endEpochMs must already account for overnight. */
    public void startSession(long startEpochMs, long endEpochMs) {
        prefs.edit()
             .putLong(KEY_START,  startEpochMs)
             .putLong(KEY_END,    endEpochMs)
             .putBoolean(KEY_ACTIVE, true)
             .apply();
    }

    /** True when now is inside [start, end) AND the active flag is set. */
    public boolean isSessionActive() {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false;
        long now = System.currentTimeMillis();
        return now >= prefs.getLong(KEY_START, 0) && now < prefs.getLong(KEY_END, 0);
    }

    /** True when a session is stored but hasn't started yet. */
    public boolean isSessionScheduled() {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false;
        return System.currentTimeMillis() < prefs.getLong(KEY_START, 0);
    }

    /** True if the session is either pending or currently active (not yet expired). */
    public boolean isSessionPendingOrActive() {
        return prefs.getBoolean(KEY_ACTIVE, false)
                && System.currentTimeMillis() < prefs.getLong(KEY_END, 0);
    }

    public long getStartEpochMs() { return prefs.getLong(KEY_START, 0); }
    public long getEndEpochMs()   { return prefs.getLong(KEY_END,   0); }

    /** Milliseconds left in the active session (0 if not active). */
    public long getRemainingMs() {
        if (!isSessionActive()) return 0;
        return prefs.getLong(KEY_END, 0) - System.currentTimeMillis();
    }

    /** Call when the session naturally expires. */
    public void clearSession() {
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply();
    }
}
