# 备忘录客户端（安卓 / Windows）

[项目进度](../PROGRESS.md) · [前台自动同步验收](../docs/phase-1/client-auto-sync.md) · [本机 HTTPS](../docs/phase-1/client-api.md) · [开发环境](../docs/common/mobile-development-environment.md)

同一个 Flutter 项目包含安卓和 Windows 桌面目标，复用 Dart 页面与模型，不复制或改写 Spring Boot 后端。

## 当前范围

当前为 **0.3.0+3，本机 HTTPS 自动同步版**：

- 真实注册、登录和退出，保留 Session/Cookie/CSRF；登录凭据只在内存，不保存密码。
- 本人分页列表，已加载标题搜索、完成筛选，未完成优先；每页 20 条、最多加载 1000 条。
- 点击摘要获取完整正文/提醒；新建即可选时间，通过一个“保存”提交内容与提醒。
- 完成/恢复未完成、二次确认删除、最近 30 条回收站；恢复不恢复旧提醒。
- 手机底部导航、电脑宽窗侧边导航、齿轮内全局设置入口。
- 输入失败保留草稿，关闭未保存编辑先确认；会话过期可重新登录原账号继续。
- 同一账号两端前台自动同步；断线退避重连，编辑中只提示变化，不替换草稿，继续保存先确认。

真实账号需要本机 MySQL 和 HTTPS 后端；手机当前使用 USB 转发，不开放公网或修改防火墙。
提醒任务已能保存到服务器，但 App 声音/系统通知、手机锁屏、Windows 托盘、素材导入仍未接入。
设置开关暂不可用，Windows 关窗口仍退出；后台推送、离线编辑、并发冲突合并尚未实现。
可选“仅查看离线演示”仍只在内存，不需要 Docker/后端，退出演示或重开程序重置。

## 目录职责

| 目录 | 用途 |
| --- | --- |
| `lib/domain/` | 备忘录、草稿、提醒动作、输入规则。 |
| `lib/data/` | 数据访问、会话/CSRF；AutoSyncController 管理前台长轮询/重连。 |
| `lib/ui/` | 手机与电脑共用的页面、编辑弹窗。 |
| `android/`、`windows/` | 各平台启动与构建适配，不是两套业务代码。 |
| `test/` | 领域/演示、API 会话、HTTP 数据映射、账号与页面交互测试。 |
| `tool/preview_test.dart` | 可选组件渲染预览，不是真机截图。 |
| `tool/prepare_local_https.dart` | 用现有 JDK 生成被忽略的本机开发证书/配置，不安装系统证书或开放网络。 |
| `tool/live_api_contract_test.dart` | 由 Java 测试启动的真实 HTTPS 合同验收；后端用隔离 H2，不连接学习 MySQL。 |

## 测试

从仓库根目录的 CMD 进入客户端：

```cmd
cd /d D:\1A-project\collab-notes-platform\client
flutter pub get --offline
flutter analyze --no-pub
flutter test --no-pub
```

此前 0.2 版常规 67 项测试和单独 7 项 HTTPS 合同验收通过，用户反馈其他交互基本没问题。
0.3 常规 76 项通过，静态分析无问题，独立 HTTPS 合同扩至 10 项通过；新版双端实际验收见自动同步文档。
当前缓存已够本版构建。换电脑若离线解析失败，先确认缺少的依赖与下载范围，不自动联网补齐。
版本锁定文件 `pubspec.lock` 提交源码；SDK 路径、缓存、构建产物及签名私钥不提交。

安装/打开新版、重启后端与双端界面验收，见 [前台自动同步验收](../docs/phase-1/client-auto-sync.md)。
本机证书材料在被忽略的 `target/local-https/`；私钥、密码配置不可分享，`mvn clean` 会清理它们。
Windows 完整目录不是正式安装器，安卓 debug APK 不是上架包；公开本机证书嵌入包，不包含私钥或数据库密码。
