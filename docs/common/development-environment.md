# 本地开发环境

## 核心原则

代码和构建工具在本地运行，数据库和中间件通过 Docker 运行。
用户日常使用 Windows CMD；本文所有终端命令均使用 CMD 语法，不要求切换终端。

```text
Windows
├── IntelliJ IDEA
├── JDK 21
├── Maven
├── Git
├── Spring Boot 应用
└── Docker Desktop
    ├── MySQL（阶段 0）
    ├── Redis（阶段 4，目前不安装）
    ├── Kafka 或 RabbitMQ（阶段 6，目前不安装）
    └── Elasticsearch 或 OpenSearch（阶段 7，目前不安装）
```

应用在本地运行，便于日常编辑、调试、测试和观察 Java 工具链。MySQL 等有状态的
基础设施使用 Docker，以固定版本和生命周期，同时避免在 Windows 中安装额外服务。

## 跨端使用与部署边界

产品目标是电脑和手机使用同一账号管理备忘录与提醒。当前阶段 0 只验证本地后端，尚未
实现客户端界面、同步或系统推送。

`localhost` 指当前设备本身：手机上的 `localhost` 并不是开发电脑。后续进行手机联调时，
需要规划可访问的服务地址；正式跨网络使用时，需要部署后端并配置 HTTPS。

提醒调度必须运行在持续在线的服务上，开发电脑关机时本地 Spring Boot 无法继续处理任务。
数据库保存任务后，后续实现可以在服务恢复时按约定处理过期任务，但不能补回停机期间的
准时通知。关闭页面后的系统提醒需要专门的推送能力，不能只依赖页面计时器。

网页/PWA 为当前客户端建议，具体设备支持和推送渠道在接入前确认。本次文档调整不会
新增客户端工具、部署环境或中间件。

## 阶段 0 的组件

| 组件 | 运行位置 | 用途 |
| --- | --- | --- |
| JDK 21 | Windows | 编译并运行应用。 |
| Maven 3.9+ | Windows | 解析依赖、测试、打包和运行 Spring Boot。 |
| Spring Boot 应用 | Windows | 日常开发和调试。 |
| MySQL 8.4.11 | Docker Desktop | 开发数据库，使用 Volume 持久化数据。 |

不要在 WSL2 中重复维护另一套 Java 和 Maven。本阶段不把应用打包为 Docker 镜像。

## Maven 依赖目录

本机 Maven 的个人配置文件位于：

```text
C:\Users\10362\.m2\settings.xml
```

依赖仓库已配置到：

```text
D:\DevTools\maven-repository
```

该配置属于本机环境，不应复制到项目或提交到 Git。

## 凭据和本地配置

仓库中的 `.env.example` 只包含变量名和非敏感占位值。首次使用时创建不受 Git 管理的
`.env`：

```cmd
cd /d D:\1A-project\collab-notes-platform
if not exist .env copy .env.example .env
```

启动 MySQL 前，将所有 `change_me` 替换为自己的密码。Docker Compose 会自动读取
`.env`。IntelliJ IDEA 的运行配置或启动 Maven 的终端，也需要设置相同的
`DB_USERNAME` 和 `DB_PASSWORD`。

不得提交 `.env`、真实密码或本机专用配置。

## 启动和停止 MySQL

启动并查看状态：

```cmd
docker compose up -d --pull never mysql
docker compose ps
```

停止但保留数据：

```cmd
docker compose down
```

命名卷 `mysql_data` 会在普通重启和 `docker compose down` 后继续保存数据库文件。
除非明确希望删除全部本地数据，否则不要运行 `docker compose down -v`。

## 运行应用

可以从 IntelliJ IDEA 启动，也可以在 CMD 加载已有 `.env` 后使用 Maven：

```cmd
cd /d D:\1A-project\collab-notes-platform
for /f "usebackq tokens=1,* delims==" %A in (".env") do @set "%A=%B"
mvn --offline spring-boot:run -Dspring-boot.run.arguments=--debug=false
```

此命令只适用于本机简单的 `KEY=value` 格式，不支持复杂引号或多行值。CMD 终端的 `for`
使用 `%A`，写入批处理文件时改为 `%%A`；不要打印包含真实密码的环境变量。

应用默认连接 `localhost:3306`。健康检查命令：

```cmd
curl.exe http://localhost:8080/actuator/health
```

## 基础设施加入时间

- 阶段 0：只启动 MySQL。
- 阶段 4：只有测出缓存需求后才加入 Redis。
- 阶段 6：只有测出同步处理延迟后才加入 Kafka 或 RabbitMQ。
- 阶段 7：只有证明 MySQL 文本搜索无法满足需求后才加入 Elasticsearch 或 OpenSearch。

未进入对应阶段的中间件不得提前出现在 Compose 配置中。
