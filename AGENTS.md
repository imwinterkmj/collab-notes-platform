# 仓库工作规则

## 开发环境

- JDK 21、Maven、Git、IntelliJ IDEA 和 Spring Boot 应用统一在 Windows 本地环境运行。
- 不在 WSL2 中另行维护第二套 Java/Maven 工具链。
- 开发阶段优先通过 IntelliJ IDEA 启动应用，也可使用 `mvn spring-boot:run`。
- 在用户明确开始后续部署或容器化练习前，不得将 Spring Boot 应用容器化。
- 数据库和中间件通过 Docker Compose 运行，并使用精确的版本标签。
- 真实密码、令牌、`.env` 和本机专用配置不得提交到 Git。
- 下载或安装工具、镜像、依赖前，必须先告知用户并征得同意。

## 阶段门禁

- 阶段 0：只使用 MySQL。
- 阶段 4：只有测出缓存压力后才加入 Redis。
- 阶段 6：只有测出同步处理的延迟问题后才加入 Kafka 或 RabbitMQ。
- 阶段 7：只有证明 MySQL 文本搜索无法满足需求后才加入 Elasticsearch 或 OpenSearch。
- 不得提前引入后续阶段的基础设施。

## 当前范围

阶段 0 已完成，目前尚未授权开始阶段 1。代码库保持为单体 Spring Boot 应用，
只包含 MySQL 连接、健康检查和基础测试；暂不添加领域实体或业务 API。
