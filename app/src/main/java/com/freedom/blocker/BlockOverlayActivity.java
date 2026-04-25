package com.freedom.blocker;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * Full-screen overlay shown when a keyword is matched in a browser.
 * It appears on top of everything (including the lock screen).
 */
public class BlockOverlayActivity extends Activity {

    public static final String EXTRA_KEYWORD = "matched_keyword";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Draw over lock screen / other apps
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED  |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON    |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_block_overlay);

        // Show which keyword triggered the block
        String keyword = getIntent().getStringExtra(EXTRA_KEYWORD);
        if (keyword != null && !keyword.isEmpty()) {
            TextView tvKeyword = findViewById(R.id.tv_blocked_keyword);
            tvKeyword.setText("Keyword matched: \"" + keyword + "\"");
            tvKeyword.setVisibility(android.view.View.VISIBLE);
        }

        Button btnBack = findViewById(R.id.btn_go_back);
        btnBack.setOnClickListener(v -> finish());
    }

    @Override
    public void onBackPressed() {
        // Don't let hardware back dismiss the overlay immediately without tapping the button
        finish();
    }
}
