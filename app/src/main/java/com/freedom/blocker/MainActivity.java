package com.freedom.blocker;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Main UI activity.
 *
 * Responsibilities:
 *  1. Permission setup (Accessibility, Device Admin, Overlay)
 *  2. Keyword list management (add / delete — disabled during active session)
 *  3. Session scheduling (date+time pickers for Start and End, overnight supported)
 *  4. Live session countdown display
 *  5. Starting FreedomForegroundService + WatchdogJobService on session start
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_REQUEST_DEVICE_ADMIN = "request_device_admin";
    private static final int REQ_DEVICE_ADMIN = 101;
    private static final long UI_REFRESH_MS   = 1_000L;

    // Data
    private SessionManager sessionManager;
    private KeywordStore    keywordStore;

    // UI references
    private TextView      tvSessionStatus;
    private TextView      tvCountdown;
    private View          cardStatus;
    private Button        btnStartSession;
    private Button        btnPickStart;
    private Button        btnPickEnd;
    private View          layoutKeywordInput;
    private TextInputEditText etKeyword;
    private Button        btnAddKeyword;
    private RecyclerView  rvKeywords;
    private TextView      tvNoKeywords;
    private ImageView     ivLockIcon;

    // Permission dots
    private View   dotAccessibility;
    private View   dotDeviceAdmin;
    private View   dotOverlay;
    private Button btnGrantAccessibility;
    private Button btnGrantDeviceAdmin;
    private Button btnGrantOverlay;

    // Session pickers state
    private int startHour   = -1, startMin = -1;
    private int endHour     = -1, endMin   = -1;

    private KeywordAdapter adapter;
    private final Handler  uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sessionManager = SessionManager.getInstance(this);
        keywordStore   = KeywordStore.getInstance(this);

        bindViews();
        setupKeywordRecyclerView();
        setupClickListeners();

        // If launched from DeviceAdminReceiver to re-grant admin
        if (getIntent().getBooleanExtra(EXTRA_REQUEST_DEVICE_ADMIN, false)) {
            requestDeviceAdmin();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        uiHandler.post(countdownRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(countdownRunnable);
    }

    // ─── View Binding ───────────────────────────────────────────────────────────

    private void bindViews() {
        tvSessionStatus      = findViewById(R.id.tv_session_status);
        tvCountdown          = findViewById(R.id.tv_countdown);
        cardStatus           = findViewById(R.id.card_status);
        btnStartSession      = findViewById(R.id.btn_start_session);
        btnPickStart         = findViewById(R.id.btn_pick_start);
        btnPickEnd           = findViewById(R.id.btn_pick_end);
        layoutKeywordInput   = findViewById(R.id.layout_keyword_input);
        etKeyword            = findViewById(R.id.et_keyword);
        btnAddKeyword        = findViewById(R.id.btn_add_keyword);
        rvKeywords           = findViewById(R.id.rv_keywords);
        tvNoKeywords         = findViewById(R.id.tv_no_keywords);
        ivLockIcon           = findViewById(R.id.iv_lock_icon);

        dotAccessibility     = findViewById(R.id.dot_accessibility);
        dotDeviceAdmin       = findViewById(R.id.dot_device_admin);
        dotOverlay           = findViewById(R.id.dot_overlay);
        btnGrantAccessibility = findViewById(R.id.btn_grant_accessibility);
        btnGrantDeviceAdmin  = findViewById(R.id.btn_grant_device_admin);
        btnGrantOverlay      = findViewById(R.id.btn_grant_overlay);
    }

    // ─── RecyclerView ───────────────────────────────────────────────────────────

    private void setupKeywordRecyclerView() {
        List<String> keywords = keywordStore.getKeywords();
        adapter = new KeywordAdapter(keywords, this::deleteKeyword);
        rvKeywords.setLayoutManager(new LinearLayoutManager(this));
        rvKeywords.setNestedScrollingEnabled(false);
        rvKeywords.setAdapter(adapter);
        updateNoKeywordsLabel(keywords);
    }

    private void deleteKeyword(String keyword) {
        if (sessionManager.isSessionPendingOrActive()) {
            Toast.makeText(this, getString(R.string.session_locked_toast), Toast.LENGTH_SHORT).show();
            return;
        }
        keywordStore.removeKeyword(keyword);
        refreshKeywordList();
    }

    private void refreshKeywordList() {
        List<String> kws = keywordStore.getKeywords();
        adapter.updateList(kws);
        updateNoKeywordsLabel(kws);
    }

    private void updateNoKeywordsLabel(List<String> kws) {
        tvNoKeywords.setVisibility(kws.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ─── Click Listeners ────────────────────────────────────────────────────────

    private void setupClickListeners() {
        btnAddKeyword.setOnClickListener(v -> addKeyword());
        etKeyword.setOnEditorActionListener((tv, actionId, event) -> {
            addKeyword();
            return true;
        });

        btnPickStart.setOnClickListener(v -> showTimePicker(true));
        btnPickEnd.setOnClickListener(v   -> showTimePicker(false));
        btnStartSession.setOnClickListener(v -> startSession());

        btnGrantAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        btnGrantDeviceAdmin.setOnClickListener(v -> requestDeviceAdmin());
        btnGrantOverlay.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
    }

    // ─── Keyword Actions ────────────────────────────────────────────────────────

    private void addKeyword() {
        if (sessionManager.isSessionPendingOrActive()) {
            Toast.makeText(this, getString(R.string.session_locked_toast), Toast.LENGTH_SHORT).show();
            return;
        }
        String kw = etKeyword.getText() != null ? etKeyword.getText().toString().trim() : "";
        if (kw.isEmpty()) return;
        keywordStore.addKeyword(kw);
        etKeyword.setText("");
        refreshKeywordList();
    }

    // ─── Time Pickers ───────────────────────────────────────────────────────────

    private void showTimePicker(boolean isStart) {
        if (sessionManager.isSessionPendingOrActive()) {
            Toast.makeText(this, getString(R.string.session_locked_toast), Toast.LENGTH_SHORT).show();
            return;
        }
        Calendar cal = Calendar.getInstance();
        android.app.TimePickerDialog dlg = new android.app.TimePickerDialog(
            this,
            android.R.style.Theme_Material_Dialog,
            (view, hour, minute) -> {
                if (isStart) {
                    startHour = hour; startMin = minute;
                    btnPickStart.setText(String.format(Locale.getDefault(), "Start: %02d:%02d", hour, minute));
                } else {
                    endHour = hour; endMin = minute;
                    btnPickEnd.setText(String.format(Locale.getDefault(), "End: %02d:%02d", hour, minute));
                }
            },
            isStart ? (startHour >= 0 ? startHour : cal.get(Calendar.HOUR_OF_DAY)) :
                      (endHour   >= 0 ? endHour   : cal.get(Calendar.HOUR_OF_DAY)),
            isStart ? (startMin  >= 0 ? startMin  : cal.get(Calendar.MINUTE)) :
                      (endMin    >= 0 ? endMin    : cal.get(Calendar.MINUTE)),
            true
        );
        dlg.show();
    }

    // ─── Start Session ──────────────────────────────────────────────────────────

    private void startSession() {
        if (sessionManager.isSessionPendingOrActive()) {
            Toast.makeText(this, getString(R.string.session_locked_toast), Toast.LENGTH_LONG).show();
            return;
        }

        if (keywordStore.isEmpty()) {
            Toast.makeText(this, getString(R.string.err_no_keywords), Toast.LENGTH_LONG).show();
            return;
        }

        if (startHour < 0 || endHour < 0) {
            Toast.makeText(this, "Please pick both start and end times.", Toast.LENGTH_LONG).show();
            return;
        }

        // Build start epoch: today at startHour:startMin
        Calendar startCal = Calendar.getInstance();
        startCal.set(Calendar.HOUR_OF_DAY, startHour);
        startCal.set(Calendar.MINUTE,      startMin);
        startCal.set(Calendar.SECOND,      0);
        startCal.set(Calendar.MILLISECOND, 0);

        // Build end epoch: today at endHour:endMin
        Calendar endCal = Calendar.getInstance();
        endCal.set(Calendar.HOUR_OF_DAY, endHour);
        endCal.set(Calendar.MINUTE,      endMin);
        endCal.set(Calendar.SECOND,      0);
        endCal.set(Calendar.MILLISECOND, 0);

        // If start is in the past, push to tomorrow
        if (startCal.getTimeInMillis() < System.currentTimeMillis()) {
            startCal.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Overnight: if end <= start (e.g. end at 03:00 < start at 08:00) → end is next day
        if (endCal.getTimeInMillis() <= startCal.getTimeInMillis()) {
            endCal.add(Calendar.DAY_OF_YEAR, 1);
        }

        long startMs = startCal.getTimeInMillis();
        long endMs   = endCal.getTimeInMillis();

        sessionManager.startSession(startMs, endMs);

        // Start the foreground service
        Intent svc = new Intent(this, FreedomForegroundService.class);
        startForegroundService(svc);

        // Schedule watchdog
        WatchdogJobService.schedule(this);

        // Enable device admin if not already
        if (!isDeviceAdminActive()) requestDeviceAdmin();

        refreshUi();

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm, EEE dd MMM", Locale.getDefault());
        Toast.makeText(this,
                "Session started!\n" + sdf.format(new Date(startMs)) +
                " → " + sdf.format(new Date(endMs)),
                Toast.LENGTH_LONG).show();
    }

    // ─── UI Refresh ─────────────────────────────────────────────────────────────

    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            uiHandler.postDelayed(this, UI_REFRESH_MS);
        }
    };

    private void refreshUi() {
        boolean active    = sessionManager.isSessionActive();
        boolean scheduled = sessionManager.isSessionScheduled();
        boolean locked    = sessionManager.isSessionPendingOrActive();

        // Session status card
        if (active) {
            tvSessionStatus.setText(getString(R.string.status_active));
            tvSessionStatus.setTextColor(getColor(R.color.session_active));
            tvCountdown.setVisibility(View.VISIBLE);
            tvCountdown.setText("Ends in " + formatDuration(sessionManager.getRemainingMs()));
            cardStatus.setBackgroundResource(0); // let CardView color show
        } else if (scheduled) {
            tvSessionStatus.setText(getString(R.string.status_scheduled));
            tvSessionStatus.setTextColor(getColor(R.color.session_scheduled));
            long until = sessionManager.getStartEpochMs() - System.currentTimeMillis();
            tvCountdown.setVisibility(View.VISIBLE);
            tvCountdown.setText("Starts in " + formatDuration(until));
        } else {
            tvSessionStatus.setText(getString(R.string.status_idle));
            tvSessionStatus.setTextColor(getColor(R.color.session_idle));
            tvCountdown.setVisibility(View.GONE);
        }

        // Lock icon
        ivLockIcon.setVisibility(locked ? View.VISIBLE : View.GONE);

        // Disable editing during session
        boolean editable = !locked;
        adapter.setEditable(editable);
        etKeyword.setEnabled(editable);
        btnAddKeyword.setEnabled(editable);
        btnPickStart.setEnabled(editable);
        btnPickEnd.setEnabled(editable);
        btnStartSession.setEnabled(editable);
        btnStartSession.setAlpha(editable ? 1f : 0.45f);

        // Permissions dots
        updatePermissionDots();
    }

    private void updatePermissionDots() {
        setDotColor(dotAccessibility, isAccessibilityServiceEnabled());
        setDotColor(dotDeviceAdmin,   isDeviceAdminActive());
        setDotColor(dotOverlay,       Settings.canDrawOverlays(this));
    }

    private void setDotColor(View dot, boolean granted) {
        dot.getBackground().setTint(
            getColor(granted ? R.color.perm_granted : R.color.perm_missing));
    }

    // ─── Permission Helpers ─────────────────────────────────────────────────────

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + BlockingAccessibilityService.class.getName();
        try {
            int enabled = Settings.Secure.getInt(
                    getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String flat = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return flat != null && flat.contains(service);
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }

    private boolean isDeviceAdminActive() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName cn = new ComponentName(this, FreedomDeviceAdminReceiver.class);
        return dpm.isAdminActive(cn);
    }

    private void requestDeviceAdmin() {
        ComponentName cn = new ComponentName(this, FreedomDeviceAdminReceiver.class);
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description));
        startActivityForResult(intent, REQ_DEVICE_ADMIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DEVICE_ADMIN) {
            refreshUi();
        }
    }

    // ─── Utilities ──────────────────────────────────────────────────────────────

    private static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        long s = (ms % 60_000) / 1_000;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
