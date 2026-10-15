package org.hdhmc.amaproutedefault;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application implements XposedServiceHelper.OnServiceListener {
    static volatile XposedService service;
    static Runnable onServiceChanged;
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }
    @Override public void onServiceBind(XposedService bound) {
        service = bound;
        new android.os.Handler(getMainLooper()).post(() -> {
            if (onServiceChanged != null) onServiceChanged.run();
        });
    }
    @Override public void onServiceDied(XposedService dead) {
        if (service == dead) service = null;
        new android.os.Handler(getMainLooper()).post(() -> {
            if (onServiceChanged != null) onServiceChanged.run();
        });
    }
}
