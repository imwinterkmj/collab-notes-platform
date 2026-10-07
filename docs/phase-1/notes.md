# 阶段 1 · 第三个小目标：备忘录表与用户归属

## 本次完成范围

2026-10-07 已经用户明确授权，在本机 MySQL 8.4.11 的 collab_notes 执行并核验手动脚本
`src/main/resources/db/manual/002_create_notes.sql`，建立 notes 表，保留已有学习账号。
**已实现创建和本人详情，自动测试及真实 MySQL 验收通过。**
创建/详情这个小目标不下载依赖、不添加中间件、不修改现有注册与登录业务逻辑；不实现提醒、标签、
修改、删除或客户端界面。用户已在 CMD 验证登录、当前用户查询及创建/详情；退出仍待手动验证。

当前已完成“已登录用户创建一条备忘录，并且只能查看自己的详情”的小闭环。
后续已增加[本人分页列表](notes-list.md)和[编辑](notes-update.md)；完成状态切换、删除和提醒仍按小目标推进。

## 为什么需要另一张表

`users` 保存账号，`notes` 保存备忘录。一名用户可以有多条备忘录，每条备忘录属于一名用户。
不把备忘录内容塞进用户表，也不为每个用户创建一张独立的备忘录表。

客户端提交标题、正文；服务端根据已经验证的登录身份决定保存到谁的名下。
后续电脑和手机使用同一个账号时，读取同一用户的数据库记录，这是跨端访问的基础，
但本设计还没有实现实时刷新、离线同步或并发修改冲突处理。

## 七个字段

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `id` | `BIGINT`，自增主键 | 标识一条备忘录，不能当作访问凭据。 |
| `user_id` | `BIGINT`，非空 | 所属用户，引用已有的 `users.id`。 |
| `title` | `VARCHAR(120)`，非空 | 标题，应用要求 1～120 个 Unicode 码点且不能全为空白。 |
| `content` | `TEXT`，非空 | 纯文本正文，可为空字符串，最多 10,000 个 Unicode 码点。 |
| `is_completed` | `BOOLEAN`，默认 false | 是否已完成，不代表提醒已发送或用户已读通知。 |
| `created_at` | `DATETIME(6)`，非空 | 创建时间，由应用按 UTC 写入。 |
| `updated_at` | `DATETIME(6)`，非空 | 最近修改时间，由应用按 UTC 写入。 |

这是当前学习版本的长度选择，不是行业统一标准。标题、正文均要求 JSON 字符串，
不自动把数字转换为文字；保留空格、换行、大小写和 emoji，不擅自裁剪内容。
创建请求中两个字段都必须提供，空正文写为 `""`，缺失或 null 均拒绝。
同名标题允许重复，不像用户名那样建立唯一约束。

创建时服务端设置 `is_completed=false`，并用同一个截取到微秒的 UTC 时间设置两个时间字段。
更新接口以后再负责修改 `updated_at`，不用数据库自动时间默认值混入本机时区。

