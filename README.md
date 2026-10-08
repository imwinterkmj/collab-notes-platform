# collab-notes-platform

这是一个面向电脑和手机使用的备忘录与定时提醒项目，重点学习多端数据一致性、可靠提醒
和高峰任务处理。项目从简单的 Spring Boot 单体应用开始，只有在发现并量化真实问题后，
才引入下一项技术。

典型场景：在电脑上记录“周五下午面试”，设置提前半小时提醒；手机能够查看和修改同一条
备忘录，到时间收到提醒，完成状态在不同设备间同步。

`collab-notes-platform` 作为现有工程标识继续使用，当前保留仓库名、Java 包名和数据库名。
业务规划以备忘录和提醒为主线，多人工作区、共享知识库和协作权限不属于当前核心范围。

## 目标功能与实现状态

阶段 0 已完成；阶段 1 本地网页 MVP 功能验收已获用户确认，待补工程收尾：

- 注册、登录、当前用户查询和退出（已实现）；备忘录创建/详情已验证用户隔离；
- 备忘录创建、本人详情、分页列表、编辑、完成状态与删除（已实现）；
- 一次性提醒的设置、改期、取消与到期生成站内通知（已实现）；
- 电脑和手机访问同一账号的数据，逐步加入并发冲突处理；
- 站内通知查看与已读、响应式网页、常驻提醒卡片、可开启的声音与电脑桌面通知（已实现，本机基本体验获用户确认，系统兼容性未全覆盖）；
- 后续研究重复提醒、稍后提醒、任务重试和高峰处理。

跨端界面建议从适配电脑和手机的网页开始，再评估 PWA。支持跨端访问与支持关闭页面后的
系统通知是不同能力；客户端技术和推送渠道尚未最终选定。

## 当前状态

阶段 0 已完成。仓库目前包含：

- Java 21 / Spring Boot 3.5 应用骨架；
- 只运行 MySQL 8.4.11 的 Docker Compose 配置；
- Spring Boot Actuator 健康检查；
- 基础测试。
- 用户表手动建表脚本，本地已执行并核验；不会在应用启动时自动执行。
- Argon2id 密码哈希配置与测试，已接入注册及登录校验。
- `POST /api/users/register` 注册接口，以及输入校验、用户名冲突和异常响应处理。
- Spring Security + Session + Cookie 登录、当前用户查询、退出和 CSRF 防护。
- 已在本机建立 notes 表；实现创建、本人详情、分页列表及 `PUT /api/notes/{id}` 编辑。
- `PATCH /api/notes/{id}/completion` 标记完成或恢复未完成；同值重复请求保持更新时间不变。
- 本人删除、持久化一次性提醒、事务调度、通知分页与已读；新增 reminders/notifications 两张表。
- 原生 HTML/CSS/JavaScript 页面由 Spring Boot 同源托管，不需要 Node 或 npm 启动。
- [主动提醒](docs/phase-1/active-reminders.md)：常驻卡片、声音/桌面开关与测试、权限失败回退、未读补查。
- [提醒设置](docs/phase-1/reminder-settings.md)：齿轮保留声音/桌面全局开关，编辑弹窗选择样式/铃声、导入图片/铃声；格式提示与失败不退出，副本仅存当前浏览器。
- [列表与回收站](docs/phase-1/editor-and-trash.md)：未完成优先、点击打开编辑弹窗、底栏操作、每账号最近 30 条删除记录可恢复。
- [统一保存与声音诊断](docs/phase-1/unified-save.md)：新建直接填写时间，一个“保存”事务提交内容和提醒；显示实际播放状态，已开启时保存尝试恢复声音。

本地构建、测试和 MySQL 连接均已验证。最新离线回归 284 项通过（总报告 294 项，10 项真实 MySQL
案例默认跳过）；此前单独通过 4 项真实备忘录/MVP MySQL 验收，临时数据已清理。
此前新增 note_trash 表，单独通过真实 MySQL 回收站验收并清理临时数据；原学习记录统计不变。本轮新增统一保存事务与播放状态处理，不改表或学习数据。
80 项前端逻辑/静态检查通过。2026-10-08 用户确认当前本机网页功能与最后边界检查通过，
包括统一保存、实际到期声音、失败草稿保留、提醒取消/去重、停机跨到期后补生成通知及回收站/账号隔离。
新统一保存另单独通过 4 项真实 MySQL 验收，包含实际写入后注入异常的完整回滚，临时数据已精确清理；复盘与版本保存仍待收尾。
手机和各浏览器兼容性未全面验证；数据库故障注入是代码异常，不宣称覆盖数据库崩溃或网络故障。
用户手动反馈与自动替身测试分别记录，不声称代理取得真实页面截图或已做手机实测。
关闭网页、休眠和手机锁屏实时推送尚未实现；电脑桌面通知不等于 Web Push 或原生 App。
notes 建表脚本不会自动执行，本机无需重复建表。手动验收命令见备忘录说明，使用 CMD。

现在优先按 [MVP 网页验收](docs/phase-1/mvp.md) 启动应用并打开 http://localhost:8080/；
无需再逐条调用 curl。关闭网页后通知会保存在数据库，但没有手机锁屏推送。

详细信息：

