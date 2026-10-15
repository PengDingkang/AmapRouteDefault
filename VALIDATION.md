# 验证记录

## 0.1.0 基础行为

2026-10-15，实机 Android 16，高德 15.10.1.2063，LSPosed IT 2.1.1-it (7847)、API 102。

| 检查 | 结果 |
| --- | --- |
| `assembleDebug` | 通过 |
| `lintDebug` | 通过，0 错误；存在目标 SDK、备份规则、文本国际化建议 |
| APK 签名验证 | `apksigner verify` 通过，使用开发调试签名 |
| 模块安装、作用域与共享设置 | 设置页能绑定 LSPosed 服务，作用域包含高德；覆盖安装后默认公交仍被保存 |
| 首页“路线”入口 | 默认显示公共交通页 |
| 选择目的地 | 正常显示公交换乘方案 |
| 普通搜索历史的“路线”入口 | 直接显示公共交通方案 |
| 路线页内手动切到驾车 | 正常显示驾车方案和“开始导航”按钮 |
| 手动驾车后退出并重新进入普通路线页 | 恢复默认公共交通 |
| 高德运行中将默认方式改为步行 | 新进入的普通路线显示步行方案，无需重启高德 |
| 默认公交时使用首页专门的“驾车”入口 | 保持驾车页 |

仅测试了路线规划，未启动实际导航或叫车。第三方带显式方式的 URI 根据参数分支保留，但未逐项实测。高德其他版本未验证。0.1.0 对其他版本不覆盖默认方式；0.2.0 改为按特征尝试匹配。


## 0.2.0 DexKit 自动定位

- `assembleDebug testDebugUnitTest lintDebug` 通过；7 个单元测试通过，Lint 0 错误、4 个建议（SDK/Gradle 更新、备份规则、国际化）。
- 测试夹具改变类名、方法名、请求字段名和枚举数值访问器名称；验证仍可识别结构。验证多候选、多枚举字段、枚举数值变化时拒绝匹配，以及显式方式保留、参数复制和 base/split APK 缓存标识变化。
- 实机不指定混淆名称，成功定位 `p91.a(s91, IPredictResultCallback)` 和字段 `a`。DexKit 2.3.0 两次完整冷扫描约 136 / 132 ms。
- 实机确认后续启动命中缓存；故意损坏模块自己的定位缓存后，成功重新扫描并重建缓存。
- 重新验证首页普通路线默认公交、正常公交查询、手动切换驾车。
- 开发时复用了本机 DexKit 2.2.0，扫描关闭后触发原生线程池崩溃；改用上游 2.3.0 修复版本后冷扫描及后续路线操作未复现。此问题不影响保留的 0.1.0 APK。

真实高德安装包仍仅实测 15.10.1.2063。重命名夹具属于结构兼容性测试，不能视为其他真实高德版本的实测结果。未升级或降级手机上的高德。


## 0.2.1 Release 构建

- 接入本机未跟踪的 release 签名配置，版本为 0.2.1（versionCode 3），路线适配逻辑未修改。
- `assembleRelease testDebugUnitTest lintRelease` 通过：通用逻辑的 7 个 Debug 单元测试结果有效，Release Lint 0 错误、3 个建议。当前 AGP 配置未生成 `testReleaseUnitTest` 任务。
- `apksigner verify` 通过；确认 APK 不含 debuggable 标记，现代 Xposed 入口及 arm64 DexKit 库均保留。
- 产物：`dist/AmapRouteDefault-0.2.1-release.apk`。首次由原 debug 签名迁移到本机 release 签名，需替换原模块并恢复设置。


## 0.2.2 R8 与 arm64 打包

- Release 启用 R8、资源压缩及 Android 默认优化规则；保留 LSPosed 动态入口，JNI 使用 DexKit 自带的 consumer rules。所有构建仅打包 arm64-v8a。
- `assembleRelease testDebugUnitTest lintRelease` 通过；通用逻辑的 7 个单元测试结果有效，Release Lint 0 错误、4 个建议，其中 x86_64/ChromeOS 建议属于明确不支持的架构。
- 相比 0.2.1 release，APK 从 4,454,890 字节缩小至 514,135 字节，减少约 88.5%。ZIP 检查仅含 `lib/arm64-v8a/libdexkit.so`，DEX 为 83,268 字节。
- Release 签名验证通过，与已安装的 0.2.1 release 使用相同证书，已覆盖安装到原设备。设置页连接 LSPosed 服务正常，默认公共交通设置保留。
- 清除本模块自己的定位缓存后，压缩包实机 DexKit 冷扫描成功（约 155 ms）；LSPosed 入口和 JNI 库工作正常，普通路线页默认显示公共交通；公交方案查询和页内手动切换驾车验证通过；再次重启高德确认命中缓存。
- 产物：`dist/AmapRouteDefault-0.2.2-release.apk`；混淆映射另存为 `dist/AmapRouteDefault-0.2.2-mapping.txt`。