数据库约束与应用校验互相补充：CHECK 限制长度和状态，但标题是否全空白由应用验证。
MySQL 中 BOOLEAN 是 TINYINT(1) 的别名，因此额外约束状态只能为 0 或 1。依据见
[MySQL 数值类型](https://dev.mysql.com/doc/refman/8.4/en/numeric-type-syntax.html) 和
[CHECK 约束](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)。

## 外键与索引

外键保证 `user_id` 指向存在的账号，不允许产生无所属用户的记录。删除用户时采用 RESTRICT，
不隐式级联删除备忘录；注销账号及数据删除策略尚未设计。它保证的是引用完整性，
**不能保证发出请求的人有权访问这条记录**。依据见
[MySQL 外键约束](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)。

表保留一个基础联合索引 `(user_id, updated_at DESC, id DESC)`，对应后续按用户展示最近
修改的备忘录；时间相同时用 ID 保持确定的排序。它不是实测优化成果；分页、数据分布和
执行计划在列表功能及数据库基线阶段验证，不现在生成大量模拟数据或宣称吞吐量。

不预加 `version`、软删除、分享角色、全文索引或提醒字段。更新丢失在阶段 3 用实验和版本号
研究；提醒拥有独立的任务状态、触发时间和生命周期，在对应小目标设计任务表。

## 归属检查：不能相信客户端提供的 userId

创建接口从现有 `AuthenticatedUser.getId()` 获取用户 ID，可像当前用户查询一样使用
`@AuthenticationPrincipal` 注入身份。请求 DTO 只接收 `title`、`content`，拒绝额外字段，
包括 `userId`、`user_id`、`id`、状态和时间字段。

详情查询必须把当前用户 ID 放进 SQL 条件，并使用参数绑定：

```sql
SELECT id, title, content, is_completed, created_at, updated_at
FROM notes
WHERE id = ? AND user_id = ?;
```

第一个参数来自路径中的备忘录 ID，第二个来自服务端验证后的身份，不能来自请求参数。
不先按 ID 查询并返回正文再检查权限，也不通过“ID 很难猜”代替授权。
查不到与属于别人的记录都统一返回 404，避免通过响应区别泄露其他用户记录是否存在。
已实现的列表同样限定当前用户；未来修改、删除和提醒操作也必须保留用户范围，而不是只校验是否登录。

## 两个已实现的接口

### 创建：POST /api/notes

需要已登录的 Cookie 和当前会话的 CSRF 校验码。请求示例保存在 `docs/phase-1/http/create-note-example.json`：

```json
{
  "title": "周五下午面试",
  "content": "复习 Java 集合、数据库索引和项目经历。"
}
```

预期 201，响应 DTO 只包含 `id`、`title`、`content`、`completed`、`createdAt`、`updatedAt`。
`completed` 是 JSON 字段名，数据库字段名为 `is_completed`；两个时间字段返回 UTC 的 `Z` 格式。
ID 由数据库生成，不接受客户端指定。标题内容不进入服务端日志，也不写入错误消息。

### 详情：GET /api/notes/{id}

需要登录；GET 不要求 CSRF。本人记录返回 200，响应字段与创建成功相同。
记录不存在或不属于当前用户均返回 404 / `NOTE_NOT_FOUND`。合法 ID 为正的 Java long；
无效格式、非正数或溢出返回 400。接口不是分享链接，不支持匿名阅读。

其他错误约定：未登录读取返回 401；写请求缺少/错误 CSRF 会先返回 403，即使还没登录；
持有有效 CSRF 但未登录的创建请求返回 401。非法输入返回 400，非 JSON 创建请求返回 415，
数据库异常返回脱敏 500，不能当成“不存在”或成功。安全过滤器规则沿用现有配置。

## 实现与验收结果

1. 在 CMD 中检查目标数据库、现有 `users` 和 `notes` 是否已存在；确认后再手动执行 SQL。
   不添加自动建表，不通过删表重试，不改已有账号。
2. 少量实现创建和详情的 DTO、Controller、Service、Repository，复用现有 JDBC、校验和认证。
3. 测试本人创建与读取、另一个用户不能读取、伪造所属用户被拒绝、未登录和 CSRF 防护。
4. 检查中文/emoji、长度边界、空正文、UTC 时间、错误响应和日志不泄露备忘录内容。
5. 快速测试之外再单独验收真实 MySQL 的字段、CHECK、外键和身份隔离；只清理本次测试数据。

以上项目已经自动验收。本机首次建表前确认 notes 不存在，users 有 1 个学习账号；
建表后核验 7 个字段、主键、联合索引（含 DESC）、外键和三个 CHECK。没有重建 users 或删除数据卷。

完整离线报告共 111 项：109 项通过，2 项真实 MySQL 验收默认跳过。新增 40 项快速备忘录测试，
包括输入格式、Unicode 码点边界、空正文、重复标题、数据库持久化、UTC 时间、本人/他人隔离、
匿名请求、CSRF、无效 ID、非法方法、数据库错误与内容脱敏。
另单独开启 DEBUG 验证 3 项异常与校验的日志脱敏；不因此建议正常开发启用凭据链路 DEBUG/TRACE。

真实 MySQL 的 `NoteMySqlTests` 单独通过，验证 HTTP 创建/详情与数据库内容一致、另一账号
不能越权、中文/emoji、UTC、重复标题、空正文、实际长度边界、三个 CHECK、外键和 RESTRICT。
首轮因 CHECK 异常分类断言不符失败，按实际数据库错误码修正后复验通过；两轮结束时均只清理
本轮随机生成的 3 条备忘录和 2 个账号（累计 6 条、4 个）。核验后 notes 为空，原 winter_01 保留。
随机端口测试服务已关闭，不替代用户正在运行的 8080 服务。

H2 与 MySQL 的 Unicode 字符长度语义不同，快速测试放宽 H2 标题列至 240 个 UTF-16 单元，
让应用的 120 个码点边界可被检验；不拿 H2 的列定义或 CHECK 行为冒充生产数据库。
真实 MySQL 单独验证 120 个 emoji 标题和 10000 个 emoji 正文正确入库。
当前 CHECK 违规由驱动报 HY000/3819，Spring 不一定翻译成 DataIntegrityViolationException；
测试核对真实错误码和约束名，不能只靠猜测异常类型判定约束有效。

## 新代码如何分工

代码位于 `src/main/java/com/collabnotes/platform/note`：

- `CreateNoteRequest`：严格要求字符串、声明长度与空值校验，拒绝客户端控制归属及服务端字段。
- `NoteController`：接收 POST/GET，从 `@AuthenticationPrincipal` 取得当前用户 ID，校验路径 ID。
- `NoteService`：组织创建和详情，生成同一个 UTC 微秒时间及初始 false 状态。
- `NoteRepository`：参数化 INSERT 和带 user_id 范围的 SELECT，写入/读回明确按 UTC 转换。
- `NoteResponse`：仅返回六个业务字段，JSON 中的状态名为 completed；toString 不打印私人内容。
- `NoteExceptionHandler` / `NoteNotFoundException`：字段错误 400、统一不可访问 404、脱敏错误 500。

单条 INSERT 不额外加长事务。未来同时创建提醒任务时，再明确多条写入需要共同提交的事务边界。
现有安全配置的默认 authenticated 规则已经覆盖新路径，不把新接口设为匿名。
生产和测试配置限制框架异常/JDBC 参数日志，避免原始私密内容从调试日志泄漏。

## 手动测试验收（CMD）

这是测试步骤，不是日常启动或脚本调用教程。当前表已建立，不再执行建表 SQL。
如果 8080 仍是旧进程，需先重启应用让新接口生效；重启会清空内存会话，需按
[登录说明](user-login.md#3-登录再查看身份) 重新登录，不能继续使用旧 Cookie。
以下假定已经使用 CMD 登录，并把当前 Cookie 保存在 `%TEMP%\collab-notes-session.txt`。

### 创建一条备忘录

先获取登录后的 CSRF，复制 token 的完整内容，不带 JSON 两侧双引号：

```cmd
cd /d D:\1A-project\collab-notes-platform
chcp 65001
curl.exe -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

替换下面中文后执行创建。不要把返回的 token 写入示例文件或 Git：

```cmd
set "CSRF=这里替换成刚返回的token"
curl.exe -i -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/create-note-example.json" http://localhost:8080/api/notes
```

预期 201，返回 id、标题、正文、completed=false，以及两个相同的 UTC 时间。
手动创建的记录会保留；重复执行会创建另一条记录，不是“更新原记录”。

### 查看自己的详情

把创建响应中的实际 ID 填入变量，不使用固定示例 ID：

```cmd
set "NOTE_ID=这里替换成刚创建的id"
curl.exe -i -b "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/notes/%NOTE_ID%
```

预期 200，与创建响应一致。未带 Cookie 的同一请求预期 401：

```cmd
curl.exe -i http://localhost:8080/api/notes/%NOTE_ID%
```

另一个账号登录后使用自己的 Cookie 查询这条 ID 预期 404；不是关闭 CSRF 或手工更改 userId
来绕过隔离。如果 POST 返回 403，先确认用同一个会话并更新了登录后的 CSRF。
Cookie 文件用完按登录说明退出并清理，不上传或提交到 Git。

## 自动测试复现

快速测试不需要 Docker：

```cmd
mvn --offline --batch-mode --no-transfer-progress -Ddebug=false -Dlogging.level.root=WARN test
```

真实备忘录验收类仅在 `RUN_MYSQL_NOTES_TESTS=true` 时运行，使用 `MYSQL_TEST_USER`、
`MYSQL_TEST_PASSWORD`、可选 `MYSQL_TEST_URL` 提供普通账号的本地连接。准备好这些进程
环境变量后，可用 `mvn --offline -Ddebug=false -Dtest=NoteMySqlTests test` 复现。
密码不写入源码或文档。本次代理已完成这项验收，用户不需要为了启动应用调用测试脚本。
可选注册验收脚本已移除，注册与登录的 Java 验收类仍保留，不要求用户运行脚本。

当前创建不保证请求幂等，重复提交可能产生两条记录；请求超时不能断言一定未保存。
纯文本内容不等于前端可以直接作为 HTML 渲染，客户端后续须按文本展示或做适当的转义。

所有给用户的命令继续使用 CMD。当前无需下载任何工具、依赖或镜像。
