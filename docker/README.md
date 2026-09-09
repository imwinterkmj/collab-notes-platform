# Docker 支持说明

阶段 0 的 Docker 配置保持最小化：根目录的 `docker-compose.yml` 只运行 MySQL。
Spring Boot 应用及其 Java/Maven 工具链均在 Windows 本地开发环境运行。

Redis、消息队列和搜索服务只能在规划的对应阶段按需加入。应用容器化推迟到后续部署
练习。本目录可在阶段 1 引入第一个数据库结构时，用于存放数据库初始化支持文件。
