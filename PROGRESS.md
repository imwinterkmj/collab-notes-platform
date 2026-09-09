# 项目进度

## 当前阶段

阶段 0：项目初始化

## 状态

已完成

## 已完成事项

- 已确定项目定位和后端主语言 Java；
- 已确定使用 Java 21、Spring Boot 3.x、Maven 和 MySQL 8；
- 已确定长期架构演进路线；
- 已建立 `AGENTS.md` 和 `PROJECT_PLAN.md`；
- 已创建 Spring Boot 应用骨架；
- 已配置 Maven 项目与 Java 21 编译目标；
- 已配置 Actuator 健康检查端点；
- 已编写基础 JUnit 测试；
- 已编写只包含 MySQL 的 `docker-compose.yml`；
- 已确定“本地应用 + Docker 中间件”的开发环境边界；
- 已补充开发环境与阶段 0 文档；
- 已在 Windows 安装并验证 JDK 21.0.12.1；
- 已在 Windows 安装并验证 Maven 3.9.16；
- 已将 Maven 依赖仓库配置到 D 盘；
- 已固定并启动 MySQL 8.4.11 Docker 容器；
- 已创建 MySQL 持久化 Volume；
- 已通过基础 JUnit 测试；
- 已验证 Spring Boot 本地启动并连接 MySQL；
- 已验证 `/actuator/health` 返回 `UP`；
- 已初始化 Git 仓库并创建阶段 0 基线提交。

## 当前任务

无；等待用户决定何时开始阶段 1。

## 验收标准

阶段 0 已满足以下条件：

- Spring Boot 可以启动；
- MySQL 可以连接；
- 健康检查端点正常；
- JUnit 可以运行；
- Docker Compose 可以启动所需依赖；
- 项目结构清晰。

## 下一阶段

阶段 1：核心单体版本 V1。

在用户明确开始阶段 1 前，不添加核心业务代码。

## 重要决策

- 使用 Java 21 和 Spring Boot 3.x；
- 项目前期保持单体架构；
- 不提前微服务化；
- 不提前使用 Redis、消息队列或分库分表；
- 所有新技术必须先有真实问题和性能数据支撑；
- JDK、Maven 和 Spring Boot 应用在 Windows 本地运行；
- MySQL 以及后续中间件通过 Docker Compose 运行；
- 阶段 0 不创建 Spring Boot 应用镜像；
- 下载或安装新工具、镜像、依赖前先告知用户。

## 已知问题

暂无。
