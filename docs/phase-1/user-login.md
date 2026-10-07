# 阶段 1 · 第二个小目标：登录、当前用户与退出

## 已完成什么

2026-10-07 已接入此前获准下载的 `spring-boot-starter-security` 3.5.16，使用 Spring
Security 6.5.11 校验账号密码、管理会话和保护接口。本次没有下载新依赖、安装软件或新增中间件。
没有改数据库结构，没有实现备忘录、提醒或客户端界面。

注册是创建账号；登录是证明身份并建立会话。这是两个独立步骤，注册成功不自动登录。

## Session 和 Cookie 用来干什么

Session 是服务端保存的登录状态，Cookie 是客户端保存并随请求带回的会话编号。
本项目登录成功后，服务端会保存用户身份，客户端收到 `JSESSIONID` Cookie；随后查询
当前用户时，不用再次提交密码，也不能靠自己填写用户 ID 冒充其他账号。

Session 暂存在本地应用的内存中，不在 MySQL 中建会话表，也不引入 Redis。空闲超时设为
30 分钟；应用重启后需要重新登录。这适合当前单实例学习，不是多实例共享会话方案。
同一账号可建立多个独立会话；退出只结束当前设备的会话，不是“所有设备退出”。

## 接口约定

| 接口 | 用途 | 主要结果 |
| --- | --- | --- |
| `GET /api/auth/csrf` | 获取防伪校验码，未登录也可调用。 | 200，返回 `headerName`、`token`。 |
| `POST /api/users/register` | 创建账号，JSON 字段规则不变。 | 201 / 400 / 409 / 500。 |
| `POST /api/auth/login` | 用 JSON 用户名和密码登录。 | 200，仅返回 `id`、`username`。 |
| `GET /api/auth/me` | 查询当前会话身份。 | 200，仅返回 `id`、`username`；未登录 401。 |
| `POST /api/auth/logout` | 使当前会话失效，清除 Cookie。 | 204，无响应体。 |

三个 POST 接口都需要同一会话的 CSRF 校验码。缺失或错误返回 403 / `CSRF_INVALID`，
此时还没有执行业务；不要通过关闭 CSRF 来绕过。登录请求仅支持 JSON，字段必须是字符串，
规则与注册一致，不裁剪密码，拒绝额外字段和多余 JSON 内容。
非法输入 400；非 JSON 415；合法格式但账号不存在或密码错误均返回相同的
401 / `INVALID_CREDENTIALS`；数据库异常返回脱敏的 500 / `INTERNAL_ERROR`。
健康检查仍允许匿名访问；其他接口默认要求登录，不提供默认 HTML 登录页或默认生成账号。

## 为什么多了 CSRF

浏览器会自动携带 Cookie，因此仅有登录 Cookie 还不足以判断某个修改请求是否来自自己的
页面。CSRF 校验码是另一项检查，用来防止其他网站借用用户的登录状态提交操作；它不是
登录凭据，拿到校验码也不代表已登录。

客户端先 GET 获取校验码，再把返回的 token 放到返回的 headerName 对应的请求头中。
两次请求必须使用同一个 Cookie 会话。登录成功或退出后需重新获取校验码，不能一直
复用登录之前的值。这遵循 [Spring Security 的 CSRF 指南](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)。
以后网页会封装这些步骤，目前在 CMD 中用 `curl.exe` 的 Cookie 文件保存会话。

## 代码职责

代码位于 `src/main/java/com/collabnotes/platform`：

- `config/SecurityConfiguration.java`：定义公开接口、401/403 JSON 响应和登录、会话、退出策略。
- `auth/JsonLoginFilter.java`：读取并校验 JSON；调用认证框架，不自己实现密码算法。
- `auth/DatabaseUserDetailsService.java`：通过 Repository 按用户名查账号。
- `user/UserRepository.java`：新增参数化 SELECT；保留原注册 INSERT 和唯一约束处理。
- `auth/AuthenticatedUser.java`：框架中的用户身份，校验成功后擦除密码哈希。
- `auth/AuthController.java`：只提供 CSRF 和当前用户查询；登录与退出由安全过滤器处理。
- `auth/LoginRequest.java` / `CurrentUserResponse.java`：区分含凭据的输入与不含凭据的输出。
- `application.yml`：30 分钟空闲超时、HttpOnly、SameSite=Lax 和可配置 Secure Cookie。

认证框架使用已有 Argon2id `PasswordEncoder.matches` 验证哈希；成功后轮换会话编号以
防止会话固定攻击，明确保存 SecurityContext。原始密码和密码哈希不进入会话身份或响应。
框架处理退出时使 Session 失效并清理安全上下文和 CSRF；参见
[Spring Security 退出说明](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/logout.html)。
内部认证异常在父过滤器处理前去除原始消息与 cause，避免框架错误日志输出 SQL 或凭据。

## 你现在怎么操作

### 1. 重启应用

如果旧应用还在运行，先在它的窗口按 Ctrl+C。Docker Desktop 保持打开，MySQL 保持运行。
数据库表无需重建、已有账号无需重新注册。在 **CMD** 应用窗口执行：

