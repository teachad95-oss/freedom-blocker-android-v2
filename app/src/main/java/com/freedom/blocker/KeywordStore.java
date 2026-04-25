package com.freedom.blocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent store for the user's blocked keyword list.
 * Backed by SharedPreferences as a StringSet.
 */
public class KeywordStore {

    private static final String PREFS_NAME   = "freedom_keywords";
    private static final String KEY_KEYWORDS = "keywords";

    private final SharedPreferences prefs;
    private static volatile KeywordStore instance;

    private KeywordStore(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static KeywordStore getInstance(Context ctx) {
        if (instance == null) {
            synchronized (KeywordStore.class) {
                if (instance == null) instance = new KeywordStore(ctx);
            }
        }
        return instance;
    }

    /** Returns a sorted snapshot of all keywords (lowercase). */
    public List<String> getKeywords() {
        Set<String> raw = prefs.getStringSet(KEY_KEYWORDS, new HashSet<>());
        List<String> list = new ArrayList<>(raw);
        Collections.sort(list);
        return list;
    }

    /** Adds a keyword (stored lowercase, trimmed). Ignores blank strings. */
    public void addKeyword(String keyword) {
        String kw = keyword == null ? "" : keyword.toLowerCase().trim();
        if (kw.isEmpty()) return;
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_KEYWORDS, new HashSet<>()));
        set.add(kw);
        prefs.edit().putStringSet(KEY_KEYWORDS, set).apply();
    }

    public void removeKeyword(String keyword) {
        Set<String> set = new HashSet<>(prefs.getStringSet(KEY_KEYWORDS, new HashSet<>()));
        set.remove(keyword);
        prefs.edit().putStringSet(KEY_KEYWORDS, set).apply();
    }

    public boolean isEmpty() {
        return prefs.getStringSet(KEY_KEYWORDS, new HashSet<>()).isEmpty();
    }
}
