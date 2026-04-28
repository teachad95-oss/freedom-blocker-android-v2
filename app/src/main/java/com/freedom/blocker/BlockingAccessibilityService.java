package com.freedom.blocker;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accessibility Service that monitors ALL text content in Chrome, Brave, and Edge
 * (including incognito/private mode). When any visible text contains a blocked
 * keyword during an active session, the browser is immediately killed.
 *
 * Scanning strategy:
 *   1. Check event text (fastest path — catches URL bar changes, tab titles)
 *   2. Walk the full accessibility tree scanning every text node and content
 *      description (catches search queries, page content, URL bar in all modes)
 *
 * Blocking strategy:
 *   1. performGlobalAction(HOME) — immediately leave the browser
 *   2. killBackgroundProcesses — kill the now-backgrounded browser
 *   3. If the user reopens the browser and the keyword is still visible,
 *      the service fires again and kills it again — creating an unbypassable loop
 */
public class BlockingAccessibilityService extends AccessibilityService {

    private static final String TAG             = "FreedomAccessibility";
    private static final long   RELOAD_EVERY_MS = 5_000;
    private static final long   DEBOUNCE_MS     = 1_500;
    private static final int    MAX_SCAN_DEPTH  = 20;

    private long lastReload = 0;
    private long lastBlock  = 0;
    private List<String> keywords;

    private SessionManager sessionManager;
    private KeywordStore   keywordStore;
    private Handler        handler;

    // Browser packages — same package is used for normal + incognito/private
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.chrome",
        "com.brave.browser",
        "com.microsoft.emmx"
    ));

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sessionManager = SessionManager.getInstance(this);
        keywordStore   = KeywordStore.getInstance(this);
        handler        = new Handler(Looper.getMainLooper());
        reloadKeywords();
        Log.d(TAG, "Accessibility service connected — aggressive keyword blocking enabled");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = System.currentTimeMillis();

        // Debounce — skip if we already blocked very recently
        if (now - lastBlock < DEBOUNCE_MS) return;

        // Only act during active sessions
        if (!sessionManager.isSessionActive()) return;

        // Reload keyword list periodically
        if (now - lastReload > RELOAD_EVERY_MS) {
            reloadKeywords();
            lastReload = now;
        }

        if (keywords == null || keywords.isEmpty()) return;

        // Check package
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !TARGET_PACKAGES.contains(pkg.toString())) return;

        String packageName = pkg.toString();

        // ── Fast path: check event text directly ──
        List<CharSequence> eventTexts = event.getText();
        if (eventTexts != null) {
            for (CharSequence cs : eventTexts) {
                if (cs != null) {
                    String text = cs.toString().toLowerCase();
                    for (String keyword : keywords) {
                        if (text.contains(keyword)) {
                            Log.i(TAG, "BLOCK (event text) keyword=\"" + keyword + "\"");
                            killBrowser(packageName, keyword);
                            lastBlock = now;
                            return;
                        }
                    }
                }
            }
        }

        // ── Deep path: walk the full accessibility tree ──
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String matched = scanAllText(root, 0);
        root.recycle();

        if (matched != null) {
            Log.i(TAG, "BLOCK (tree scan) keyword=\"" + matched + "\" pkg=" + packageName);
            killBrowser(packageName, matched);
            lastBlock = now;
        }
    }

    /**
     * Recursively scan ALL text nodes in the accessibility tree.
     * Checks getText() and getContentDescription() on every node.
     * @return the matched keyword, or null if no match found.
     */
    private String scanAllText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_SCAN_DEPTH) return null;

        // Check node text
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            String lower = text.toString().toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    return keyword;
                }
            }
        }

        // Check content description (tab titles, image alt text, etc.)
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            String lower = desc.toString().toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    return keyword;
                }
            }
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String result = scanAllText(child, depth + 1);
                if (result != null) {
                    child.recycle();
                    return result;
                }
                child.recycle();
            }
        }

        return null;
    }

    /**
     * Kill the browser:
     *   1. Go HOME immediately (browser moves to background)
     *   2. Kill the browser process
     *   3. Show a toast explaining what happened
     */
    private void killBrowser(String packageName, String matchedKeyword) {
        // Step 1: Go HOME — this is instant and reliable
        performGlobalAction(GLOBAL_ACTION_HOME);

        // Step 2: Kill the browser process after it moves to background
        handler.postDelayed(() -> {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses(packageName);
                    Log.i(TAG, "killBackgroundProcesses: " + packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error killing browser: " + e.getMessage());
            }
        }, 500);

        // Step 3: Show toast notification
        handler.post(() -> {
            try {
                Toast.makeText(this,
                    "Blocked keyword: \"" + matchedKeyword + "\" — browser closed",
                    Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        });
    }

    private void reloadKeywords() {
        keywords = keywordStore.getKeywords();
        Log.d(TAG, "Keywords reloaded: " + keywords.size() + " entries");
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }
}