```cmd
cd /d D:\1A-project\collab-notes-platform
docker compose up -d --pull never mysql
for /f "usebackq tokens=1,* delims==" %A in (".env") do @set "%A=%B"
mvn --offline spring-boot:run -Dspring-boot.run.arguments=--debug=false
```

这里沿用本机简单的 `KEY=value` 格式 `.env`；不支持复杂引号或多行值。
终端中用 `%A`，写入批处理文件时用 `%%A`。本文所有操作都在 CMD 执行。
保留运行窗口，看到启动成功后再操作下一步。日志不是要执行的命令。

### 2. 另开 CMD，获取 CSRF 校验码

默认使用之前的公开学习账号 `winter_01` 和示例占位密码，
仅适用于你按原示例创建的账号。如果改过示例或注册时用了其他密码，应改用相应学习账号；
不要把真实密码写回示例文件或提交 Git。

```cmd
cd /d D:\1A-project\collab-notes-platform
curl.exe -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

返回 JSON 包含 `headerName` 和 `token`。复制 `token` 的完整内容，不带两侧双引号，
替换下一行的中文后执行，不要使用聊天记录中的旧 token：

```cmd
set "CSRF=这里替换成刚返回的token"
```

如果已有 `winter_01`，跳过注册。只有准备创建另一个学习账号时才调用：

```cmd
curl.exe -i -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/register-example.json" http://localhost:8080/api/users/register
```

注册成功 201；已有同名账号时 409 是预期结果，不要删表来重试。

### 3. 登录，再查看身份

```cmd
curl.exe -i -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/register-example.json" http://localhost:8080/api/auth/login
curl.exe -i -b "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/me
```

两次都应返回 200 和自己的 `id`、`username`。第二次没有发密码，是 `-b` 自动携带了 Cookie。
`-c` 保存服务端新发的 Cookie，登录时必须保留它，才能保存轮换后的会话编号。
ID 以数据库中的实际记录为准，不期待固定值。

### 4. 更新 CSRF，退出登录

先使用已登录的 Cookie 获取新的 token，不要使用第 2 步的旧值：

```cmd
curl.exe -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

复制刚返回的 token，替换下面的中文，随后执行退出：

```cmd
set "CSRF=这里替换成新返回的token"
curl.exe -i -X POST -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" http://localhost:8080/api/auth/logout
```

应返回 `HTTP/1.1 204`，表示退出成功，没有 JSON 响应体。GET 不会退出登录。

### 5. 确认退出生效

```cmd
curl.exe -i -b "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/me
```

应返回 `HTTP/1.1 401`，JSON 错误码为 `UNAUTHENTICATED`。重新登录前，再按第 2 步
获取新的 CSRF 校验码。若 POST 出现 403，优先检查
会话是否一致、token 是否在登录/退出后失效、请求头是否正确；不要关闭防护。
Cookie 文件只保存在 `%TEMP%`，含会话凭据，不上传、不截图、不提交 Git。用完后清理
这个确定的临时文件和当前 CMD 的变量；不删除其他文件：

```cmd
if exist "%TEMP%\collab-notes-session.txt" del "%TEMP%\collab-notes-session.txt"
set "CSRF="
```

删除本地 Cookie 文件不等于让服务端退出，因此先完成 POST 退出，再清理文件。

## 验证结果与边界

2026-10-07 离线报告共 70 项：69 项通过，1 项真实 MySQL 验收默认跳过。
新增验证包括错误密码、未知账号、非法 JSON、CSRF 缺失/伪造/轮换、会话编号轮换、凭据
擦除、Cookie 属性、退出失效、旧 Cookie 重放拒绝及不同设备的会话独立性。
原有注册验证仍通过，注册成功仍不会自动登录。

```cmd
mvn --offline --batch-mode --no-transfer-progress -Ddebug=false -Dlogging.level.root=WARN test
```

真实 MySQL 验收类为 `UserRegistrationMySqlTests`，内容已扩展为注册与登录验收。该项单独运行也
已通过，只精确删除本次随机生成的 1 个 `verify_` 账号，没有删表、删卷或修改你的学习账号。
测试使用独立随机 HTTP 端口，结束后服务关闭，不占用你正在使用的 8080。
可选脚本已移除；验收类仍保留。环境变量配置与 Maven 复现方式见注册文档。
用户已在 CMD 手动验证登录与当前用户查询均返回 200；退出仍待手动验证。

当前仅限本机学习：本地 HTTP 的 Secure=false；正式部署必须 HTTPS，并设置
`SESSION_COOKIE_SECURE=true`。不要开放公网，也不要启用 DEBUG/TRACE 凭据链路日志或记录
Cookie、CSRF token 和请求体。尚未实现限流、防撞库、密码重置、会话共享、全部设备退出或
系统推送。后续已实现备忘录创建/本人详情的用户隔离，见 [备忘录说明](notes.md)。
测试通过不是高并发容量结论。

认证小目标完成后已建立备忘录表并实现创建/本人详情，每次访问都从会话取身份，而不是
相信客户端传来的用户 ID；其他备忘录操作与提醒仍按后续小目标推进。
