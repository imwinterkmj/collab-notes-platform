# 安卓与 Windows 共用客户端骨架

[阶段导航](README.md) · [客户端代码说明](../../client/README.md) · [工具环境](../common/mobile-development-environment.md)

本文保留 0.1.0+1 离线演示骨架的历史验收记录；用户反馈基本没问题后，已接入真实账号与备忘录。
当前 0.2.0+2 的启动、安装与双端验收请使用 [本机 HTTPS 联调](client-api.md)，不要继续按本文把新版当纯离线 App。

## 本轮目标与边界（2026-10-09）

用户明确需要可安装的安卓 App 和 Windows 桌面 App，已授权开始搭建共用客户端。
本轮先生成可构建的双端项目与中文界面，用清楚标注的演示数据验证流程；不重写后端，
不把只完成骨架写成实际账号、多端同步或可靠提醒已经实现。

两个目标都位于 `client/`，共享 Flutter 页面、备忘录模型和数据访问接口。
当前仅使用 SDK 与已有依赖缓存，没有新增下载或第三方运行时插件。
没有改 Java 业务、认证/CSRF、数据库、Compose、用户记录或数据卷，也没有开放隧道或提交/推送 Git。

## 已实现的演示流程

- 欢迎页说明离线、内存、未接入系统提醒；只提供“进入演示”，不索取账号或密码。
- 备忘录按未完成优先、更新时间/ID 倒序排列；支持标题/正文搜索与完成状态筛选。
- 点击记录打开编辑弹窗，标题、正文、一次性提醒时间一起“保存”；新建不必先保存再设时间。
- 标题按 1～120 个 Unicode 码点且非全空白，正文最多 10000；SET 必须是未来时间。
- KEEP 保留原演示时间，CANCEL 清除；完成取消演示时间，恢复未完成不恢复旧计划。
- 保存失败保留文字与时间，不部分改写记录；放弃未保存草稿需要确认，保存中禁用重复操作。
- 删除二次确认，演示回收站只保留最近 30 条（不是 30 天）；恢复产生新 ID，不恢复旧提醒。
- 手机窄窗使用底部导航，电脑宽窗使用侧边导航；设置入口在右上角齿轮。

上述行为是内存演示实现，不证明服务端事务、并发、离线同步或账号隔离。
已有后端的真实 MySQL 验收是此前独立记录，不是本轮 Flutter 测试的内容。

## 当前产物

| 目标 | 产物与状态 |
| --- | --- |
| 安卓 ARM64 | `client/build/app/outputs/flutter-apk/app-debug.apk`，78,858,265 字节；版本 0.1.0+1，minSdk 24、targetSdk 36，APK v2 签名核验通过。 |
| Windows x64 | `client/build/windows/x64/runner/Release/`，12 个文件合计 29,123,382 字节，含 `collab_notes_client.exe`、DLL 和 data；release 编译通过。 |

安卓是开发用 debug 签名，不是上架包。Windows 当前是可运行的文件夹，不是 MSI/安装向导、
签名分发或自动更新已经完成；**不能只复制 exe**，必须保留同目录 DLL 和 data。
先在当前开发电脑验收；其他电脑的 VC++ 运行库与正式打包后续再确认，不自动下载安装。
图标暂用 Flutter 模板占位，界面与系统标签明确显示“备忘录 · 演示”。

安卓使用 Gradle `--offline --no-daemon` 构建，Windows 使用 `flutter build windows --release --no-pub`。
首次 Windows 编译的中文代码页警告已通过 MSVC `/utf-8` 修复并复编通过。
Android 模板仍有 AGP/Kotlin 旧 DSL 弃用与 SDK XML 兼容警告，当前退出码为 0；
不为消除警告下载新 SDK 或接受无关许可，不宣称构建日志完全无警告。

## 现在怎么手动验收

这一轮不需要启动 Docker、MySQL 或 Spring Boot，也不用逐个调用接口。

### Windows

在 CMD 从仓库根目录打开：

```cmd
cd /d D:\1A-project\collab-notes-platform
start "" "client\build\windows\x64\runner\Release\collab_notes_client.exe"
```

