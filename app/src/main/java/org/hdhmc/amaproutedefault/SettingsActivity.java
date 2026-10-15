package org.hdhmc.amaproutedefault;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.libxposed.service.XposedService;

public final class SettingsActivity extends Activity {
    static final String GROUP = "route_defaults";
    static final String KEY = "mode";
    static final String[] MODES = {"OFF", "BUS", "ONFOOT", "RIDE", "CAR"};
    private static final int[] LABELS = {R.string.route_mode_original, R.string.route_mode_bus,
            R.string.route_mode_walk, R.string.route_mode_cycle, R.string.route_mode_car};
    private TextView status;
    private RadioGroup choices;
    private boolean updating;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(26);
        root.addView(title);
        status = new TextView(this);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);
        TextView description = new TextView(this);
        description.setText(R.string.settings_description);
        description.setTextSize(16);
        root.addView(description);
        choices = new RadioGroup(this);
        for (int i = 0; i < LABELS.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setId(100 + i);
            radio.setText(LABELS[i]);
            radio.setTextSize(17);
            choices.addView(radio);
        }
        choices.setOnCheckedChangeListener((group, id) -> {
            if (updating) return;
            int index = id - 100;
            XposedService service = ModuleApplication.service;
            if (service == null || index < 0 || index >= MODES.length) return;
            try {
                boolean saved = service.getRemotePreferences(GROUP).edit().putString(KEY, MODES[index]).commit();
                if (!saved) throw new IllegalStateException("Remote preferences commit failed");
                Toast.makeText(this, R.string.preference_saved, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.preference_save_failed, Toast.LENGTH_LONG).show();
                refresh();
            }
        });
        root.addView(choices);
        TextView help = new TextView(this);
        help.setPadding(0, pad, 0, 0);
        help.setText(R.string.settings_help);
        root.addView(help);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
    }

    @Override protected void onStart() {
        super.onStart();
        ModuleApplication.onServiceChanged = this::refresh;
        refresh();
    }

    @Override protected void onStop() {
        ModuleApplication.onServiceChanged = null;
        super.onStop();
    }

    private void refresh() {
        XposedService service = ModuleApplication.service;
        boolean connected = service != null;
        String mode = "OFF";
        try {
            if (connected) {
                SharedPreferences prefs = service.getRemotePreferences(GROUP);
                mode = prefs.getString(KEY, "OFF");
                status.setText(service.getScope().contains("com.autonavi.minimap")
                        ? R.string.status_ready
                        : R.string.status_missing_scope);
            }
        } catch (RuntimeException e) { connected = false; }
        if (!connected) status.setText(R.string.status_disconnected);
        updating = true;
        int selected = 0;
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) selected = i;
        choices.check(100 + selected);
        for (int i = 0; i < choices.getChildCount(); i++) choices.getChildAt(i).setEnabled(connected);
        updating = false;
    }
}
