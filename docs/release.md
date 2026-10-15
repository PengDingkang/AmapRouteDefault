# CI 与发版说明

版本以 `gradle.properties` 的 `appVersionName`、`appVersionCode` 为唯一来源。当前正式包名为 `org.hdhmc.amaproutedefault`，作用域为 `com.autonavi.minimap`，仅支持 arm64-v8a 和现代 Xposed API 102。

## 普通 CI

`CI` 工作流在 main 代码推送、Pull Request 和手动运行时触发，纯 Markdown 变更跳过自动构建。流程包括工作流检查、发布工具测试、Debug/Release 构建、Java 单元测试、两种构建的 Lint，以及 APK 元数据、ABI 和 Xposed 入口检查。

CI 和 Release 固定使用 `ubuntu-24.04`，避免 `ubuntu-latest` 的系统版本迁移自动改变构建环境。Gradle 版本由仓库 Wrapper 固定，升级时同时更新 Wrapper 文件及发行包 SHA-256，并验证 Debug/Release 构建。

CI 不读取签名 Secrets，也不发布 GitHub Release。下载的 artifact 包含 Debug APK、**不可直接安装的 unsigned Release APK** 和该 Release 的 R8 mapping；测试与 Lint 报告单独保存 14 天。Debug 与 Release 使用同一包名，调试签名与正式签名不同，不能直接互相覆盖安装。

## GitHub 配置

沿用 HyperWallpaperMonet 的 Repository Secrets 名称：

| Secret | 用途 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | 正式 keystore 的 Base64 内容 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 签名 alias |
| `RELEASE_KEY_PASSWORD` | alias 密码 |
| `XPOSED_REPO_TOKEN` | 目标 Xposed 仓库的 Contents 读写权限，仅在选择同步发布时使用 |

源码仓库发布使用工作流自带的 `GITHUB_TOKEN`，工作流仅申请 Contents 写权限。签名文件在 runner 临时目录还原，并在结束时清理；不要提交 keystore、`local.properties` 或密码。后续升级必须使用相同正式签名。

可选 Repository Variables：

- `XPOSED_REPOSITORY`：默认 `Xposed-Modules-Repo/org.hdhmc.amaproutedefault`。该仓库需要已经存在且 Token 有写权限；工作流可初始化空仓库的 main 分支，不负责申请收录或创建组织仓库。
- `RELEASE_CERT_SHA256`：预期的正式签名证书 SHA-256 指纹，可带冒号。配置后证书不匹配会终止发布。指纹是公开信息，可从已安装的正式 APK 使用 `apksigner verify --print-certs` 获取。

## 手动 Release

在源码仓库 Actions 中选择 **Release → Run workflow**，分支选择 main：

| 输入 | 行为 |
| --- | --- |
| `version` | 必须精确匹配 `appVersionName`，例如 `0.2.2` |
| `publish_source_release` | 发布源码仓库的 `v<version>`，默认开启 |
| `publish_xposed_release` | 同步 Xposed 元数据并发布 `<versionCode>-<versionName>`，默认关闭 |
| `prerelease` | 将所选发布标记为预发布；版本含 `-` 时也自动标为预发布 |

两项 publish 均关闭时，仅构建、校验和上传签名产物，可用于验证 GitHub 签名配置。Release 工作流不会因普通推送或 CI 成功而自动发布。

发版前更新版本号和 `CHANGELOG.md` 的对应 `## [版本]` 条目，完成需要的实机验证并推送到 main。输入不匹配、更新记录缺失/为空、已有相同 tag、签名失败或 APK 校验失败时，工作流终止。不要覆盖已有发行版的 tag；两处仓库发布并非事务，部分成功时关闭已成功的目标，仅重试另一处。

构建和校验通过后，签名产物先作为 Actions artifact 保存 30 天。发布时：

- 源码仓库：以触发工作流的源码 commit 创建 `v<version>`，附件为 APK、对应的 R8 mapping、`SHA256SUMS`；说明来自对应更新记录。
- Xposed 仓库：main 仅同步 `README.md`、`README.en.md`、`SCOPE`、`SOURCE_URL`、`SUMMARY`。README 中的相对链接改为指向对应源码 commit，避免引用未同步的文件。
- Xposed Release：以同步后的元数据 commit 创建 `<versionCode>-<versionName>`，例如 `4-0.2.2`；标题为 `0.2.2`，只上传与源码 Release **同一份正式 APK**，不上传 Debug、unsigned、mapping 或源码。说明包含安装要求、更新记录和源码 commit 链接。

## 本地验证

```sh
python3 -m unittest discover -s scripts/tests -v
shellcheck scripts/sync_xposed.sh
actionlint
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease
python3 scripts/release_tools.py version --expected 0.2.2
python3 scripts/release_tools.py verify-apk app/build/outputs/apk/release/app-release.apk --variant release
```

最后一项需要配置 `ANDROID_HOME` 和本地 release 签名。没有签名时，校验 `app-release-unsigned.apk` 并使用 `--variant unsigned`。APK 检查包括包名、版本、debuggable、arm64 原生库、API 102 元数据、作用域和 R8 后实际存在的模块入口类；正式 APK 还会校验签名。构建检查不能代替设备上的模块加载、DexKit 冷扫描和路线行为验证。

CI 明确安装并使用 Build Tools 36.0.0，不随 runner 预装的更高版本自动切换；本地可通过 `ANDROID_BUILD_TOOLS_VERSION` 选择其他已安装版本。签名解析兼容旧版 signer 编号和新版 SDK 范围格式，排除 Source Stamp 与公钥摘要；存在多个不同签名证书时拒绝发布。