- [文档总导航](docs/README.md)
- [阶段 0 说明](docs/phase-0/README.md)
- [本地开发环境](docs/common/development-environment.md)
- [Java 目录与代码职责入门](docs/common/code-structure.md)
- [项目总体规划](PROJECT_PLAN.md)
- [项目进度](PROGRESS.md)
- [业务范围与提醒设计](docs/common/product-scope.md)
- [从 localhost 到 HTTPS 网站、PWA 与推送的路线](docs/common/deployment-route.md)
- [阶段 1 导航：注册、登录及备忘录各小目标](docs/phase-1/README.md)

## 环境要求

- Windows：JDK 21、Maven 3.9+、Git，可选 IntelliJ IDEA；
- Docker Desktop 与 Docker Compose。

代码、构建工具和 Spring Boot 应用在 Windows 本地运行，MySQL 等基础设施通过
Docker 运行。当前不将 Spring Boot 应用容器化。

## 配置本地凭据

本文所有终端命令都在 **Windows CMD（命令提示符）** 执行。
首次使用时创建本地环境变量文件；已存在的 `.env` 不覆盖：

```cmd
cd /d D:\1A-project\collab-notes-platform
if not exist .env copy .env.example .env
```

打开 `.env`，将其中的 `change_me` 替换为自己的本地密码。`.env` 已被 Git 忽略，
不得提交真实密码。

## 启动 MySQL

```cmd
docker compose up -d --pull never mysql
docker compose ps
```

当状态显示 `healthy` 时，说明 MySQL 已完成初始化。

## 运行测试

```cmd
mvn --offline -Ddebug=false test
```

快速数据库测试使用 MySQL 兼容模式下的 H2，真实 MySQL 验收默认跳过，所以普通测试
不依赖 Docker。`--offline` 不下载依赖；新开发机器先按约定获得下载同意并准备依赖。
真实 MySQL 验收的运行方式见 [注册说明](docs/phase-1/user-registration.md#真实-mysql-验收)。

## 在本地运行应用

在 IntelliJ IDEA 的运行配置中设置 `DB_USERNAME` 和 `DB_PASSWORD`，或者在当前
CMD 会话中从现有 `.env` 加载：

```cmd
cd /d D:\1A-project\collab-notes-platform
for /f "usebackq tokens=1,* delims==" %A in (".env") do @set "%A=%B"
mvn --offline spring-boot:run -Dspring-boot.run.arguments=--debug=false
```

加载命令适用于本机简单的 `KEY=value` 格式，不适用于复杂引号、多行值或完整 dotenv 语法。
在 CMD 终端中用 `%A`，若写入 `.cmd` 批处理则用 `%%A`。不要打印凭据或把真实密码写入文档。

检查健康状态：

```cmd
curl.exe http://localhost:8080/actuator/health
```

预期返回：

```json
{"status":"UP"}
```

## 试用注册与登录接口

先确保用户表已按 [注册说明](docs/phase-1/user-registration.md) 建好，并启动本地应用。
在项目根目录另开一个 **CMD**，先获取 CSRF 校验码，并把 Cookie 保存在临时目录：

```cmd
curl.exe -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

复制返回 JSON 中 `token` 的完整内容，不带两侧双引号，替换下一行的中文后执行：

```cmd
set "CSRF=这里替换成刚返回的token"
curl.exe -i -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/register-example.json" http://localhost:8080/api/users/register
```

示例文件仅含公开的本地学习占位密码，不用于真实账号。首次注册预期为 `201`，再次提交
相同用户名预期为 `409`；ID 和时间由服务端生成，不能照示例期待固定的 ID。
已有学习账号不需要再注册，直接按 [登录说明](docs/phase-1/user-login.md#你现在怎么操作)
完成登录 → 查询当前用户 → 更新 CSRF → 退出。之前不带 CSRF 的 curl 注册命令现在会返回 403。
临时 Cookie 文件含会话凭据，不上传或提交 Git；登录说明包含用完后的精确清理命令。
查看数据库时使用 `SELECT id, username, created_at FROM users;`，不要输出密码哈希。
通过 HTTP 测试仅限本机；部署或跨设备传输密码前必须配置 HTTPS。

## 停止环境

停止应用时，在运行窗口按 `Ctrl+C`。

停止 MySQL 但保留数据：

```cmd
docker compose down
```

只有明确希望删除本地数据库全部数据时，才能使用 `docker compose down -v`。

## 目录结构

```text
src/main/java/com/collabnotes/platform  Spring Boot 应用根包
src/main/resources                     运行配置
src/main/resources/db/manual           手动执行的 SQL 脚本
src/main/resources/static             同源网页，无前端构建工具要求
src/test                               后端测试、真实 MySQL 验收及可选前端静态检查
docker                                 基础设施说明与后续支持文件
docs                                   架构演进和开发文档
```

## 演进边界

阶段 0 已完成，阶段 1 基础 MVP 已实现注册/登录、备忘录管理、一次性提醒、站内通知与网页；
本地网页功能已获用户确认；手机真机联调及关页/锁屏系统推送仍待推进，不宣称高并发能力。

后续保留数据库基线、并发控制、缓存、设计模式、消息队列、搜索、高并发保护、分库分表
实验、可观测性和最终压测的学习路线。每个阶段围绕备忘录与提醒中的真实问题推进。

接口记录 QPS、P50/P95/P99 和错误率；提醒链路另外记录调度延迟、积压量、发送尝试结果
和重复处理情况。大数据量与高并发分别进行实验，所有模拟负载和测量结果明确标注。
