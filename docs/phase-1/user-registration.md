# 阶段 1 · 第一个小目标：用户表与注册接口

## 当前范围与状态

已确定表字段、约束和接口草案。2026-10-06 已在本地 MySQL 8.4.11 的 `collab_notes`
数据库创建并核验空的 `users` 表。2026-10-07 经用户同意，在 `pom.xml` 添加并通过 Maven
下载了密码处理依赖，随后完成 Argon2id 密码哈希配置并离线通过 12 项测试。
随后已完成最小注册接口：48 项离线测试通过，另有 1 项真实 MySQL 验收通过，验证了
同名双请求竞争、哈希存储和 UTC 时间。随机临时验收账号已精确清理，没有删除表或数据卷。
本次实现没有下载新依赖，也没有引入新基础设施。

随后已完成最小登录闭环，当前共 69 项快速测试通过，真实 MySQL 注册与登录验收通过。
登录与退出见 [登录说明](user-login.md)。备忘录和提醒尚未实现，按后续小步骤推进。

## 为什么先设计用户

电脑和手机登录同一个账号后，才能访问同一份备忘录。后续备忘录通过用户 ID 确定归属，
接口必须验证当前登录用户有权访问目标数据；用户 ID 本身不等于访问授权。

第一版先使用用户名注册，不要求邮箱、手机号、昵称、头像或第三方登录。

## 四个字段及选择理由

| 字段 | SQL 类型 | 约束 | 用途 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 主键、自增、非空。 | 稳定的用户标识，后续备忘录引用它。 |
| `username` | `VARCHAR(32)` | 非空、唯一。 | 用户输入的登录名。 |
| `password_hash` | `VARCHAR(255)` | 非空。 | 保存密码算法生成的编码结果。 |
| `created_at` | `DATETIME(6)` | 非空。 | 保存用户创建时间，约定按 UTC 写入。 |

### ID 与用户名为什么分开

ID 是内部稳定标识，用户名是用户使用的登录名。以后修改登录名时，备忘录的归属不需要
跟着改变。自增 ID 先满足单库需求，分布式 ID 在分片实验时再讨论。

使用有符号 `BIGINT`，方便映射 Java `Long`。自增值可能因为失败或回滚出现间隔，不用它
统计用户数，也不依赖编号连续。

### 用户名规则

设计约定：长度为 3～32 个字符，只允许小写英文字母、数字和下划线，模式为
`^[a-z0-9_]{3,32}$`。例如 `winter_01` 合法，`Winter`、带空格或中文的登录名暂不接受。

第一版直接拒绝不符合规则的输入，不悄悄转换大小写或裁剪。中文显示名可以作为后续独立
字段加入，不与登录名混用。所有写入路径都要遵守这条规则。

表使用明确的大小写敏感排序规则。排序规则会影响字符串比较与唯一性，不能把它当作
纯显示配置；这里结合严格的小写输入规则固定比较语义。

### 密码为什么不是 `password`

注册请求接收用户输入的密码，但数据库只保存密码哈希。拟采用 Argon2id，已准备以下 Java 库：

- `org.springframework.security:spring-security-crypto:6.5.11`：提供密码编码与校验接口，
  版本由当前 Spring Boot 3.5.16 管理。
- `org.bouncycastle:bcprov-jdk18on:1.86`：提供 Argon2 所需算法实现，版本在项目中明确固定。

最初仅引入密码处理模块；后续登录步骤已接入获准下载的 `spring-boot-starter-security`。
用户已同意下载这两个库及必要依赖，Maven 已完成下载；目前密码哈希工具及测试已完成，
已接入注册接口并在真实 MySQL 验证了数据库存储。其他新增下载仍须事先征得同意。

使用成熟密码库生成盐并保存包含算法、参数和盐的编码结果；不自行实现加密算法，不使用
MD5 或普通 SHA-256 代替密码哈希。登录时调用库的校验函数，不解密还原密码。

