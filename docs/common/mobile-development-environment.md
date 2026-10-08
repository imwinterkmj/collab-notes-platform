# 安卓与 Windows App 开发环境

[文档导航](../README.md) · [客户端路线](deployment-route.md) · [本地后端环境](development-environment.md)

## 当前状态（2026-10-09）

用户已同意按 Flutter 客户端方案下载工具和必要构建依赖，先用 vivo X100s（型号 V2359A、OriginOS 5）真机，
不安装模拟器。截图没有单独显示 Android 系统版本；随后通过 USB 只读查询确认 Android 15/API 35，
没有从内核名称中的 android14 推断。iPhone 客户端后续兼容，实际构建和真机测试需要 macOS/Xcode。
用户现已授权同时搭建 Windows 桌面 App 与安卓 App，已创建共用的 client/ Flutter 项目。
使用已有 Visual Studio 2022 Community C++ 工具及 Flutter 桌面缓存，Windows x64 release 已实际编译通过；本轮不新增下载。
共用骨架的 34 项自动测试、静态分析及安卓 ARM64 debug APK 构建/签名也通过，见 [双端骨架验收](../phase-1/client-scaffold.md)。
随后已接入 [真实账号与备忘录、本机 HTTPS](../phase-1/client-api.md)，0.2.0+2 两端构建、67 项常规测试与单独 7 项 HTTPS/H2 合同验收通过。
用户反馈真实账号版其他交互基本没问题；现为 [0.3 前台自动同步](../phase-1/client-auto-sync.md)，76 项检查及独立 10 项 HTTPS 合同通过。
用户已同意 USB + 本机 HTTPS，未新增下载或开放公网；系统提醒仍未接入，新版自动同步待验收。

用户已明确同意接受 Android SDK 许可，必要组件已安装，安卓命令行编译环境验收通过。
在 Git 忽略的 `target/android-environment-check-20261008/` 中生成了独立 Flutter 示例，
完成静态检查、1 项组件测试和 ARM64 debug APK 构建，安装包签名验证通过。
用户已确认 vivo 真机安装、打开示例并点击计数正常；这是用户反馈，不是代理手机 UI 实测。
此前计数器仍不是备忘录 App；随后已创建双端备忘录演示骨架，没有实现系统提醒或部署后端。

## 工具与位置

| 组件 | 固定版本 | 位置与状态 |
| --- | --- | --- |
| Flutter SDK / Dart | Flutter 3.47.6 / Dart 3.13.5 | `D:\DevTools\flutter`，已解压、初始化并核验版本。 |
| Android Studio | Rabbit 1，2026.2.1.8；build AI-262.9437.185.2621.16467767 | `D:\DevTools\AndroidStudio`，官方 ZIP 已解压，产品元数据及可执行文件已核验；未启动界面。 |
| Android Command-line Tools | 22.0，构建 15859902 | `D:\DevTools\AndroidSDK\cmdline-tools\22.0`，已解压，复用 JDK 21 运行版本检查。 |
| Android 平台 SDK | API 36，revision 2 | `D:\DevTools\AndroidSDK\platforms\android-36`，已安装、参与实际编译。 |
| Build-Tools | 36.0.0 | `D:\DevTools\AndroidSDK\build-tools\36.0.0`，已安装；APK 构建与签名检查通过。 |
| Platform-Tools / ADB | 37.0.1 | `D:\DevTools\AndroidSDK\platform-tools`，已安装，`adb version` 核验通过。 |
| Android NDK | 28.2.13676358（r28c） | `D:\DevTools\AndroidSDK\ndk\28.2.13676358`，已安装；使用 Flutter 自带配置版本。 |
| Gradle | 9.3.1 | `D:\DevCaches\Gradle\wrapper\dists`，复用已校验的 ZIP，由 Wrapper 正常解压；版本与实际构建验证通过。 |

