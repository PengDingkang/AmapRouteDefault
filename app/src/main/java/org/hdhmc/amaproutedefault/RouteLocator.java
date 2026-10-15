package org.hdhmc.amaproutedefault;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;
import org.luckypray.dexkit.wrap.DexField;
import org.luckypray.dexkit.wrap.DexMethod;

/** Uses bytecode anchors and call relationships, never guessed obfuscated names. */
final class RouteLocator {
    private static final String RULE_REVISION = "route-init-v3-dexkit-2.3";

    // Cache hits skip scanning. On a miss, each APK gets one bridge reused for all queries,
    // then closed to release its native resources instead of retaining it in the host process.
    @SuppressLint("DuplicateCreateDexKit")
    static RouteAdapter locate(ApplicationInfo info, ClassLoader loader, Consumer<String> log)
            throws ReflectiveOperationException, IOException {
        List<String> paths = new ArrayList<>();
        paths.add(info.sourceDir);
        if (info.splitSourceDirs != null) paths.addAll(Arrays.asList(info.splitSourceDirs));
        String fingerprint = fingerprint(paths);
        File cache = new File(info.dataDir, "code_cache/amap-route-default-v2.properties");
        try {
            Properties saved = new Properties();
            try (FileInputStream input = new AtomicFile(cache).openRead()) { saved.load(input); }
            if (fingerprint.equals(saved.getProperty("fingerprint"))) {
                RouteAdapter cached = RouteAdapter.validate(
                        new DexMethod(saved.getProperty("method")).getMethodInstance(loader));
                log.accept("Locator cache hit: " + cached.initialize);
                return cached;
            }
            log.accept("Host APK or locator rules changed; discarding cache");
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Missing, stale, corrupt or structurally invalid cache: perform a fresh scan.
        }

        long started = System.nanoTime();
        System.loadLibrary("dexkit");
        Map<String, RouteAdapter> matches = new LinkedHashMap<>();
        for (String path : paths) {
            try (DexKitBridge bridge = DexKitBridge.create(path)) {
                collect(bridge.findMethod(FindMethod.create().matcher(initializerMatcher()
                        .usingStrings("initRouteType() routeTypeSource"))), loader, matches, log);
                // Logging may be stripped in some builds. Also query stable business strings.
                collect(bridge.findMethod(FindMethod.create().matcher(initializerMatcher()
                        .usingStrings("weixin_navi", "amap_mo", "tab_recommend_filter"))),
                        loader, matches, log);
                // Independent fallback: the public service delegates directly to the initializer.
                for (MethodData service : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass("com.amap.bundle.planhome.PlanHomeService")
                        .name("getInitRouteType").returnType("void").paramCount(2)))) {
                    collect(service.getInvokes(), loader, matches, log);
                }
            }
        }
        RouteAdapter adapter = unique(matches);
        log.accept("DexKit matched " + adapter.initialize + " routeField=" + adapter.routeField.getName()
                + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        save(cache, fingerprint, new DexMethod(adapter.initialize).toString(), log);
        return adapter;
    }

    private static MethodMatcher initializerMatcher() {
        return MethodMatcher.create().modifiers(Modifier.STATIC).returnType("void").paramCount(2);
    }

    private static void collect(Iterable<MethodData> candidates, ClassLoader loader,
                                Map<String, RouteAdapter> matches, Consumer<String> log) {
        for (MethodData candidate : candidates) {
            if (matches.containsKey(candidate.getDescriptor())) continue;
            try {
                RouteAdapter adapter = RouteAdapter.validate(candidate.getMethodInstance(loader));
                // Require route-field access, a callback call and construction of its result type.
                String field = new DexField(adapter.routeField).toString();
                String callback = new DexMethod(adapter.callback).toString();
                String result = new DexMethod(adapter.resultConstructor).toString();
                boolean readsRoute = false, callsCallback = false, createsResult = false;
                for (UsingFieldData used : candidate.getUsingFields()) {
                    if (field.equals(used.getField().getDescriptor())) readsRoute = true;
                }
                for (MethodData invoked : candidate.getInvokes()) {
                    if (callback.equals(invoked.getDescriptor())) callsCallback = true;
                    if (result.equals(invoked.getDescriptor())) createsResult = true;
                }
                if (!readsRoute || !callsCallback || !createsResult) {
                    log.accept("Rejected candidate without route-initialization bytecode: " + candidate.getDescriptor());
                    continue;
                }
                matches.put(candidate.getDescriptor(), adapter);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                log.accept("Rejected candidate " + candidate.getDescriptor() + ": " + error.getClass().getSimpleName());
            }
        }
    }

    static RouteAdapter unique(Map<String, RouteAdapter> matches) throws NoSuchMethodException {
        if (matches.size() != 1) throw new NoSuchMethodException(
                "Expected one validated route initializer; found " + matches.size());
        return matches.values().iterator().next();
    }

    static String fingerprint(List<String> paths) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(RULE_REVISION.getBytes(StandardCharsets.UTF_8));
            for (String path : paths) {
                File apk = new File(path);
                if (!apk.isFile()) throw new IOException("Missing host APK");
                // Upgrades change install paths; size/mtime also cover in-place changes.
                String identity = "\n" + apk.getCanonicalPath() + "\n" + apk.length() + "\n" + apk.lastModified();
                digest.update(identity.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void save(File cache, String fingerprint, String method, Consumer<String> log) {
        AtomicFile atomic = new AtomicFile(cache);
        FileOutputStream output = null;
        try {
            File parent = cache.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create cache directory");
            Properties saved = new Properties();
            saved.setProperty("fingerprint", fingerprint);
            saved.setProperty("method", method);
            output = atomic.startWrite();
            saved.store(output, RULE_REVISION);
            atomic.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            log.accept("Locator cache unavailable; next launch will rescan");
        }
    }
}
