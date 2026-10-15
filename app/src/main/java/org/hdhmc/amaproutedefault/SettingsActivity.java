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
    private static final String[] LABELS = {"跟随高德原有行为", "公共交通", "步行", "骑行", "驾车"};
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
        title.setText("高德默认路线");
        title.setTextSize(26);
        root.addView(title);
        status = new TextView(this);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);
        TextView description = new TextView(this);
        description.setText("默认出行方式\n\n打开普通路线规划时优先使用此方式，进入路线页后仍可手动切换。\n");
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
                if (!saved) throw new IllegalStateException("保存失败");
                Toast.makeText(this, "已保存，下次打开路线规划时生效", Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Toast.makeText(this, "保存失败，请检查模块是否已启用", Toast.LENGTH_LONG).show();
                refresh();
            }
        });
        root.addView(choices);
        TextView help = new TextView(this);
        help.setPadding(0, pad, 0, 0);
        help.setText("首次使用：在 LSPosed 中启用本模块，作用域勾选高德地图，然后结束高德进程并重新打开。\n\n使用 DexKit 自动定位路线入口，尝试兼容不同高德版本；无法确认时保留高德原有行为。已实测：15.10.1.2063。");
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
                        ? "模块已启用 · 高德地图已加入作用域"
                        : "模块已启用 · 请在 LSPosed 作用域中勾选高德地图");
            }
        } catch (RuntimeException e) { connected = false; }
        if (!connected) status.setText("请先在支持 API 102 的 LSPosed 中启用模块，再重新打开本页。");
        updating = true;
        int selected = 0;
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) selected = i;
        choices.check(100 + selected);
        for (int i = 0; i < choices.getChildCount(); i++) choices.getChildAt(i).setEnabled(connected);
        updating = false;
    }
}
