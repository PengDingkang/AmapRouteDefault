# LSPosed loads this class by the name in META-INF/xposed/java_init.list.
-keep,allowoptimization class org.hdhmc.amaproutedefault.RouteModule {
    public <init>();
}

# DexKit supplies its own consumer rules for JNI entry points. Manifest components
# and Android callbacks are retained by the Android default keep rules.
