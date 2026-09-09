# collab-notes-platform

这是一个以架构演进为主线的多人协作知识库后端项目。项目从简单的 Spring Boot
单体应用开始，只有在发现并量化真实问题后，才引入下一项技术。

## 当前状态

阶段 0 已完成。仓库目前包含：

- Java 21 / Spring Boot 3.5 应用骨架；
- 只运行 MySQL 8.4.11 的 Docker Compose 配置；
- Spring Boot Actuator 健康检查；
- 基础测试。

本地构建、测试和 MySQL 连接均已验证。当前故意不包含任何业务 API。

详细信息：

- [阶段 0 说明](docs/phase-0.md)
- [本地开发环境](docs/development-environment.md)
- [项目总体规划](PROJECT_PLAN.md)
- [项目进度](PROGRESS.md)

## 环境要求

- Windows：JDK 21、Maven 3.9+、Git，可选 IntelliJ IDEA；
- Docker Desktop 与 Docker Compose。

代码、构建工具和 Spring Boot 应用在 Windows 本地运行，MySQL 等基础设施通过
Docker 运行。当前不将 Spring Boot 应用容器化。

## 配置本地凭据

首次使用时创建本地环境变量文件：

```powershell
Copy-Item .env.example .env
```

打开 `.env`，将其中的 `change_me` 替换为自己的本地密码。`.env` 已被 Git 忽略，
不得提交真实密码。

## 启动 MySQL

```powershell
docker compose up -d
docker compose ps
```

当状态显示 `healthy` 时，说明 MySQL 已完成初始化。

## 运行测试

```powershell
mvn test
```

测试使用 MySQL 兼容模式下的 H2 内存数据库，因此快速测试不依赖 Docker。

## 在本地运行应用

在 IntelliJ IDEA 的运行配置中设置 `DB_USERNAME` 和 `DB_PASSWORD`，或者在当前
PowerShell 会话中设置：

```powershell
$env:DB_USERNAME = 'collab_notes'
$env:DB_PASSWORD = '你的本地普通用户密码'
mvn spring-boot:run
```

检查健康状态：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

预期返回：

```json
{status:UP}
```

## 停止环境

停止应用时，在运行窗口按 `Ctrl+C`。

停止 MySQL 但保留数据：

```powershell
docker compose down
```

只有明确希望删除本地数据库全部数据时，才能使用 `docker compose down -v`。

## 目录结构

```text
src/main/java/com/collabnotes/platform  Spring Boot 应用根包
src/main/resources                     运行配置
src/test                               基础上下文、JDBC 和健康检查测试
docker                                 基础设施说明与后续支持文件
docs                                   架构演进和开发文档
```

## 演进边界

Redis、消息队列、微服务、搜索、分库分表和业务领域模型均不属于阶段 0。后续只有在
对应阶段发现并量化问题后，才能引入相关技术。
