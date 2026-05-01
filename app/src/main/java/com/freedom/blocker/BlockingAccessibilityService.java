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
 * (including incognito/private mode). 
 *
 * Scanning strategy:
 *   1. Check event text (fastest path — catches URL bar changes, tab titles)
 *   2. Walk the full accessibility tree scanning every text node and content
 *      description (catches search queries, page content, URL bar in all modes)
 *
 * Blocking strategy (V4 Auto-Navigation for Android 14+):
 *   1. Auto-Click Home: Attempt to find and click the browser's Home button
 *   2. Auto-Back: If no Home button, fire GLOBAL_ACTION_BACK to leave the page/search
 *   3. Overlay: Launch the BlockOverlayActivity to cover the screen
 *   4. Minimize: Fire GLOBAL_ACTION_HOME to send the browser to the background
 *   5. Kill (Fallback): Try killBackgroundProcesses for older Android versions
 */
public class BlockingAccessibilityService extends AccessibilityService {

    private static final String TAG             = "FreedomAccessibility";
    private static final long   RELOAD_EVERY_MS = 5_000;
    private static final long   DEBOUNCE_MS     = 500;
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
        Log.d(TAG, "Accessibility connected — V4 auto-navigation blocking enabled");
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

        AccessibilityNodeInfo root = getRootInActiveWindow();

        // ── Fast path: check event text directly ──
        List<CharSequence> eventTexts = event.getText();
        if (eventTexts != null) {
            for (CharSequence cs : eventTexts) {
                if (cs != null) {
                    String text = cs.toString().toLowerCase();
                    for (String keyword : keywords) {
                        if (text.contains(keyword)) {
                            Log.i(TAG, "BLOCK (event text) keyword=\"" + keyword + "\"");
                            navigateAwayAndBlock(packageName, keyword, root);
                            lastBlock = now;
                            if (root != null) root.recycle();
                            return;
                        }
                    }
                }
            }
        }

        // ── Deep path: walk the full accessibility tree ──
        if (root == null) return;

        String matched = scanAllText(root, 0);
        if (matched != null) {
            Log.i(TAG, "BLOCK (tree scan) keyword=\"" + matched + "\" pkg=" + packageName);
            navigateAwayAndBlock(packageName, matched, root);
            lastBlock = now;
        }
        
        root.recycle();
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
     * Executes the Auto-Navigation blocking strategy
     */
    private void navigateAwayAndBlock(String packageName, String matchedKeyword, AccessibilityNodeInfo rootNode) {
        // 1. Attempt to auto-click the Home button in the browser UI
        boolean clickedHome = false;
        if (rootNode != null) {
            String[] homeIds = {
                "com.android.chrome:id/home_button",
                "com.brave.browser:id/bottom_home_button",
                "com.brave.browser:id/home_button"
            };
            for (String id : homeIds) {
                List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    for (AccessibilityNodeInfo btn : nodes) {
                        if (btn.isClickable()) {
                            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            clickedHome = true;
                            Log.i(TAG, "Clicked home button: " + id);
                            break;
                        }
                    }
                }
                if (clickedHome) break;
            }
        }

        // 2. If Home button not found/clicked, simulate BACK to exit the blocked search/page
        if (!clickedHome) {
            Log.i(TAG, "Home button not found, sending GLOBAL_ACTION_BACK");
            performGlobalAction(GLOBAL_ACTION_BACK);
            // Queue a second BACK just to be sure we leave the page
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 150);
        }

        // 3. Launch full-screen block overlay
        Intent intent = new Intent(this, BlockOverlayActivity.class);
        intent.putExtra(BlockOverlayActivity.EXTRA_KEYWORD, matchedKeyword);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TOP
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);

        // 4. Send the browser to the background
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 300);

        // 5. Fallback: try to kill background processes (works on Android < 14)
        handler.postDelayed(() -> {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses(packageName);
                }
            } catch (Exception ignored) {}
        }, 500);

        // 6. Show toast notification
        handler.post(() -> {
            try {
                Toast.makeText(this,
                    "Blocked keyword: \"" + matchedKeyword + "\" — auto-navigated away",
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
