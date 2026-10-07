# Java 目录与代码职责入门

[文档导航](../README.md) · [阶段 1](../phase-1/README.md)

## 前面的长路径是什么

`src/main/java/com/collabnotes/platform` 不是很多独立应用，而是 Maven 的源码目录与 Java 的包路径：

- `src`：源码。
- `main/java`：运行应用所用的 Java 代码；`src/test/java` 是自动测试代码。
- `com/collabnotes/platform`：对应 `package com.collabnotes.platform;`，作为这个工程的基础包名。
  包名用点分隔，落到磁盘上就是对应的多层文件夹；用于组织类和避免重名，不表示联网地址，也不要求购买域名。
- `CollabNotesPlatformApplication.java`：启动入口，含 main 方法；这里的 main 方法和上面的 main 目录不是一回事。

这套标识先沿用，不因为产品改成备忘录就移动整套包名。启动类位于基础包下，Spring Boot 默认扫描其子包内的组件。

## 后面的目录按功能分类

| 包 | 负责什么 | 现有例子 |
| --- | --- | --- |
| `user` | 用户注册、账号记录、用户名和密码哈希的存取。 | UserRegistrationService、UserRepository |
| `auth` | 登录/退出、验证身份、当前用户和 Session 认证衔接。 | AuthController、JsonLoginFilter、AuthenticatedUser |
| `note` | 备忘录内容、列表、完成状态、回收站及统一保存。 | NoteService、NoteSaveService、NoteTrashRepository |
| `reminder` | 一次性提醒、到期调度及其站内通知。 | ReminderService、ReminderDispatcher、NotificationService |
| `config` | 全局安全与密码哈希等配置。 | SecurityConfiguration、PasswordHashConfiguration |

`user` 和 `auth` 关联但不相同：前者关心“账号数据怎样创建和存储”，后者关心“这次请求是谁、是否已登录”。
站内通知目前是提醒的结果，所以与提醒放在同一个包；不必现在就额外拆出一个服务。
这些都是同一个 Spring Boot 单体里的分类，不是微服务，不需要分别启动。

## 一个功能内，文件再按职责分工

以点击“保存”为例，请求大致经过：网页 → NoteSaveController → NoteSaveService → 相关 Repository → MySQL。

- `Controller`：接收 HTTP 请求、读取登录身份、触发输入校验、返回响应，不负责写 SQL。
- `Service`：组织业务规则和事务，例如标题正文与提醒要一起提交或一起回滚。
- `Repository`：执行 SQL、限定归属、把数据库记录转换成 Java 对象。
- `Request` / `Response`：定义请求和返回字段，例如 SaveNoteRequest、SavedNoteResponse。
- `Exception` / `ExceptionHandler`：表达业务失败并转换成一致的错误响应。
- 提醒的 `Scheduler` / `Dispatcher`：分别负责定时发起扫描与事务内处理任务，不是另一套应用。

本项目是“先按功能归类，再在功能内按职责分工”。还有一种按 controller/service/repository 建全局目录的方式，
也可以使用；当前方式让同一个功能的相关代码集中。分类不等于禁止互相调用：统一保存会协调备忘录和提醒的服务。

文件数量多不代表更高级：简单逻辑不必额外加接口、实现类或空文件夹；当前 JDBC 项目也不必为了模仿其他项目再添一套 JPA Entity。
后续出现真正的新业务职责时再增加目录，不提前生成设备推送、缓存或消息队列模块。

## 其他相关代码在哪

网页 HTML/CSS/JavaScript 在 `src/main/resources/static`；配置在 `src/main/resources/application.yml`，
手动 SQL 在 `src/main/resources/db/manual`。测试在 `src/test/java`，前端逻辑检查在 `src/test/frontend`。
测试与业务代码分开，新增 NoteSaveMySqlTests 不影响正常启动；真实数据库测试默认跳过，只有明确开启才运行。
