# collab-notes-platform

这是一个面向电脑和手机使用的备忘录与定时提醒项目，重点学习多端数据一致性、可靠提醒
和高峰任务处理。项目从简单的 Spring Boot 单体应用开始，只有在发现并量化真实问题后，
才引入下一项技术。

典型场景：在电脑上记录“周五下午面试”，设置提前半小时提醒；手机能够查看和修改同一条
备忘录，到时间收到提醒，完成状态在不同设备间同步。

`collab-notes-platform` 作为现有工程标识继续使用，当前保留仓库名、Java 包名和数据库名。
业务规划以备忘录和提醒为主线，多人工作区、共享知识库和协作权限不属于当前核心范围。

## 目标功能与实现状态

以下为整体目标，当前阶段 0 已完成，阶段 1 已完成用户表、注册、登录及备忘录创建/本人详情：

- 注册、登录、当前用户查询和退出（已实现）；备忘录创建/详情已验证用户隔离；
- 备忘录创建与本人详情（已实现）；编辑、删除、分页与完成切换待实现；
- 一次性提醒的设置、修改、取消与触发；
- 电脑和手机访问同一账号的数据，逐步加入并发冲突处理；
- 先验证站内通知，再按实际设备选择系统推送渠道；
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

- 已在本机建立 notes 表；实现 `POST /api/notes` 和 `GET /api/notes/{id}`。

本地构建、测试和 MySQL 连接均已验证。109 项快速测试通过；另已单独通过真实 MySQL
注册/登录及备忘录创建/详情验收，临时数据已清理。当前不包含提醒或客户端界面。
notes 建表脚本不会自动执行，本机无需重复建表。手动验收命令见备忘录说明，使用 CMD。

详细信息：

- [阶段 0 说明](docs/phase-0.md)
- [本地开发环境](docs/development-environment.md)
- [项目总体规划](PROJECT_PLAN.md)
- [项目进度](PROGRESS.md)
- [业务范围与提醒设计](docs/product-scope.md)
- [用户表与注册接口设计](docs/phase-1-user-registration.md)
- [登录与退出：设计、代码职责及手动操作](docs/phase-1-user-login.md)
- [备忘录创建与本人详情：设计、实现与验收](docs/phase-1-notes.md)

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
真实 MySQL 验收的运行方式见 [注册说明](docs/phase-1-user-registration.md#真实-mysql-验收)。

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

先确保用户表已按 [注册说明](docs/phase-1-user-registration.md) 建好，并启动本地应用。
在项目根目录另开一个 **CMD**，先获取 CSRF 校验码，并把 Cookie 保存在临时目录：

```cmd
curl.exe -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

复制返回 JSON 中 `token` 的完整内容，不带两侧双引号，替换下一行的中文后执行：

```cmd
set "CSRF=这里替换成刚返回的token"
curl.exe -i -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/http/register-example.json" http://localhost:8080/api/users/register
```

示例文件仅含公开的本地学习占位密码，不用于真实账号。首次注册预期为 `201`，再次提交
相同用户名预期为 `409`；ID 和时间由服务端生成，不能照示例期待固定的 ID。
已有学习账号不需要再注册，直接按 [登录说明](docs/phase-1-user-login.md#你现在怎么操作)
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
src/test                               基础、密码配置、注册、登录和数据库验收测试
scripts                                手动开启的真实 MySQL 验收脚本
docker                                 基础设施说明与后续支持文件
docs                                   架构演进和开发文档
```

## 演进边界

阶段 0 已完成，阶段 1 已完成注册、登录、备忘录创建与本人详情；其他备忘录功能和提醒仍待分步实现。

后续保留数据库基线、并发控制、缓存、设计模式、消息队列、搜索、高并发保护、分库分表
实验、可观测性和最终压测的学习路线。每个阶段围绕备忘录与提醒中的真实问题推进。

接口记录 QPS、P50/P95/P99 和错误率；提醒链路另外记录调度延迟、积压量、发送尝试结果
和重复处理情况。大数据量与高并发分别进行实验，所有模拟负载和测量结果明确标注。
