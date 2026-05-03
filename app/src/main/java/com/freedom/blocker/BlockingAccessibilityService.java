package com.freedom.blocker;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V5 – Strategy reverse-engineered from "Lock Me Out" APK.
 *
 * Detection (3 layers):
 *   1. Fast-path: check url_bar / tab_title / menu_header_url by view ID
 *   2. Event text: catch raw text-changed events (works in Incognito)
 *   3. Full tree scan: fallback walk of all nodes
 *
 * Blocking (2-step with retry, same as Lock Me Out):
 *   Step 1 – Click the browser's Home button (view ID based)
 *   Step 2 – Find the URL bar and ACTION_SET_TEXT "http://www.google.com", then submit
 *   Step 3 – GLOBAL_ACTION_HOME to minimize browser
 *   Step 4 – Launch BlockOverlayActivity
 *   Step 5 – Fallback killBackgroundProcesses (Android < 14)
 */
public class BlockingAccessibilityService extends AccessibilityService {

    private static final String TAG             = "FreedomAccessibility";
    private static final long   RELOAD_EVERY_MS = 5_000;
    private static final long   DEBOUNCE_MS     = 400;
    private static final int    MAX_SCAN_DEPTH  = 25;
    private static final String SAFE_URL        = "http://www.google.com";

    private long lastReload = 0;
    private long lastBlock  = 0;
    private List<String> keywords;

    private SessionManager sessionManager;
    private KeywordStore   keywordStore;
    private Handler        handler;

