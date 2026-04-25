package com.freedom.blocker;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accessibility Service that monitors the address bar of Chrome, Brave, and Edge.
 * When a URL contains a blocked keyword during an active session, it triggers
 * a GLOBAL_ACTION_BACK and launches the BlockOverlayActivity.
 */
public class BlockingAccessibilityService extends AccessibilityService {

    private static final String TAG              = "FreedomAccessibility";
    private static final long   RELOAD_EVERY_MS  = 5_000;   // reload keywords every 5 s
    private static final long   DEBOUNCE_MS      = 400;      // ignore rapid duplicate events

    private long lastReload = 0;
    private long lastBlock  = 0;
    private List<String> keywords;

    private SessionManager sessionManager;
    private KeywordStore   keywordStore;

    // Exact view IDs for the URL / address bar in each browser
    private static final Set<String> URL_BAR_IDS = new HashSet<>(Arrays.asList(
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/search_box_text",
        "com.brave.browser:id/url_bar",
        "com.brave.browser:id/search_box_text",
        "com.microsoft.emmx:id/url_bar",
        "com.microsoft.emmx:id/search_box_text"
    ));

    // Only observe events from these packages (declared in accessibility_service_config.xml too)
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
        reloadKeywords();
        Log.d(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = System.currentTimeMillis();

        // Debounce – skip if we already processed an event very recently
        if (now - lastBlock < DEBOUNCE_MS) return;

        // Only act during active sessions
        if (!sessionManager.isSessionActive()) return;

        // Reload keyword list periodically (in case user added one mid-session via another app)
        if (now - lastReload > RELOAD_EVERY_MS) {
            reloadKeywords();
            lastReload = now;
        }

        if (keywords == null || keywords.isEmpty()) return;

        // Check package
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !TARGET_PACKAGES.contains(pkg.toString())) return;

        // Walk the accessibility tree looking for a URL bar node
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        boolean blocked = scanAndBlock(root);
        root.recycle();

        if (blocked) lastBlock = now;
    }

    /**
     * Recursively scans the node tree.
     * @return true if a keyword was matched and blocking was triggered.
     */
    private boolean scanAndBlock(AccessibilityNodeInfo node) {
        if (node == null) return false;

        String viewId = node.getViewIdResourceName();
        CharSequence text = node.getText();

        if (viewId != null && URL_BAR_IDS.contains(viewId)
                && text != null && text.length() > 0) {

            String url = text.toString().toLowerCase();
            for (String keyword : keywords) {
                if (url.contains(keyword)) {
                    Log.i(TAG, "BLOCKING — url=\"" + url + "\" keyword=\"" + keyword + "\"");
                    triggerBlock(keyword);
                    return true;
                }
            }
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (scanAndBlock(child)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }

    private void triggerBlock(String matchedKeyword) {
        // 1. Navigate back immediately
        performGlobalAction(GLOBAL_ACTION_BACK);

        // 2. Show full-screen block overlay
        Intent intent = new Intent(this, BlockOverlayActivity.class);
        intent.putExtra(BlockOverlayActivity.EXTRA_KEYWORD, matchedKeyword);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TOP
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
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
