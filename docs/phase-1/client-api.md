# 安卓 / Windows：接入真实账号与备忘录

[阶段导航](README.md) · [客户端](../../client/README.md) · [上一版离线骨架](client-scaffold.md)

本文保留 0.2 版接口接入与 HTTPS 配置记录；0.3 已增加 [前台自动同步](client-auto-sync.md)，当前验收优先看新文档。

## 本轮结果与边界（0.2 历史记录）

两端共用客户端当时为 **0.2.0+2，备忘录 · 联调**。注册、登录、退出、分页列表、完整详情、
内容与提醒统一保存、完成/恢复未完成、删除与最近 30 条回收站已接入现有 Spring Boot API。
后端业务、数据库表和认证规则没有重写；Session、Cookie、CSRF 和本人归属限制继续保留。

登录后的记录存后端数据库；重开 App 需要重新登录，不会丢失已成功保存的记录。
两端登录同一账号，通过右上角刷新读取另一端修改。**不是自动实时同步，也不是离线编辑。**
当前搜索/筛选只覆盖已加载标题：每页 20 条，点击加载更多，每次页面会话最多 1000 条；
列表显示已加载数量，不冒充总数，摘要不含正文，点击后才读取完整正文与提醒。

仍保留“仅查看离线演示”：不登录、不连接后端，只在内存演示，退出即重置。
这与真实账号的数据完全分开。设置里的声音/系统通知仍不可用；铃声和图片导入尚未迁移。
本轮可以保存服务器提醒任务，但 **没有安卓锁屏提示或 Windows 托盘后台提醒**，
关闭 Windows 主窗口仍退出进程。`FIRED` 只代表服务器站内通知入库，不代表 App 已展示。

## 联调方式：USB + 本机 HTTPS

