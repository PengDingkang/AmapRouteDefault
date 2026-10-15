package org.hdhmc.amaproutedefault;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

public final class RouteModule extends XposedModule {
    private static final String TAG = "AmapRouteDefault";
    private static final String TARGET = "com.autonavi.minimap";
    private boolean mainProcess;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        mainProcess = TARGET.equals(param.getProcessName());
        if (!mainProcess) detach();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!mainProcess || !TARGET.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        try {
            RouteAdapter adapter = RouteLocator.locate(param.getApplicationInfo(), param.getClassLoader(),
                    message -> log(Log.INFO, TAG, message));
            SharedPreferences preferences = getRemotePreferences("route_defaults");

            hook(adapter.initialize).intercept(chain -> {
                Object replacement = null;
                try {
                    replacement = adapter.replacement(chain.getArg(0), preferences.getString("mode", "OFF"));
                } catch (Exception error) {
                    log(Log.WARN, TAG, "Could not apply default; using original behavior", error);
                    replacement = null;
                }
                if (replacement == null) return chain.proceed();
                log(Log.DEBUG, TAG, "Applied initial route preference");
                // Copy only the planning request. The stored request remains untouched so that
                // disabling the preference or opening another route cannot retain our override.
                return chain.proceed(new Object[]{replacement, chain.getArg(1)});
            });
            log(Log.INFO, TAG, "Route initialization adapter installed (DexKit)");
        } catch (Exception | LinkageError error) {
            log(Log.WARN, TAG, "No unique compatible route initializer; adapter inactive", error);
        }
    }
}
