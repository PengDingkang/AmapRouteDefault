plugins { id("com.android.application") }
android {
    namespace = "org.hdhmc.amaproutedefault"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "org.hdhmc.amaproutedefault"
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { merges += "META-INF/xposed/*" } }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.luckypray:dexkit:2.3.0")
    testImplementation("junit:junit:4.13.2")
}