其他原始安装包也保留在 `D:\DevTools\Downloads`，本轮不擅自删除归档。
Flutter、Studio、命令行工具和 Gradle 对照官方 SHA-256；其余 SDK 包对照官方仓库的大小与 SHA-1。
未使用第三方镜像或关闭 TLS 校验。来源为 [Flutter 官方归档](https://docs.flutter.dev/install/archive)、
[Android Studio 官方下载](https://developer.android.com/studio)、
[Android SDK 官方清单](https://dl.google.com/android/repository/repository2-3.xml) 与
[Gradle 官方发行版](https://services.gradle.org/distributions/)。

## 已配置的路径

| 用户级设置 | 值 |
| --- | --- |
| ANDROID_HOME | `D:\DevTools\AndroidSDK` |
| GRADLE_USER_HOME | `D:\DevCaches\Gradle` |
| PUB_CACHE | `D:\DevCaches\Pub` |

用户 PATH 保留原项，追加 Flutter bin、Platform-Tools 和版本化命令行工具 bin；重新打开 CMD 后生效。
Flutter 已明确配置 Android SDK、Android Studio 和 `D:\DevTools\Java\jdk-21`，不改变现有 JAVA_HOME 或 Maven。
Android Studio 自带 JBR 用于运行 IDE，不作为替换后端 JDK 的理由；Flutter 编译已指向现有 JDK 21。
Flutter 使用统计已关闭。下载过程只在相关工具进程中使用本机现有代理，没有修改系统代理或提交代理配置。
主要 SDK 与缓存在 D 盘，少量用户设置和临时文件仍可能在 C 盘，不承诺 C 盘零占用。

## 已做与待做的验收

- `flutter --version` 成功，Flutter 3.47.6 / Dart 3.13.5 与官方归档一致。
- Android Studio 解压后的 build.txt、product-info.json 和 studio64.exe 检查通过，尚无界面验收。
- `sdkmanager --version` 返回 22.0，可使用现有 JDK 21；本轮用于普通 SDK 许可接受、ADB 安装和元数据检查。
  工具提示已弃用；同一安装包内置的 `android.exe` 入口首次启动取得官方 Android CLI 1.0.16500706，
  后续诊断加 `--no-metrics`。命令行工具、NDK 的版本同时核对 `source.properties`，不只采信目录名。
- Java 21.0.12.1 与 Maven 3.9.16 再次核验正常，后端环境未替换。
- `flutter doctor -v` 已识别 SDK 36、Build-Tools 36.0.0 和现有 JDK 21，不再报告 SDK 缺失。
  仍提示部分许可未接受：仅接受本次普通 Android SDK 的 `android-sdk-license`，未接受未使用的
  Google TV、XR、预览版、Glass、ARM 转译及 MIPS 系统镜像等 7 项额外许可。实际 APK 编译已通过，
  不为让诊断全绿而接受无关许可或安装模拟器。
  “Connected device”中的 Windows/Chrome/Edge 是桌面目标，不是已经连接 vivo 手机。
- 隔离示例 `flutter analyze --no-pub` 无问题，`flutter test --no-pub` 的 1 项组件测试通过。
- `flutter build apk --debug --target-platform android-arm64 --no-pub` 成功，Gradle 首次构建用时约 324.5 秒；
  期间下载了必要插件/依赖，使用 Android Gradle Plugin 9.1.0 和 Kotlin 插件 2.4.0，不是业务性能测试。
- 测试包为 `target/android-environment-check-20261008/build/app/outputs/flutter-apk/app-debug.apk`，
  77,246,681 字节，包名 `com.collabnotes.environment.android_environment_check`，ARM64，minSdk 24、targetSdk 36；
  `apksigner verify --verbose` 通过（v2 签名）。debug 签名只用于开发，不是上架签名。
- 构建有 SDK XML 版本兼容提示，但本次退出码为 0、APK 签名检查通过；保留提示记录，不宣称所有诊断均无警告。
- 没有运行 Java/前端业务测试、访问数据库、改表、启动隧道、改防火墙或提交/推送 Git。

此前环境验收示例和安装包只留在已忽略的 `target/`；本轮新增 client/ 源码，两端产物位于已忽略的 client/build/，未修改后端业务。
设备识别与手机计数器安装运行已通过，现两端演示骨架也已构建，下一步安卓真实接口小闭环后立即联调 Windows；
登录、备忘录接口、设备端提醒与安全联调分别验收，不把示例 APK 当作这些能力已完成。
后续业务 App 使用新的插件或版本时仍可能下载依赖，不能宣称已缓存全部依赖。

如需自行查看状态，关闭旧 CMD 后重新打开：

```cmd
flutter --version
flutter doctor -v
```

普通 SDK 许可已按用户本次明确授权接受，可查看 [Android SDK 许可](https://developer.android.com/studio/terms)。
联网安装、工具准备、手机调试、公网访问与正式部署是不同范围；工具下载授权不扩大为开放学习环境。