用户已明确同意此方式。电脑后端只监听 `127.0.0.1:8443`；安卓通过 USB 的 ADB reverse
把手机此端口转到电脑此端口。Windows 直接连接同一个本机 HTTPS 地址。
没有启动 cloudflared、公开公网入口、修改防火墙或向 Windows/手机安装系统信任证书。
ADB 功能依据见 [Android 官方 ADB 文档](https://developer.android.com/tools/adb)。

已有 JDK 生成一年有效的本机开发证书，SAN 包含 localhost 和 127.0.0.1。
客户端仅对回环 HTTPS 地址额外信任这一份公开证书，仍验证证书有效期和主机名，
没有 `badCertificateCallback` 或“忽略证书错误”。DER 公钥证书在 Dart 信任入口转换为 PEM，
格式依据见 [Dart SecurityContext](https://api.dart.dev/dart-io/SecurityContext/setTrustedCertificates.html)。
安卓新增正常 INTERNET 权限，没有放开明文 HTTP 或任意证书信任。

本机材料位于 Git 已忽略的 `target/local-https/`：

| 文件 | 用途 |
| --- | --- |
| `dev-server.p12` | 服务端私钥；禁止分享、提交。 |
| `server-local.json`、`server-env.cmd` | 含密钥库密码的本机配置；禁止截图或提交。 |
| `dev-server.cer` | 公开证书，不含私钥。 |
| `client-defines.json` | 本机地址与公开证书，供客户端构建使用。 |

这些文件已经准备好，当前电脑不必再生成。不要运行 `mvn clean` 后仍期待证书存在：
它会清理 `target/`；如果材料丢失或证书换新，必须重新准备并重新构建两个客户端，
旧包不会自动信任新证书。生成器拒绝覆盖完整材料，也不会自动删除不完整材料。
不要把 `target/local-https/` 强制添加到 Git。

换电脑开发时，使用现有 JDK 生成自己的材料（不是下载命令），然后本机重新构建：

```cmd
cd /d D:\1A-project\collab-notes-platform\client
dart tool/prepare_local_https.dart
```

当前 APK/EXE 只面向这台电脑的开发证书；不是公网正式发布包，不适用于另一台电脑的证书。

## 手动验收：只做下面几步

先关闭旧版 Windows 客户端；如果原来的 8080 后端仍运行，在它的 CMD 按 Ctrl+C 停止，
避免同时启动两个提醒扫描进程。手机保持已授权 USB 调试连接。无需下载、重建表或重建 MySQL 卷。

### 1. 新开 CMD，启动本机 HTTPS 后端

```cmd
cd /d D:\1A-project\collab-notes-platform
docker compose up -d --pull never mysql
for /f "usebackq tokens=1,* delims==" %A in (".env") do @set "%A=%B"
call "target\local-https\server-env.cmd"
set "DEBUG=false"
mvn --offline spring-boot:run
```

看到 `Tomcat started on port 8443 (https)` 后保留此窗口。
这里是终端中执行的 `%A`；如自行写进批处理须用 `%%A`。
本次手动启动连接原有本机 MySQL，建议在 App 中注册一个专用验收账号、使用独立强密码；
不需要删除现有学习账号或数据，不向公网公开它们。

### 2. 另开 CMD，安装新版手机包并打开电脑客户端

```cmd
cd /d D:\1A-project\collab-notes-platform
"D:\DevTools\AndroidSDK\platform-tools\adb.exe" reverse tcp:8443 tcp:8443
"D:\DevTools\AndroidSDK\platform-tools\adb.exe" install -r "client\build\app\outputs\flutter-apk\app-debug.apk"
start "" "client\build\windows\x64\runner\Release\collab_notes_client.exe"
```

手机打开“备忘录 · 联调”，不用 vivo/夸克浏览器。`install -r` 更新原演示 App，不卸载它。
Windows 要保留 Release 目录的 DLL 和 data，不要只复制 exe。
本版手机真实联网需要 USB 连接、电脑/MySQL/后端持续运行；拔线后无法加载或提交，
这不等于未来正式 App 必须一直插线。重新连接后再执行 reverse 即可。

### 3. 用界面统一检查

1. 手机注册测试账号，再登录；电脑登录同一账号。注册成功不自动登录。
2. 手机新建标题、正文和未来提醒时间，一次点击保存；电脑点右上角刷新，应看到该记录。
3. 电脑打开记录改正文/改期并保存；手机刷新后点开，应看到完整正文和新时间。
4. 关闭并重开一个客户端，重新登录，记录仍在；后端重启后亦需重新登录，不重新注册。
5. 标记完成后待执行提醒取消；恢复未完成不复活旧提醒。删除后到回收站恢复，内容保留、旧提醒不恢复。
6. 过去时间保存失败，文字与时间草稿保留；退出后换另一账号，不能看到原账号记录。

提醒到期、铃声、手机锁屏、电脑关窗口后台提示 **不属于这一轮 App 验收**，下一步单独接入。
现有网页功能仍保留；切换至本机自签 HTTPS 后浏览器不会自动信任证书，
本轮用 App 验收，不要求绕过浏览器证书警告。恢复原 8080 模式时，先停止 HTTPS 后端，
关闭这个含 SSL 环境变量的 CMD，在新的 CMD 按原网页文档启动。

## 安全与失败处理

- Cookie 和 CSRF 只放当前进程内存，不落磁盘，不保存密码；重开客户端重新登录。
- 登录后采用新会话并重新取 CSRF；只有过滤器明确拒绝的 `CSRF_INVALID` 安全重试一次。
- 超时、断网或 5xx 不自动重放写入，也不把连接中断解释为“肯定没保存”。
  编辑草稿保留，须先核对服务端结果再明确允许继续；新建核对仅列第一页，不是 POST 幂等保证。
- 会话过期时可在编辑上方重新登录原账号，草稿不丢、不自动提交；不能借此切换账号。
- 退出断网仍清除本机凭据，但明确提示服务端退出未确认，旧会话按原超时失效。
- 不跟随 API 重定向，不转发凭据到其他来源；错误只显示安全文案，不输出响应原文或密码。
- 删除前验证回收站接口，避免旧硬删除后端与新客户端混用；恢复不重建旧通知/提醒。

数据库事务并不提供多端编辑冲突检测：仍然最后写入覆盖。OFFSET 翻页期间有并发修改时
可能重复/遗漏，客户端按 ID 合并只能消除已加载重复，不能提供一致快照。

## 自动验证记录（2026-10-09）

- Flutter 静态分析无问题，常规 67 项测试通过：领域/演示、Cookie/CSRF、HTTP 仓库、真实账号页面、
  原账号重新登录、草稿保留、分页去重与窄屏键盘布局。逻辑/组件替身不冒充真机联网。
- 单独 7 项真实 HTTPS 合同测试通过：实际 Dart IO 请求 → 原 Spring Security/Controller/Repository →
  临时 H2，覆盖注册登录、SET/KEEP/CANCEL、分页与 Unicode、两个会话互读、客户端重建、恢复、隔离与拒绝不可信证书。
  不是 MySQL 合同测试、网络容量测试或 vivo 真机报告。
- Java 普通离线回归共 295 项：284 通过，11 默认跳过（10 项 MySQL、1 项本机 HTTPS 测试入口）。
  HTTPS 单独入口已另行通过，内含上述 7 项；不把跳过项算作普通回归通过。
- 6 类组件渲染预览生成通过，已查看登录、手机/电脑真实账号布局；仅使用替身账号与本机已有字体，不是真机截图。
- 安卓 ARM64 debug APK 与 v2 签名核验通过；Windows x64 release 目录构建通过。
  版本 0.2.0+2，minSdk 24/targetSdk 36。最终 APK 为 95,650,636 字节（约 91 MiB），含完整调试 kernel；
  Windows 目录 12 个文件，共 29,532,782 字节（约 28 MiB）。这是测试包体，不是性能指标。
  同一项目两端构建不并行，避免共享 Flutter 资产目录竞争；已最后单独重建安卓并核验应用入口而非测试入口。
  构建仍有原模板 AGP/Kotlin 与 SDK XML 警告，未为消除警告下载新版本。

本轮没有新下载、后端业务/表/学习数据变更、Git 提交或推送；未自动安装这个新版 APK 或启动原生窗口。
用户随后反馈其他交互基本没问题，要求取消手动刷新；新版自动同步须另行验收，不扩大旧反馈。

需要维护测试时，从 CMD 执行（不是每次手动验收都要执行）：

```cmd
cd /d D:\1A-project\collab-notes-platform\client
flutter analyze --no-pub
flutter test --no-pub
cd /d D:\1A-project\collab-notes-platform
set "FLUTTER_SDK=D:\DevTools\flutter"
set "RUN_CLIENT_HTTP_TESTS=true"
set "DEBUG=false"
mvn --offline -Dtest=ClientHttpContractTests test
set "RUN_CLIENT_HTTP_TESTS="
```

HTTPS 测试使用已有 `target/local-https/` 材料，强制临时 H2、随机回环 HTTPS 端口并关闭自身扫描，
不会读取 `.env` 或连接学习 MySQL。测试日志仅写入已忽略的 `target/client-contract-output.log`，
测试进程结束后临时数据库消失；不得为测试改生产数据源或关闭 CSRF。
