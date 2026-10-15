# 高德默认路线

[English](README.en.md)

为高德地图添加默认出行方式选项的 LSPosed 模块。模块设置中可选公共交通、步行、骑行、驾车，或关闭覆盖、跟随高德原有行为。

## 兼容环境

- 使用 **DexKit 2.3.0** 自动定位，尝试兼容不同高德版本；无匹配或匹配不唯一时不挂接。
- 已实测高德 **15.10.1.2063**；其他版本属于尽力适配，尚未逐版实测。
- Android 14 及以上，支持 **现代 Xposed API 102** 的 LSPosed。
- 仅支持 **arm64-v8a** 设备，安装包不包含其他架构的原生库。
- 实机环境：Android 16 / LSPosed IT 2.1.1-it (7847)。

## 使用

1. 安装 APK，在 LSPosed 中启用“高德默认路线”，作用域选择“高德地图”。
2. 打开“高德默认路线”，选择默认出行方式。
3. 首次启用后结束高德进程并重新打开。
4. 后续修改默认方式，在下次进入普通路线规划时生效，无需重启手机。

路线页内仍可自由切换出行方式。专门的驾车、骑行等入口以及明确指定方式的第三方链接保留调用方的选择。默认方式适用于首页“路线”和普通搜索的“路线”入口；已有路线页不会因修改设置而立即切换。

需要恢复时选择“跟随高德原有行为”，或在 LSPosed 中停用模块后重启高德。

设置页跟随系统深浅色模式，背景和单选按钮采用系统动态配色，无需单独设置。

## 实现

`RouteLocator` 通过两类代码特征查找路线初始化方法：

- 初始化日志字符串，或微信导航、出行推荐等业务字符串组合。
- `PlanHomeService.getInitRouteType()` 的直接调用关系，覆盖日志字符串被移除的情况。

候选必须通过结构与字节码校验：静态双参数初始化方法、单方法回调、POI 请求字段、唯一的出行枚举字段、枚举名称与数值映射、结果构造方法，以及对请求字段、回调、结果构造的实际访问。最终只允许一个匹配。类名、方法名和请求字段名可以随混淆变化；不再硬编码 `p91`、`s91`、`a`，也不按高德版本号拒绝运行。

`RouteAdapter` 在请求未指定出行方式时复制参数，使用高德原有的显式方式分支初始化。原始请求和路线页切换事件不被修改。设置通过 LSPosed Remote Preferences 共享，仅加载于高德主进程；模块自身不申请网络或位置权限。

模块应用自身的本地数据不参与云端备份和设备迁移；默认方式及作用域由 LSPosed 管理，其迁移行为由框架决定。

定位结果存入高德私有 `code_cache`，用定位规则版本、base/split APK 的安装路径、大小和修改时间作为缓存标识。更新高德或规则后重新扫描；缓存命中仍重新校验反射结构。缓存损坏时重新扫描，扫描异常或结果不唯一时保留高德原有行为。原生扫描使用 DexKit 2.3.0，它包含共享线程池销毁崩溃的上游修复。

自动定位不能保证所有高德版本兼容：如果初始化架构、枚举含义或特征字符串和服务接口同时变化，仍需要更新匹配规则。未在用户手机上升级或降级高德进行其他版本测试。

## 版本不兼容时自行适配

如果你安装的高德版本无法自动适配，建议 **fork 本项目，使用 AI Agent 为自己的设备和实际安装包做适配**。项目提供了 [AGENTS.md](AGENTS.md)，说明代码入口、分析流程、必须保留的行为和验证要求。

1. 在代码托管平台 fork 本项目并克隆到本地，准备 JDK、Android SDK 和支持本地文件及终端操作的 AI Agent。
2. 连接自己的 Android 设备并启用 ADB 调试，告知 Agent 目标设备、高德和 LSPosed 版本、复现步骤，以及允许进行的设备操作。让 Agent 提取本机实际安装的 APK 并分析相关日志，而不是根据版本号猜测混淆名称。
3. 让 Agent 阅读 `AGENTS.md`，修改定位或适配规则、完成构建与测试，再在你授权的设备上验证。没有完成实机测试的版本应明确标为未验证。

可以把下面的提示词补全后交给 Agent：

```text
请先阅读本项目的 AGENTS.md、README.md 和 VALIDATION.md，
为我自己的设备和高德安装包适配默认路线功能。

目标设备型号/ADB 序列号：<填写>
Android 版本：<填写>
高德版本及安装渠道：<填写；请同时从设备确认实际版本和 APK>
LSPosed 版本/API：<填写>
模块版本、默认出行方式：<填写>
复现步骤和实际现象：<填写>
允许的设备操作：<例如读取安装包和相关日志、覆盖安装模块、结束并重启高德>

请根据实际 APK 和日志定位问题，优先扩展 DexKit 特征及结构适配，
保留显式出行方式和手动切换，匹配不明确时保持高德原有行为。
完成相关测试、构建和已授权的实机验证，更新验证记录，
交付可安装 APK，并说明验证范围和仍不支持的情况。
```

AI Agent 的改动仍需在自己的设备上验证。如果适配具有通用价值，欢迎提交 Pull Request，并附上脱敏后的版本信息、匹配依据和验证结果；不要公开高德 APK、用户位置或完整设备日志。

## 构建

使用 JDK 21、Android SDK 37：

```sh
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug testDebugUnitTest lintDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。Gradle Wrapper 使用 Gradle 9.8.1 并校验官方发行包 SHA-256；Xposed API 仅为编译依赖，Service 库打包在设置应用中。

构建 release 时，在未跟踪的 `local.properties` 中配置 `releaseStoreFile`、`releaseStorePassword`、`releaseKeyAlias`、`releaseKeyPassword`，或使用对应的环境变量 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。签名文件路径相对于项目根目录，也支持绝对路径。不要提交签名文件和密码。

```sh
./gradlew assembleRelease testDebugUnitTest lintRelease
```

配置完整时，签名后的输出为 `app/build/outputs/apk/release/app-release.apk`；未配置签名时仅生成 unsigned APK，不能直接安装。首次从 debug 签名切换到 release 签名可能需要重新安装模块并恢复设置，后续应使用同一 release 密钥升级。

Release 默认启用 R8 代码压缩、优化、混淆和资源压缩；所有构建仅打包 arm64-v8a 原生库。`app/proguard-rules.pro` 保留由 LSPosed 动态加载的模块入口，DexKit 的 JNI 入口使用依赖自带的 consumer rules。发布时应同时留存 `app/build/outputs/mapping/release/mapping.txt`，以便还原混淆后的崩溃堆栈。

## CI 与自动发版

版本统一在 `gradle.properties` 中设置。GitHub Actions 的 **CI** 会构建 Debug 和 R8 Release、运行测试与 Lint，并校验 APK 包名、版本、arm64 库和 Xposed 入口；构建产物及检查报告可从 Actions 下载。CI 的 Release APK 未签名，日常安装请使用正式发布的签名 APK。

**Release** 工作流支持手动构建签名包，可分别选择发布到源码仓库和 `Xposed-Modules-Repo/org.hdhmc.amaproutedefault`。源码仓库留存 APK、R8 mapping 和校验和；LSPosed 仓库同步模块元数据及同一份 APK。需要的 Secrets、输入参数和补发方法见 [发版说明](docs/release.md)。

## 许可证

本项目原创代码以 [Apache License 2.0](LICENSE) 发布。第三方依赖遵循各自的许可证。
