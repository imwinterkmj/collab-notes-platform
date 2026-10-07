# 阶段 1 · 第六个小目标：标记完成与恢复未完成

本文件保留完成状态小目标当时的接口与测试记录；随后已增加 [MVP 网页与提醒](mvp.md)，
目前标记完成会在同一事务取消待执行提醒，恢复未完成不恢复旧提醒。优先通过网页统一验收。

## 接口与范围

`PATCH /api/notes/{id}/completion` 设置本人备忘录的目标状态。请求只有 `completed`：

```json
{"completed": true}
```

true 表示完成，false 表示未完成；必须是 JSON 布尔值，不接受字符串、数字、null、缺失或额外字段。
明确设置状态，不使用“每请求一次翻转一次”，避免网络重试把状态又翻回去。
成功返回 200 和原有六字段详情；标题、正文、归属、ID、创建时间保持不变。
只有实际状态变化才更新 UTC 的 updatedAt；当前状态已经相同，则更新时间与内容都保持不变。
同值重复提交不是新记录，也不是新一次状态切换。

需要登录 Cookie 与当前会话 CSRF。无效 ID/请求返回 400，未登录且有有效 CSRF 返回 401，
缺失/错误 CSRF 返回 403，非 JSON 返回 415；不存在与属于别人统一 404，数据库异常脱敏 500。
这是唯一允许客户端修改完成状态的入口；创建与编辑请求仍拒绝 completed 等服务端字段。
完成状态小目标当时不改表、不下载依赖、不加入中间件，不实现删除、提醒、筛选或界面。
当时尚无提醒，不能宣称已取消任务；目前 MVP 已补齐完成与取消待执行提醒的事务关系，见上方链接。

## SQL 与事务

```sql
UPDATE notes
SET updated_at = CASE WHEN is_completed = ? THEN updated_at ELSE ? END,
    is_completed = ?
WHERE id = ? AND user_id = ?;
```

两个状态参数为同一个目标值，时间由服务端提供，归属只来自会话；不拼接用户输入。
先计算更新时间再赋完成状态，避免 MySQL 按赋值顺序求值时比较到已经覆盖后的状态。
状态是否变化直接在同一 UPDATE 内判断，不先读状态再翻转；更新和随后读取本人详情放在同一事务。
读取失败回滚，不依赖驱动返回匹配行数还是实际修改行数；同值更新不能误报 404。

这只保证当前请求的原子性，不实现版本号或跨端冲突检测；另一个设备后续设置相反状态仍可能覆盖。
阶段 3 再用并发实验研究版本和竞争。不把重复请求验证称为高并发容量测试。

## 改动与自动验收

- `UpdateNoteCompletionRequest` 与 `StrictBooleanDeserializer`：只接受必填布尔状态，拒绝多余字段。
- `NoteController.setCompletion`：路由、ID 校验和当前登录身份。
- `NoteService.setCompletion`：服务端时间、事务以及不可访问时统一 404。
- `NoteRepository.setCompletionOwnedById`：参数化条件 UPDATE，保留文本和创建时间。
- `NoteCompletionIntegrationTests`：正常切换、重复设置、列表/详情、输入边界、用户隔离、Session/CSRF。
- `NoteUpdateTransactionTests`：实际执行 H2 状态 UPDATE 后模拟读取失败，验证状态与时间回滚。
- `NoteErrorTests`：数据库异常与非法状态输入不在响应或日志泄露 SQL 和私密信息。

2026-10-07 新增 40 项快速测试：36 项完成集成、2 项故障回滚、2 项异常脱敏。
最终离线回归共 218 项：214 项通过，4 项真实 MySQL 案例默认跳过，0 失败、0 错误。
3 项真实 MySQL 备忘录综合验收单独通过；本轮精确清理 9 条备忘录、6 个临时账号，
原有记录数量与 ID 合计不变，保留用户学习数据。真实注册案例本轮未重复执行。
另以框架 DEBUG 单独通过 8 项异常/校验脱敏测试；日常不建议启用凭据链路 DEBUG/TRACE。
MySQL 验证正常提交、重复提交的实际时间与归属行为；故障注入在 H2 验证，不冒充真实 MySQL 故障注入。
以上为该小目标当时的测试数量；最新 MVP 测试及网页统一验收见[项目进度](../../PROGRESS.md)。

## 手动验收（CMD）

如果旧应用还在运行，先在它的 CMD 窗口按 Ctrl+C；Docker 保持运行，不重建表、不重新注册。
在第一个 CMD 从根目录启动新代码，并让这个窗口保持运行：

```cmd
cd /d D:\1A-project\collab-notes-platform
docker compose up -d --pull never mysql
for /f "usebackq tokens=1,* delims==" %A in (".env") do @set "%A=%B"
mvn --offline spring-boot:run -Dspring-boot.run.arguments=--debug=false
```

看到应用成功启动后，另开第二个 CMD。重启会使旧内存会话失效，必须重新登录：

```cmd
cd /d D:\1A-project\collab-notes-platform
curl.exe -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

填入刚返回的 token 并登录已有学习账号；示例文件仅为公开学习凭据，自己的账号使用本机输入：

```cmd
set "CSRF=这里替换成刚返回的token"
curl.exe -i -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/register-example.json" http://localhost:8080/api/auth/login
curl.exe -i -b "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/me
```

两次预期均为 200。登录会更新会话和 CSRF，后面必须再获取一次 token；不要继续使用登录前的值。
详细说明见[登录文档](user-login.md#你现在怎么操作)，本轮测试结束前不要退出。
使用本人备忘录的实际 ID；HTTP 示例保存在 `docs/phase-1/http/`，不包含任何真实凭据。

```cmd
cd /d D:\1A-project\collab-notes-platform
set "NOTE_ID=这里替换成本人备忘录的实际id"
curl.exe -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

填入刚返回的 token，然后标记完成：

```cmd
set "CSRF=这里替换成刚返回的token"
curl.exe -i -X PATCH -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/complete-note-example.json" http://localhost:8080/api/notes/%NOTE_ID%/completion
curl.exe -i -b "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/notes/%NOTE_ID%
```

预期两次都是 200，completed=true，详情与修改响应一致。重复执行同一 PATCH，updatedAt 保持不变。
再恢复未完成并查看列表：

```cmd
curl.exe -i -X PATCH -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/reopen-note-example.json" http://localhost:8080/api/notes/%NOTE_ID%/completion
curl.exe -i -b "%TEMP%\collab-notes-session.txt" "http://localhost:8080/api/notes?page=0&size=2"
```

预期 200，completed=false；标题、正文、创建时间均不变。状态确实变化时更新 updatedAt。
本步会改变选中学习记录的状态，不会新建记录。401 重新登录，403 检查 CSRF；不要关闭防护。