也可直接在资源管理器中打开该 exe；不要移动它并丢掉旁边的 DLL/data。
本轮没有自动启动 Windows 窗口或取得真实桌面交互截图。

### 安卓

手机连接 USB 并允许调试后，在仓库根目录的 CMD 执行：

```cmd
"D:\DevTools\AndroidSDK\platform-tools\adb.exe" install -r "client\build\app\outputs\flutter-apk\app-debug.apk"
```

手机上打开“备忘录 · 演示”。它与此前环境验收计数器是不同包，不需要卸载旧示例。
若系统要求确认安装，请自行查看并确认；不代替用户修改手机权限。
安装完成后可拔 USB，此版本不联网；这不代表后续真实数据同步不需要可达后端。
本轮未自动安装到手机，之前计数器的验收不能代替本次备忘录界面验收。

### 两端各检查一次

1. 进入演示；未完成排在已完成前，齿轮和退出入口可见。
2. 新建标题/正文，打开一次性提醒，选“1 小时后”或日期时间，直接点底部“保存”。
3. 点开记录编辑；全空白标题保存失败，正文与时间仍保留，修正后能保存。
4. 标记完成、删除、切回收站并恢复；恢复不带旧提醒，取消删除不会移除记录。
5. 电脑缩放窗口，手机打开键盘编辑；底栏操作能点到，正文可滚动。

退出演示或重开程序后，数据恢复为三条演示种子，这是本轮明确约定，不是数据库丢失。
不要输入需要长期保存的私人内容；现在的提醒时间到点不会响铃，设置中的两个开关暂时禁用。

## 自动检查与视觉检查

- `flutter analyze --no-pub` 无问题。
- `flutter test --no-pub` 共 34 项通过：19 项内存领域/仓库测试、15 项入口与组件交互测试。
  覆盖 Unicode、UTC、未来时间、统一保存失败无部分替换、排序、完成/回收站、退出重置、返回草稿保护、
  保存中禁用重复点击，以及 320×640 / 1.6 倍字体 / 230 逻辑像素键盘和 1280×800 窗口。
- 另运行 4 项组件预览生成，使用本机已有中文字体与已缓存的 Material 图标字体，
  人工查看手机主页/编辑和电脑主页渲染图；图片只放根目录已忽略的 `target/client-previews/`。

组件测试与预览使用 Flutter 测试环境，不是 Windows 原生交互或 vivo 真机截图，不证明系统通知、
音频、IME 实际行为或锁屏运行。4 项预览生成不是已有基准图的视觉回归验证。
未重复运行 Java/网页测试，也未访问学习 MySQL 数据。

如要再生成可选组件预览，进入客户端目录后执行（只使用本机已有字体）：

```cmd
cd /d D:\1A-project\collab-notes-platform\client
flutter test --no-pub --update-goldens --dart-define=PREVIEW_FONT=C:/Windows/Fonts/msyh.ttc tool/preview_test.dart
```

预览不是运行应用所必需的流程；不会把 Windows 字体复制进项目或安装包。
响应式依据见 [Flutter 窗口适配](https://docs.flutter.dev/ui/adaptive-responsive/general)，
测试层次见 [Flutter 测试说明](https://docs.flutter.dev/testing/overview)。

## 紧接着的实施顺序

1. 复用现有登录/备忘录/统一保存/回收站 API，新增 HTTP 数据访问实现，不改写 Java 接口。
2. 在传输真实账号密码前准备隔离测试数据与安全 HTTPS 入口；保留 Session、Cookie、CSRF 与用户隔离，
   不直接公开弱密码学习账号，不为手机关掉 CSRF/CORS 约束。联网入口与新依赖下载另行确认。
3. 安卓先跑通真实账号与保存闭环，再用同一客户端代码立即联调 Windows，避免形成两套业务。
4. 分别补 Android 系统通知/设备定时、Windows 托盘留后台/系统通知/声音，测试权限与改期取消。
5. 再做正式安装器、签名和启动恢复；iPhone 后续在 macOS/Xcode 环境兼容验证。

当前 Windows 点击窗口关闭会退出进程，没有托盘；安卓没有定时器/通知权限流程。
后台、关页、锁屏提醒继续保留在规划中，不算本轮验收通过；休眠、关机和强行停止的边界要单独说明。
