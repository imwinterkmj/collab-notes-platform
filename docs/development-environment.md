# 本地开发环境

## 核心原则

代码和构建工具在本地运行，数据库和中间件通过 Docker 运行。

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

```powershell
Copy-Item .env.example .env
```

启动 MySQL 前，将所有 `change_me` 替换为自己的密码。Docker Compose 会自动读取
`.env`。IntelliJ IDEA 的运行配置或启动 Maven 的终端，也需要设置相同的
`DB_USERNAME` 和 `DB_PASSWORD`。

不得提交 `.env`、真实密码或本机专用配置。

## 启动和停止 MySQL

启动并查看状态：

```powershell
docker compose up -d
docker compose ps
```

停止但保留数据：

```powershell
docker compose down
```

命名卷 `mysql_data` 会在普通重启和 `docker compose down` 后继续保存数据库文件。
除非明确希望删除全部本地数据，否则不要运行 `docker compose down -v`。

## 运行应用

可以从 IntelliJ IDEA 启动，也可以在当前终端临时设置环境变量后使用 Maven：

```powershell
$env:DB_USERNAME = 'collab_notes'
$env:DB_PASSWORD = '你的本地普通用户密码'
mvn spring-boot:run
```

应用默认连接 `localhost:3306`。健康检查命令：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

## 基础设施加入时间

- 阶段 0：只启动 MySQL。
- 阶段 4：只有测出缓存需求后才加入 Redis。
- 阶段 6：只有测出同步处理延迟后才加入 Kafka 或 RabbitMQ。
- 阶段 7：只有证明 MySQL 文本搜索无法满足需求后才加入 Elasticsearch 或 OpenSearch。

未进入对应阶段的中间件不得提前出现在 Compose 配置中。