## CI 与发布自动化

2026-10-16：接入参考 HyperWallpaperMonet 的普通 CI、手动签名 Release 和可选 Xposed 仓库同步。

- 版本读取集中到 `gradle.properties`，当前仍为 0.2.2 / 4，未修改路线 Hook 或定位规则。
- `python3 -m unittest discover -s scripts/tests -v` 的 9 项测试通过：版本与更新记录精确匹配、签名不符拒绝、ABI/元数据/R8 入口验证，以及在本地 bare Git 仓库模拟空仓库同步、重复运行和已有文件保留。
- actionlint 1.7.12、ShellCheck、文档链接与 `git diff --check` 通过。
- 使用空签名属性模拟无 Secrets 的 CI，`assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease` 通过；真实 Debug 和 unsigned R8 Release APK 校验通过。Debug 约 3.87 MB、unsigned Release 为 510,039 字节。
- 已有 0.2.2 正式签名 APK 通过发布校验脚本；该脚本确认 arm64-only、包名/版本、API 102 作用域、实际保留的入口类和签名证书。
- 本地测试不等于 GitHub Secrets 权限验证或真实公开发版；Release 提供两项 publish 均关闭的签名构建模式，维护者可在 Actions 中验证配置。
- 首次 GitHub CI 的编译、Java 测试、Lint 和工作流检查通过；APK 校验暴露出 runner 预装新版 apksigner 的 signer 输出格式差异。固定使用明确安装的 Build Tools 36.0.0，并兼容编号/SDK 范围两种格式，增加来源戳排除、多证书拒绝和工具选择回归测试后，发布工具测试共 12 项通过。


## 0.2.3 设置页自适应主题

2026-10-16，沿用上述 Android 16 / LSPosed IT 2.1.1-it (7847) 实机环境。

- 设置页使用原生 Material 深浅色主题和 `values-night` 资源，背景及单选按钮引用 Android 14 起提供的系统动态颜色；未增加依赖。
- `assembleRelease testDebugUnitTest lintRelease` 通过，7 个测试通过，Lint 0 错误、4 个既有建议。Release APK 通过包名、版本、arm64-only、Xposed 入口及签名校验，与 0.2.2 使用相同证书。
- 签名 APK 为 514,551 字节，比 0.2.2 增加 416 字节；保留 R8 压缩和 arm64-only 打包。
- 已覆盖安装 0.2.3（versionCode 5）。设置页正常连接 LSPosed，原有“公共交通”选项保留且可用。
- 页面保持打开时分别切换系统浅色、深色，确认 Activity 自动重建，背景、文字和单选按钮同步变化；截图及窗口状态确认浅色使用深色系统栏图标，深色使用浅色图标。
- 测试后已恢复设备原有 `auto` 深浅色模式。截图、界面树和窗口状态仅保存在仓库外临时目录。
- 本次仅修改设置页主题资源，未修改路线 Hook、定位规则及设置存储；未重复 DexKit 冷扫描和完整路线回归。其他 Android 版本及更换壁纸后的配色变化未实机验证。
- 产物：`dist/AmapRouteDefault-0.2.3-release.apk`；混淆映射：`dist/AmapRouteDefault-0.2.3-mapping.txt`。


## CI 注解维护

2026-10-16，检查 GitHub Actions 运行 `38770049396` 的三条 notice：两个 job 的 `ubuntu-latest` 迁移提醒，以及 Gradle 9.3.1 过时提醒。

- CI 两个 job 及手动 Release 均固定为 `ubuntu-24.04`，继续使用已验证的系统版本。
- 使用官方 Wrapper 任务升级至 Gradle 9.8.1，同步更新启动脚本、Wrapper JAR 和发行包 SHA-256；JAR 指纹与官方公布值一致。
- 新 Wrapper 下模拟无签名 Secrets 的 CI：`assembleDebug assembleRelease testDebugUnitTest lintDebug lintRelease` 通过；7 个 Java 测试通过，两种构建的 Lint 均为 0 错误、4 个既有建议。
- Debug 和 unsigned Release 均通过 APK 元数据、ABI、Xposed 入口及对应签名状态校验；12 项发布工具测试、ShellCheck、actionlint 和 `git diff --check` 通过。
- `--warning-mode all` 确认现有 AGP 9.1.1 内部仍使用两个已弃用的 Gradle API；这是后续 Gradle 10/11 的迁移事项，本次不屏蔽这些日志。当前 Gradle 9.8.1 构建正常。
- 本次仅调整构建环境，应用版本保持 0.2.3 / 5，未重新安装模块或改变设备设置。
