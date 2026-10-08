# 安卓 / Windows：前台自动同步

[阶段导航](README.md) · [客户端](../../client/README.md) · [本机 HTTPS 与启动配置](client-api.md)

## 解决的问题

用户反馈 0.2 版其他交互基本正常，但另一端修改必须手动刷新，不适合日常使用。
0.3.0+3 改为同一账号前台自动同步：新建、保存内容/提醒、完成、删除和回收站恢复后，另一端自动更新列表与回收站。
仍复用现有接口、Session/Cookie/CSRF、事务和本机 HTTPS，没有新增依赖、中间件、数据库表或公网入口。
网页编辑成功也产生变化提示；网页列表本身未改为自动同步。

## 怎样同步

客户端向认证后的 `GET /api/notes/changes?cursor=...` 发起长轮询。
后端用 `DeferredResult` 异步保留请求，不让 Servlet 工作线程一直阻塞等待：

1. 首次连接、重连或游标失效，立即返回当前游标，客户端读取列表与回收站。
2. 游标一致时等待；本账号写事务提交成功即唤醒连接，只返回游标和 changed，不推送正文。
3. 客户端自动读取先前已加载的页和回收站，保留搜索、筛选与导航；编辑仍按需读取完整内容。
4. 无变化时约 8 秒返回心跳，再继续等待；不是“每 8 秒才发现修改”。无变化心跳不查数据库或刷新列表。

同一保存事务的通知钩子合并，回滚不发提示。普通写入不排在长轮询后面。
请求间隔至少 250 毫秒，断线按 1/2/4/8/16/30 秒退避；重连核对数据，不重放失败写入。
后端重启后游标换新，需要重新登录原账号；客户端重开也不持久化密码或会话。

依据：[Spring MVC 异步请求](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)、
[提交后事务钩子](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)。

## 草稿、后台与可靠性边界

- 编辑打开期间不重载字段。有变化只提示，标题、正文和时间草稿保留；已有记录继续保存前再确认。
  提示按账号判断，其他记录变化也可能触发，不是单条版本冲突检测。仍最后写入覆盖，不合并草稿。
- 手机进入后台或 Windows 隐藏时停止建立新请求，恢复前台核对。
  不叠加在途请求：恢复可能先等原请求结束（服务端约 8 秒，网络超时最多 15 秒），不是锁屏推送。
- 退出与重新登录隔离旧响应；会话失效暂停同步，须用户重新登录，不自动提交草稿。
- 前台自动请求会延续服务端会话；30 分钟空闲按请求活动判断，不按用户最后点击判断。
- 单实例内存游标只作失效提示，不是消息历史、持久化队列或设备回执。数据库是事实来源；丢提示后重连可核对。
  已接入应用写路径；直接执行 SQL 不产生提示，需手动刷新或重连。
- 最多 256 条等待，同账号最多 4 条，账号缓存最多 512 个，空闲 5 分钟惰性清理。
  超限返回 429/503 后退避；这是资源上限，不是压测容量成绩。多实例共享会话/变化发布仍未实现。
- 列表仍每页 20 条，最多加载 1000 条；OFFSET 不提供并发快照。自动同步不是离线编辑、手机通知或 Windows 托盘提醒。

## 这次怎么验收

关闭旧 Windows 客户端，在运行后端的 CMD 按 Ctrl+C；不要同时启动两个后端调度进程。
仍在上次加载过数据库和 HTTPS 环境变量的 CMD 时，重新执行：

```cmd
cd /d D:\1A-project\collab-notes-platform
mvn --offline spring-boot:run
```

新 CMD 或环境变量丢失时，按 [HTTPS 启动步骤](client-api.md) 完整启动。
不重建表、卷或证书，不运行 `mvn clean`。Docker/MySQL 保持运行，手机保持 USB 连接。

另开 CMD 更新手机包并打开新版电脑客户端：

```cmd
cd /d D:\1A-project\collab-notes-platform
"D:\DevTools\AndroidSDK\platform-tools\adb.exe" reverse tcp:8443 tcp:8443
"D:\DevTools\AndroidSDK\platform-tools\adb.exe" install -r "client\build\app\outputs\flutter-apk\app-debug.apk"
start "" "client\build\windows\x64\runner\Release\collab_notes_client.exe"
```

手机打开“备忘录 · 联调”，两端登录同一验收账号并保持前台；Windows 保留完整 Release 目录。

1. 显示“自动同步已连接”。手机新建保存，电脑不点刷新应自动出现。
2. 电脑改标题/正文/时间，手机列表自动更新；点开读取新内容与时间。
3. 完成、删除、恢复后另一端状态与回收站自动更新；恢复不重启旧提醒。
4. 一端保留编辑草稿，另一端修改保存：编辑端提示有变化，不改草稿；继续保存先确认。
5. 拔 USB 后手机保留已有数据并提示重连；接回并重新执行 reverse，应补查变化。
6. 后端重启后重新登录原账号，不重新注册，记录仍在。后台/锁屏提醒不属于本轮。

## 自动验证

Java 普通离线回归 300 项：289 通过、11 默认跳过（10 MySQL、1 独立 HTTPS 入口）。
单独 10 项真实 Dart IO HTTPS → Spring Security/事务/JDBC → 临时 H2 合同通过，覆盖提交后唤醒、
保存/完成/删除/恢复、失败回滚不发信号、无变化心跳、账号隔离与认证。不是 MySQL 或真机压测。
客户端常规 76 项通过，静态分析无问题；覆盖自动更新、搜索筛选保留、编辑提示/草稿、单连接、退避、后台暂停和退出隔离。
替身不等于 vivo/Windows 原生交互报告；新版实际效果待用户确认。

0.3.0+3 安卓 ARM64 debug APK 与 Windows x64 release 已离线构建通过；APK 签名、版本及正常应用入口核验通过。
Windows 完整目录 12 个文件、29,549,166 字节（约 28 MiB），不是正式安装器；安卓调试包 78,920,649 字节（约 75 MiB），不是上架包。
两端构建顺序执行，最后独立重建安卓，避免测试或 Windows 构建竞争 Flutter 资源。原模板构建警告保留，未另行下载工具。

维护测试命令见 [客户端测试](../../client/README.md#测试) 和 [HTTPS 合同验收](client-api.md)。
没有访问或修改学习 MySQL、下载新依赖、自动安装新版、提交或推送 Git。
