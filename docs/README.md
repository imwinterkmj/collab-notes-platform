# 文档导航

文档按阶段分类，根目录只保留项目说明、规划、进度和仓库规则。
先查看[项目进度](../PROGRESS.md)，再进入当前阶段的小目标文档。

- 通用说明：[本地开发环境](common/development-environment.md)、[安卓 App 开发环境](common/mobile-development-environment.md)、[Java 目录与代码职责入门](common/code-structure.md)、[业务范围与提醒设计](common/product-scope.md)、[从 localhost 到跨端使用](common/deployment-route.md)。
- [阶段 0：项目初始化](phase-0/README.md)，记录已经完成的环境基线。
- [阶段 1：备忘录与一次性提醒 V1](phase-1/README.md)，按小目标阅读设计、实现和验收。

后续进入新阶段时再建立 `phase-2/` 等目录，不预先建立未使用的文档或引入基础设施。
每阶段维护 `README.md` 导航；手动测试请求放在该阶段的 `http/`，命令从仓库根目录执行。
手动 SQL 仍在 `src/main/resources/db/manual/`，不移动到文档目录，不会自动执行。