    // ── Target browser packages ────────────────────────────────────────────
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.chrome",
        "com.chrome.beta",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.microsoft.emmx.canary"
    ));

    // ── Per-browser URL-bar view IDs (from Lock Me Out DEX analysis) ───────
    private static final Map<String, String> URL_BAR_IDS = new HashMap<String, String>() {{
        put("com.android.chrome",       "com.android.chrome:id/url_bar");
        put("com.chrome.beta",          "com.chrome.beta:id/url_bar");
        put("com.brave.browser",        "com.brave.browser:id/url_bar");
        put("com.microsoft.emmx",       "com.microsoft.emmx:id/url_bar");
        put("com.microsoft.emmx.canary","com.microsoft.emmx.canary:id/url_bar");
    }};

    // ── Per-browser Tab-title view IDs ─────────────────────────────────────
    private static final Map<String, String> TAB_TITLE_IDS = new HashMap<String, String>() {{
        put("com.android.chrome",  "com.android.chrome:id/tab_title");
        put("com.chrome.beta",     "com.chrome.beta:id/tab_title");
        put("com.brave.browser",   "com.brave.browser:id/tab_title");
    }};

    // ── Per-browser menu-header-URL view IDs ───────────────────────────────
    private static final Map<String, String> MENU_URL_IDS = new HashMap<String, String>() {{
        put("com.android.chrome",  "com.android.chrome:id/menu_header_url");
        put("com.chrome.beta",     "com.chrome.beta:id/menu_header_url");
    }};

    // ── Per-browser Home-button view IDs ───────────────────────────────────
    private static final Map<String, String[]> HOME_BUTTON_IDS = new HashMap<String, String[]>() {{
        put("com.android.chrome",       new String[]{"com.android.chrome:id/home_button"});
        put("com.chrome.beta",          new String[]{"com.chrome.beta:id/home_button"});
        put("com.brave.browser",        new String[]{"com.brave.browser:id/bottom_home_button",
                                                      "com.brave.browser:id/home_button"});
        put("com.microsoft.emmx",       new String[]{"com.microsoft.emmx:id/home_button"});
        put("com.microsoft.emmx.canary",new String[]{"com.microsoft.emmx.canary:id/home_button"});
    }};

    // ── New-tab button IDs (fallback if home button not found) ────────────
    private static final Map<String, String> NEW_TAB_IDS = new HashMap<String, String>() {{
        put("com.android.chrome",  "com.android.chrome:id/open_in_new_tab");
        put("com.chrome.beta",     "com.chrome.beta:id/open_in_new_tab");
        put("com.brave.browser",   "com.brave.browser:id/open_in_new_tab");
    }};

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sessionManager = SessionManager.getInstance(this);
        keywordStore   = KeywordStore.getInstance(this);
        handler        = new Handler(Looper.getMainLooper());
        reloadKeywords();
        Log.i(TAG, "V5 service connected – LockMeOut strategy active");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastBlock < DEBOUNCE_MS) return;
        if (!sessionManager.isSessionActive()) return;

        if (now - lastReload > RELOAD_EVERY_MS) {
            reloadKeywords();
            lastReload = now;
        }
        if (keywords == null || keywords.isEmpty()) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null || !TARGET_PACKAGES.contains(pkg.toString())) return;
        String packageName = pkg.toString();

        // ── Layer 1: Check specific view IDs (URL bar, tab title, menu URL) ──
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String matched = checkSpecificViewIds(packageName, root);

        // ── Layer 2: Check raw event text (catches Incognito typing) ─────────
        if (matched == null) {
            List<CharSequence> eventTexts = event.getText();
            if (eventTexts != null) {
                for (CharSequence cs : eventTexts) {
                    if (cs == null) continue;
                    String lower = cs.toString().toLowerCase();
                    for (String kw : keywords) {
                        if (lower.contains(kw)) { matched = kw; break; }
                    }
                    if (matched != null) break;
                }
            }
        }

        // ── Layer 3: Full accessibility tree scan ─────────────────────────────
        if (matched == null && root != null) {
            matched = scanAllText(root, 0);
        }

        if (matched != null) {
            Log.i(TAG, "BLOCKED keyword=\"" + matched + "\" in " + packageName);
            lastBlock = now;
            executeBlock(packageName, matched, root);
        } else if (root != null) {
            root.recycle();
        }
    }

    /**
     * Layer 1 – Check only the specific view IDs that Lock Me Out targets.
     * These are the URL bar, tab title, and menu header URL.
     */
    private String checkSpecificViewIds(String packageName, AccessibilityNodeInfo root) {
        if (root == null) return null;

        // Check URL bar
        String urlBarId = URL_BAR_IDS.get(packageName);
        if (urlBarId != null) {
            String match = checkNodeListForKeyword(root.findAccessibilityNodeInfosByViewId(urlBarId));
            if (match != null) return match;
        }

        // Check tab title
        String tabTitleId = TAB_TITLE_IDS.get(packageName);
        if (tabTitleId != null) {
            String match = checkNodeListForKeyword(root.findAccessibilityNodeInfosByViewId(tabTitleId));
            if (match != null) return match;
        }

        // Check menu header URL
        String menuUrlId = MENU_URL_IDS.get(packageName);
        if (menuUrlId != null) {
            String match = checkNodeListForKeyword(root.findAccessibilityNodeInfosByViewId(menuUrlId));
            if (match != null) return match;
        }

        return null;
    }

    private String checkNodeListForKeyword(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return null;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            CharSequence text = node.getText();
            if (text != null) {
                String lower = text.toString().toLowerCase();
                for (String kw : keywords) {
                    if (lower.contains(kw)) {
                        node.recycle();
                        return kw;
                    }
                }
            }
            CharSequence desc = node.getContentDescription();
            if (desc != null) {
                String lower = desc.toString().toLowerCase();
                for (String kw : keywords) {
                    if (lower.contains(kw)) {
                        node.recycle();
                        return kw;
                    }
                }
            }
            node.recycle();
        }
        return null;
    }

    /**
     * Full recursive tree scan – fallback for when view IDs don't match.
     */
    private String scanAllText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_SCAN_DEPTH) return null;

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            String lower = text.toString().toLowerCase();
            for (String kw : keywords) {
                if (lower.contains(kw)) return kw;
            }
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            String lower = desc.toString().toLowerCase();
            for (String kw : keywords) {
                if (lower.contains(kw)) return kw;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String result = scanAllText(child, depth + 1);
                child.recycle();
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * Two-step blocking strategy (reverse-engineered from Lock Me Out):
     *   Step 1: Click the browser's Home button
     *   Step 2: Set URL bar text to SAFE_URL and submit (if step 1 failed)
     *   Then: HOME global action + overlay + optional process kill
     */
    private void executeBlock(String packageName, String keyword, AccessibilityNodeInfo root) {

        // STEP 1: Click the browser's Home button
        boolean step1Done = false;
        if (root != null) {
            String[] homeIds = HOME_BUTTON_IDS.get(packageName);
            if (homeIds != null) {
                outer:
                for (String id : homeIds) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                    if (nodes != null) {
                        for (AccessibilityNodeInfo btn : nodes) {
                            if (btn != null) {
                                if (btn.isClickable() || btn.isEnabled()) {
                                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    step1Done = true;
                                    Log.i(TAG, "Step 1 completed – clicked home: " + id);
                                    btn.recycle();
                                    break outer;
                                }
                                btn.recycle();
                            }
                        }
                    }
                }
            }
            if (!step1Done) {
                Log.w(TAG, "Step 1 NOT completed – home button not found in " + packageName);
            }
        }

        // STEP 2: If step 1 failed, navigate to safe URL via URL bar
        final boolean step1Completed = step1Done;
        final AccessibilityNodeInfo rootRef = root;

        if (!step1Done && root != null) {
            String urlBarId = URL_BAR_IDS.get(packageName);
            if (urlBarId != null) {
                List<AccessibilityNodeInfo> urlNodes = root.findAccessibilityNodeInfosByViewId(urlBarId);
                if (urlNodes != null && !urlNodes.isEmpty()) {
                    AccessibilityNodeInfo urlBar = urlNodes.get(0);
                    if (urlBar != null) {
                        // Focus the URL bar
                        urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        // Set text to safe URL
                        Bundle args = new Bundle();
                        args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            SAFE_URL
                        );
                        boolean textSet = urlBar.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT, args
                        );
                        if (textSet) {
                            // Submit by pressing Enter (IME_ACTION_GO)
                            urlBar.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
                            handler.postDelayed(() ->
                                performGlobalAction(GLOBAL_ACTION_HOME), 200
                            );
                            Log.i(TAG, "Step 2 completed – set URL bar to safe URL");
                        } else {
                            Log.w(TAG, "Step 2 NOT completed – ACTION_SET_TEXT failed");
                        }
                        urlBar.recycle();
                    }
                }
            }
        }

        // Retry step 1 after a short delay if it didn't work first time
        if (!step1Completed) {
            handler.postDelayed(() -> {
                AccessibilityNodeInfo retryRoot = getRootInActiveWindow();
                if (retryRoot == null) return;
                CharSequence retryPkg = retryRoot.getPackageName();
                if (retryPkg == null || !packageName.equals(retryPkg.toString())) {
                    retryRoot.recycle();
                    return;
                }
                String[] homeIds = HOME_BUTTON_IDS.get(packageName);
                if (homeIds != null) {
                    for (String id : homeIds) {
                        List<AccessibilityNodeInfo> nodes = retryRoot.findAccessibilityNodeInfosByViewId(id);
                        if (nodes != null) {
                            for (AccessibilityNodeInfo btn : nodes) {
                                if (btn != null && (btn.isClickable() || btn.isEnabled())) {
                                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    Log.i(TAG, "Step 1 RETRY completed – clicked: " + id);
                                    btn.recycle();
                                    break;
                                }
                                if (btn != null) btn.recycle();
                            }
                        }
                    }
                }
                retryRoot.recycle();
            }, 300);
        }

        // STEP 3: Send browser to background
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 150);

        // STEP 4: Launch block overlay
        Intent intent = new Intent(this, BlockOverlayActivity.class);
        intent.putExtra(BlockOverlayActivity.EXTRA_KEYWORD, keyword);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TOP
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);

        // STEP 5: Fallback – kill process (works on Android < 14)
        handler.postDelayed(() -> {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) am.killBackgroundProcesses(packageName);
            } catch (Exception ignored) {}
        }, 600);

        // Also fire BACK twice as a last resort to exit any blocked page
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 250);
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 450);

        // Notify user
        handler.post(() -> {
            try {
                Toast.makeText(this, "Blocked: \"" + keyword + "\"", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });

        if (root != null) root.recycle();
    }

    private void reloadKeywords() {
        keywords = keywordStore.getKeywords();
        Log.d(TAG, "Keywords reloaded: " + keywords.size());
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }
}
