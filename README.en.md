# Amap Default Route

[中文说明](README.md)

A small LSPosed module that lets you choose the default travel mode in Amap: public transit, walking, cycling, driving, or Amap's original behavior.

## Requirements

- Android 14 or later, **arm64-v8a only**.
- LSPosed or a compatible framework supporting **modern Xposed API 102**.
- Amap (`com.autonavi.minimap`); tested with **15.10.1.2063** on Android 16 and LSPosed IT 2.1.1-it (7847).

Other Amap versions use best-effort DexKit feature matching. There must be exactly one structurally validated match; otherwise the module leaves Amap's behavior unchanged. Automatic matching is not a guarantee of compatibility with every version.

## Installation

1. Install the signed release APK.
2. Enable the module in LSPosed and select Amap as its scope.
3. Open **高德默认路线** and choose a default travel mode.
4. Restart Amap after enabling the module for the first time.

The preference applies when opening ordinary route planning without an explicit mode. Manual changes inside the route page and dedicated driving/cycling entries retain their choices. New preferences apply to subsequent route planning without restarting the device.

## Adapting an incompatible version

Fork this project and use an AI Agent to adapt it to **your own device and installed APK**. Ask the agent to read [AGENTS.md](AGENTS.md), inspect your actual Amap installation and relevant logs, update the matching rules, and verify the resulting APK on your authorized device. The Chinese README includes a prompt template. Share only sanitized evidence; do not upload Amap APKs or personal location history.

## Build and release

Use JDK 21 and Android SDK 37:

```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease
python3 -m unittest discover -s scripts/tests -v
```

Version names and codes are defined in `gradle.properties`. Release builds use R8 and resource shrinking; all builds include only arm64-v8a native libraries. Keep the matching R8 mapping file when distributing a release.

GitHub Actions provides CI artifacts and a manual **Release** workflow with optional publication to the source repository and the Xposed Modules Repo. Release signing uses repository secrets; pull-request CI does not receive signing keys. See [maintainer release instructions](docs/release.md).

## License

Original project code is licensed under [Apache License 2.0](LICENSE). Third-party dependencies retain their respective licenses.
