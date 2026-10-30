// HyperGestures: the settings page (plan T6), an app icon in the home app (MAIN/LAUNCHER) instead
// of `adb shell settings put` for every switch. HgSettingsFragment holds the list. Material 3 in
// the page's own palette (HgSettingsTheme), light or dark with the system, under a large title
// that scrolls away with the list; edge to edge, padded by the system bars. Nothing of it runs
// unless the page is open. It runs in its own process, com.hypergestures:settings (manifest), so
// it must only use Settings and files, never the recents/gesture process's in-process state.
package com.android.quickstep;

import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.launcher3.R;

public final class HgSettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hg_settings_activity);
        View root = findViewById(R.id.hg_settings_root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        TextView title = findViewById(R.id.hg_settings_title);
        title.setText(R.string.hg_settings_title);
        // The module copies its log into our external files dir only once that exists (hg_log's
        // HG_LOG_MIRROR, for Save logs): opening the page creates it.
        getExternalFilesDir(null);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.hg_settings_content, new HgSettingsFragment())
                    .commit();
        }
    }
}
