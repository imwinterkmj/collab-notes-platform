# 阶段 0：项目初始化

## 与新业务定位的关系

项目规划现已调整为面向电脑和手机的备忘录与定时提醒平台。阶段 0 建立的是通用工程
基础，现有启动类、依赖、数据库连接配置和健康检查可以继续使用。

本次只修改文档，不重命名仓库、Java 包或数据库，也不创建业务表。以下验收结果为阶段 0
已有记录，不代表备忘录或提醒功能已经实现。

## 阶段范围

本阶段只建立可运行的工程基础：

- Java 21 与 Spring Boot 3.5；
- Maven 构建与 JUnit 5 测试；
- 通过环境变量连接 MySQL 8；
- Spring Boot Actuator 健康检查端点；
- 只包含 MySQL 的 Docker Compose 环境。

本阶段不包含业务实体、Repository、Service、Controller、身份认证、Redis、消息队列、
搜索引擎、应用 Docker 镜像或数据库迁移脚本。

## 依赖选择及原因

| 依赖 | 在阶段 0 中的用途 |
| --- | --- |
| Spring Web | 运行 HTTP 服务，为健康检查和后续 REST API 提供基础。 |
| Actuator | 提供标准的 `/actuator/health` 端点，并检查数据库连接状态。 |
| JDBC | 在尚未确定 ORM 领域模型前，创建并验证 MySQL 连接池。 |
| MySQL Connector/J | 提供运行环境使用的 MySQL 驱动。 |
| Validation | 为后续 DTO 的输入校验建立基础。 |
| H2（仅测试） | 让基础测试快速运行，不要求本机同时启动 MySQL。 |

## 配置边界

应用通过 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD` 读取数据库连接信息。Docker
Compose 从不受 Git 管理的 `.env` 文件读取本地凭据。真实凭据必须从外部提供，
不得提交到 Git。

`src/test/resources/application.yml` 会在测试期间覆盖运行环境配置，将 MySQL 替换为
启用 MySQL 兼容模式的 H2 内存数据库。该测试用于验证 Spring 上下文、HTTP 服务、
健康检查和 JDBC 连接。将本地应用连接到 Compose 中的 MySQL，则用于验证真实的
MySQL 驱动和连接链路。

## 验收结果

1. `mvn test` 已在 Java 21 下通过。
2. `docker compose up -d` 只启动一个健康的 `mysql` 服务。
3. `mvn spring-boot:run` 能在本地启动应用并连接该数据库。
4. `GET http://localhost:8080/actuator/health` 返回 HTTP 200 和 `UP`。
5. `.env` 已被 Git 忽略，未进入版本库。