`VARCHAR(255)` 为编码结果及后续算法调整留出空间，不代表原始密码最多 255 个字符。
当前按用户自行选择的本地练习规则：原始密码为 1～128 个 Unicode 码点，不允许全空白；允许中文、emoji 和空格，
不强制字符组合，不悄悄截断、去空格或改变大小写。例如一个 emoji 通常占一个码点，
组合字符可能占多个码点；此处不是按屏幕显示字形或 Java UTF-16 的 `char` 数量计数。
该下限只用于用户选择的本地练习，不应当作正式安全建议；上线前应重新评估并参考
[OWASP 身份认证指南](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#implement-proper-password-strength-controls)。

密码与密码哈希都不进入 API 响应，也不记录到日志。相关依据见
[OWASP 密码存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)。

### 已实现的密码工具与测试

生产代码是 `src/main/java/com/collabnotes/platform/config/PasswordHashConfiguration.java`：

- `@Configuration`：让 Spring 自动发现这个配置类。
- `@Bean`：创建一个由 Spring 管理的 `PasswordEncoder`，后续 Service 可通过构造方法注入。
- `@Value`：读取配置或默认值，不在业务代码里到处创建密码工具。
- `Argon2PasswordEncoder`：调用已下载的成熟库计算 Argon2id，而不是自己写算法。
- 盐长度固定 16 字节，哈希长度固定 32 字节，并行度固定为 1；编码结果包括算法、参数、
  随机盐及哈希，不需要额外的盐字段。

示意用法（生成哈希已接入注册，校验已接入登录框架）：

```java
String storedHash = passwordEncoder.encode(rawPassword);
boolean correct = passwordEncoder.matches(candidatePassword, storedHash);
```

`encode` 用于生成以后存入 `password_hash` 的字符串；`matches` 根据已存编码中的盐和
参数校验候选密码，不能通过再次 `encode` 后比较字符串来判断密码是否相同，因为每次生成
随机盐。它不是可逆加密，没有“解密取回密码”的接口，也不替代登录身份认证。

`src/main/resources/application.yml` 管理两个成本参数：

| 配置项 | 可选环境变量 | 默认值 |
| --- | --- | --- |
| `app.security.password-hash.memory-kib` | `PASSWORD_HASH_MEMORY_KIB` | 19456 KiB，即 19 MiB。 |
| `app.security.password-hash.iterations` | `PASSWORD_HASH_ITERATIONS` | 2 次迭代。 |

选用 OWASP 的 `m=19 MiB、t=2、p=1` 起始方案；本项目暂不允许降低内存或迭代次数，低于
该方案会导致启动失败。其他满足安全要求的参数组合不属于当前配置范围。
增大参数会增加计算耗时和资源消耗；目前没有独立性能基线或并发测量，不能随意调大或
把测试耗时当作接口吞吐量。测试成功不代表数据库泄露后密码绝不可能被猜中。

新增 `PasswordHashConfigurationTests`，9 项测试检查：

1. 默认编码格式是 Argon2id，长度适合当前字段，正确密码校验成功。
2. 错误密码校验失败。
3. 相同密码生成不同随机盐和编码，但都可正确校验。
4. 保留空格、大小写、中文及 emoji，不隐式裁剪或转换。
5. 可以通过配置提高内存和迭代次数。
6. 四个低于基线的配置分别导致 Spring 上下文启动失败。

基础测试中另增 1 项检查：完整应用可以发现并注入密码工具。
2026-10-07 已在项目根目录执行以下命令，共 12 项通过、0 失败、0 错误：

```cmd
mvn --offline --batch-mode --no-transfer-progress -Ddebug=false -Dlogging.level.root=WARN test
```

`--offline` 表示只使用已有依赖，不下载；密码配置测试不连接数据库，基础数据源测试使用
H2，不需要启动 Docker。日志中低于基线的四次启动失败是测试预期，不是验收失败。
密码工具步骤本身没有创建账号；随后注册实现及真实数据库验收见下文。

### 时间为什么明确为 UTC

`DATETIME(6)` 表示日期时间并保留微秒，但本身不包含时区，也不会像 `TIMESTAMP` 那样
自动按会话时区转换。这里约定由应用按 UTC 写入，返回接口时明确表示为 UTC 时间。

例如接口的 `2026-10-05T08:00:00Z` 对应北京时间 16:00。后续客户端按自己的时区展示。
注册代码使用 `Instant` 生成时间，截取到微秒，并通过 `LocalDateTime.ofInstant(..., UTC)`
写入 `DATETIME(6)`。真实 MySQL 验收已检查读回的年月日时分秒转为 UTC 后与响应一致；
现有 Compose 的时区设置不自动保证这条约定。

依据见 [MySQL 日期时间类型说明](https://dev.mysql.com/doc/refman/8.4/en/datetime.html)。

## 建表 SQL

该 SQL 同时保存在 `src/main/resources/db/manual/001_create_users.sql`，已在本地执行。
它是手动脚本，不由 Spring Boot 自动执行，也不挂载到 Docker 初始化目录。
执行前查看现有表，不使用删除表来绕过已有结构。

```sql
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_as_cs;
```

- `NOT NULL`：这条记录必须具有该字段值；它不负责拒绝空字符串。
- `PRIMARY KEY`：唯一标识每条用户记录。
- `AUTO_INCREMENT`：插入时让数据库生成 ID。
- `UNIQUE`：通过唯一索引拒绝重复用户名。
- `InnoDB`：使用支持事务的存储引擎。
- `created_at` 没有使用依赖会话时区的默认时间，注册实现时由应用提供。

数据库负责唯一性和非空约束，应用负责输入格式与密码处理，两层职责互相补充。

### 在另一台开发机器上复现

先按照开发环境文档配置 `.env` 并启动 MySQL，等待 `docker compose ps` 显示 `healthy`。
在项目根目录的 **CMD** 执行以下检查；命令使用容器内已有的普通账号和密码，
不需要把真实密码写进命令或脚本：

```cmd
echo SELECT DATABASE(); SHOW TABLES;| docker compose exec -T mysql sh -c "export MYSQL_PWD=\"$MYSQL_PASSWORD\"; exec mysql --user=\"$MYSQL_USER\" --database=\"$MYSQL_DATABASE\""
```

确认目标数据库正确、没有 `users` 表后，执行一次：

```cmd
docker compose exec -T mysql sh -c "export MYSQL_PWD=\"$MYSQL_PASSWORD\"; exec mysql --user=\"$MYSQL_USER\" --database=\"$MYSQL_DATABASE\"" < src\main\resources\db\manual\001_create_users.sql
```

核验结构：

```cmd
echo SHOW CREATE TABLE users; SHOW INDEX FROM users; SELECT COUNT(*) AS user_count FROM users;| docker compose exec -T mysql sh -c "export MYSQL_PWD=\"$MYSQL_PASSWORD\"; exec mysql --user=\"$MYSQL_USER\" --database=\"$MYSQL_DATABASE\""
```

本机上述检查已完成：四个字段与设计一致，存在 `PRIMARY` 和 `uk_users_username` 两个
唯一索引，记录数为 0。当前机器不用再次建表；再次执行脚本会报表已存在，不要因此删表。
其他机器如果已经有该表，先比对结构，不能跳过差异或覆盖现有数据。

Docker 数据保存在原有 Volume 中；数据库中的表不是项目目录中的普通文件。
SQL 文件用于复现结构，不是数据库数据备份。Docker 的首次初始化机制不会因已有 Volume
重新启动而自动执行新建表脚本，所以这里明确手动执行；后续有多次结构变更时再讨论迁移工具。

## 为什么先查询不能代替唯一约束

两个请求可以交错执行：

```text
请求 A：查询 winter_01，发现不存在
请求 B：查询 winter_01，发现不存在
请求 A：插入 winter_01
请求 B：也尝试插入 winter_01
```

没有唯一约束时，两条记录可能都保存成功。增加 `uk_users_username` 后，数据库保证
两条相同用户名记录不能同时成功写入。应用可以先查询以提前提示，但还必须处理插入时的
用户名唯一冲突，并返回业务错误。

不要把所有数据库异常都翻译成“用户名已存在”：连接失败、SQL 错误或其他约束失败具有
不同含义。不使用 `INSERT IGNORE` 掩盖冲突，也不使用覆盖已有账号的更新语句来注册。

唯一索引行为见 [MySQL 唯一索引说明](https://dev.mysql.com/doc/refman/8.4/en/create-index.html)。

## 注册接口（已实现）

请求：`POST /api/users/register`，JSON 格式。

用户名和密码必须是 JSON 字符串，不把数字或布尔值悄悄转换成密码。额外字段一律拒绝。

```json
{
  "username": "winter_01",
  "password": "example-long-passphrase"
}
```

密码仅为示例占位值，不是任何实际账号凭据。请求不允许客户端指定用户 ID、密码哈希或
创建时间；这些值由服务端控制。

成功：`201 Created`，只返回必要的非敏感字段。以下 ID 和时间仅为响应示例。

```json
{
  "id": 1,
  "username": "winter_01",
  "createdAt": "2026-10-05T08:00:00Z"
}
```

错误约定：

| 情况 | HTTP 状态 | 处理原则 |
| --- | --- | --- |
| 输入缺失或不合法 | `400 Bad Request` | 给出可理解的字段错误，不回显密码。 |
| 用户名已被占用 | `409 Conflict` | 处理查询发现的重复和插入时的唯一冲突。 |
| 未预期的服务端错误 | `500 Internal Server Error` | 不泄露 SQL、堆栈或凭据，不能误报注册成功。 |

注册完成只表示账号已创建，不隐式登录。登录已单独实现，必须调用登录接口建立会话。
接入安全框架后，注册也必须携带同一会话的 CSRF 校验码，缺失或无效时返回 403。

## 已实现代码的职责

- Controller：接收请求、校验输入和返回 HTTP 响应。
- Service：组织注册规则、密码处理和用户创建。
- Repository：通过参数化 SQL 访问数据库，返回实际保存结果与生成的 ID。
- 请求 DTO：承载用户名和原始密码，不能直接作为响应。
- 响应 DTO：只承载 ID、用户名和创建时间。
- 异常处理：将明确的输入或用户名冲突转换为约定错误。

代码位于 `src/main/java/com/collabnotes/platform/user`：

| 文件 | 职责 |
| --- | --- |
| `RegisterUserRequest.java` | 接收用户名、密码，声明字段校验；密码只读入不序列化，`toString` 脱敏；拒绝额外字段。 |
| `RegistrationStringDeserializer.java` | 要求 JSON 字符串，拒绝类型的隐式转换。 |
| `RegisterUserResponse.java` | 只返回 ID、用户名、UTC 创建时间。 |
| `UserRegistrationController.java` | 暴露 POST 接口，触发 `@Valid` 校验，成功返回 201。 |
| `UserRegistrationService.java` | 调用现有密码工具，生成 UTC 时间，组织用户创建。 |
| `UserRepository.java` | 使用 Spring JDBC 参数化 INSERT，取得数据库生成的 ID，识别用户名唯一约束。 |
| `UsernameAlreadyExistsException.java` | 表示明确的用户名占用，不携带 SQL 或输入值。 |
| `RegistrationError.java` | 定义错误码、提示和字段错误的响应结构。 |
| `UserRegistrationExceptionHandler.java` | 保留正确的 HTTP 错误状态，不回显密码或 SQL；未预期错误仅记录异常类型。 |

注册流程：

```text
JSON 请求 → Controller 校验 → Service 生成哈希和 UTC 时间
          → Repository 执行 INSERT → 返回服务端生成的用户信息
```

没有先查询用户名；直接依靠数据库唯一约束判定是否可注册，避免查询与插入之间的竞态。
Repository 只将明确命中 `uk_users_username` 的唯一冲突转换为业务异常；主键冲突、
其他唯一约束和数据库故障不能误报为用户名占用。H2 快速测试与 MySQL 的约束识别分别处理。

目前写库只有一条 INSERT，数据库负责该语句的原子性，因此没有为整个 Service 添加
`@Transactional`。密码计算发生在申请数据库连接之前，避免在耗时计算期间持有连接或锁。
以后多条写入需要共同成功时，再明确事务边界。没有引入 ORM 或迁移工具。

### 手动调用注册接口

本机的用户表已经建好，不需要重复执行建表脚本。打开 Docker Desktop，等 MySQL 健康，
在 IntelliJ 配好普通账号凭据后启动应用；也可以在 **CMD** 中执行：

```cmd
cd /d D:\1A-project\collab-notes-platform
docker compose up -d --pull never mysql
for /f "usebackq tokens=1,* delims==" %A in (".env") do @set "%A=%B"
mvn --offline spring-boot:run -Dspring-boot.run.arguments=--debug=false
```

这里的 CMD 加载命令沿用本机 `.env` 的简单 `KEY=value` 格式，不适用于复杂带引号或多行
的 dotenv 内容。终端中用 `%A`，写成批处理时用 `%%A`。
`mvn` 运行期间保留这个窗口；启动日志是输出，不是需要手动执行的命令。

另开一个 **CMD**，先获取 CSRF 并保存 Cookie 会话：

```cmd
cd /d D:\1A-project\collab-notes-platform
curl.exe -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

把返回 JSON 的 `token` 完整内容复制到下一行，替换中文，不带 JSON 两侧双引号，再提交注册：

```cmd
set "CSRF=这里替换成刚返回的token"
curl.exe -i -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/register-example.json" http://localhost:8080/api/users/register
```

公开示例文件使用用户名 `winter_01` 和占位密码 `example-long-passphrase`，只用于本地
学习，不使用真实账号密码。第一次成功为 201，再次提交相同用户名为 409；如果该用户名
已存在，首次尝试也会是 409。修改示例用户名即可测试另一个账号，不要删表重试。
已有账号可以直接按 [登录说明](user-login.md#你现在怎么操作) 进行登录与退出。
旧的不带 CSRF 的 curl 命令现在返回 403，这是防护先于业务执行，不是数据库出了问题。
Cookie 临时文件不提交 Git，用完后按登录说明清理；不要把真实 token 或 Cookie 写入文档。

在 MySQL 查看结果时只查非敏感字段：

```sql
SELECT id, username, created_at FROM users;
```

创建时间在数据库中按 UTC 保存，可能与本地时钟相差 8 小时；客户端以后负责按时区展示。
不要使用 `SELECT *` 将密码哈希打印到终端或截图中。停止应用时按 Ctrl+C。
测试服务没有长期留在后台，手动试用时需要自己启动应用。

### 真实 MySQL 验收

普通离线测试使用 H2，不连接本机 MySQL。`UserRegistrationMySqlTests` 仅在环境变量
`RUN_MYSQL_REGISTRATION_TESTS=true` 时启用。可选验收脚本已移除，Java 验收类仍保留。
需要复现时，先在当前 CMD 配置 `MYSQL_TEST_USER`、`MYSQL_TEST_PASSWORD` 和可选
`MYSQL_TEST_URL`（仅本地普通账号，不提交凭据），再执行：

```cmd
set "RUN_MYSQL_REGISTRATION_TESTS=true"
mvn --offline -Ddebug=false -Dtest=UserRegistrationMySqlTests test
set "RUN_MYSQL_REGISTRATION_TESTS="
set "MYSQL_TEST_USER="
set "MYSQL_TEST_PASSWORD="
set "MYSQL_TEST_URL="
```

验收要求 Docker 和已有 MySQL 正常运行，仅在当前进程环境中传递密码，不输出密码、不写入文件；
连接 localhost 的 `collab_notes` 数据库，映射端口不是 3306 时通过 `MYSQL_TEST_URL` 指定。
不下载依赖、不建表、不删表。验收使用随机 `verify_` 前缀账号，事先确认没有同名记录，
结束时仅删除该测试账号。上面命令清除本次测试变量；不负责清理手动创建的学习账号。

验收时启动独立的随机端口 HTTP 服务，两个线程发送同名请求；检查一个 201、一个 409，
只有一行记录，保存的是可校验的 Argon2id 哈希，响应 UTC 时间与数据库一致，非法密码
返回 400。最初验收通过后精确删除了 1 个验收账号，当时核验表中记录数为 0。
后续验收类已扩展验证登录、Cookie 轮换、退出失效与不同设备会话独立性，同样通过；
只精确清理本次生成的临时账号，保留用户后来手动创建的学习账号。

这是双请求竞争正确性验证，不是大流量压测；数据库故障目前通过模拟异常检查错误分类，
没有停止容器进行真实故障注入。注册请求超时后不能假定数据库一定没写入，需查看结果。

当前仍是本地学习版本：已实现 Session 登录；尚未实现泄露密码检查、限流或防滥用；这些能力按后续
教学步骤设计。用户名占用的明确提示是当前接口约定，公开部署前需评估枚举风险。
HTTP 只限本机学习，跨设备或部署传输密码前需要 HTTPS；注册成功并不意味着已登录。

## 实现后的验收清单

1. 正常注册后只有一条用户记录，ID 由数据库生成。
2. 数据库存储的不是原始密码；库的校验函数可验证正确密码并拒绝错误密码。
3. 缺失、过长或非法用户名被拒绝，密码不进入响应或日志。
4. 两个并发请求注册相同用户名时，数据库最终只有一条记录，其余得到明确冲突结果。
5. UTC 创建时间读写正确，响应包含明确的时区信息。
6. MySQL 故障不会被转换为用户名冲突或成功响应。

以上检查已覆盖正常注册、存储及校验、输入错误、同名竞争、UTC 时间和错误分类。
最小注册步骤当时的完整离线报告为 49 项：48 项通过，1 项真实 MySQL 验收默认跳过；
登录步骤完成后为 70 项：69 项通过，1 项真实 MySQL 验收默认跳过。
随后单独启用扩展后的真实 MySQL 验收并通过。快速测试使用 H2，不能替代真实 MySQL 验收；
模拟数据库故障的响应测试不能当作实际断网、停库或恢复能力的证明。
