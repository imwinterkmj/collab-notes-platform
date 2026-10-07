# 阶段 1 · 第五个小目标：编辑本人备忘录

## 范围与接口

`PUT /api/notes/{id}` 替换可编辑内容，必须同时提供 `title` 和 `content`，不是部分字段 PATCH。
规则与创建一致：标题 1～120 个 Unicode 码点且不能全空白；正文 0～10000 个码点，允许空字符串，
但不能缺失或为 null；必须是 JSON 字符串，保留空格、换行和 emoji，拒绝额外字段。

需要登录 Cookie 和当前会话 CSRF；归属只取服务端登录身份，不相信请求中的 userId。
成功返回 200 和详情的六个字段。只修改标题、正文及服务端 UTC 更新时间，保留 ID、归属、
创建时间和完成状态。重复 PUT 不创建新记录，但成功提交会刷新更新时间，不保证响应字节相同。
不存在的记录不会自动创建；不实现完成切换、删除、提醒或客户端界面。

| 情况 | 结果 |
| --- | --- |
| 合法请求、本人记录 | 200，返回更新后的详情。 |
| 不存在或属于别人 | 相同的 404 / NOTE_NOT_FOUND。 |
| 非法 ID、输入或额外字段 | 脱敏 400，不回显私人内容。 |
| 有效 CSRF，但未登录 | 401。 |
| CSRF 缺失或错误 | 403，防护先于业务。 |
| 非 JSON 请求 | 415。 |
| 数据库异常 | 脱敏 500，不能假装成功或不存在。 |

## SQL 与事务

```sql
UPDATE notes
SET title = ?, content = ?, updated_at = ?
WHERE id = ? AND user_id = ?;
```

所有值绑定参数，归属条件直接在 SQL 中，不先取别人正文再检查权限。
`NoteService.update` 使用 Spring 事务：更新与读取本人详情共同提交，读取失败也回滚更新。
不只靠更新行数为 0 判定不存在：驱动可能返回实际修改行数而不是匹配行数，同值更新也可能
没有实际修改。通过同一事务中的用户范围 SELECT 判定，不存在才返回 404。

事务不等于编辑冲突检测；当前没有版本号或乐观锁，两端编辑仍可能最后写入覆盖前一端。
阶段 3 再用实验研究更新丢失。本步不新增表、索引、依赖或中间件。
正文是纯文本，后续客户端必须按文本展示或转义，不能直接作为 HTML 渲染。

## 手动验收（CMD）

先重启旧应用加载新代码，然后按[登录说明](user-login.md#你现在怎么操作)重新登录。
Cookie 文件存在不代表仍登录；确认 `/api/auth/me` 返回 200。使用本人记录的实际 ID，
下面操作会修改选中的学习记录，不会新建记录；示例 JSON 只含公开学习内容。

```cmd
cd /d D:\1A-project\collab-notes-platform
chcp 65001
set "NOTE_ID=这里替换成本人备忘录的实际id"
curl.exe -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/auth/csrf
```

把刚返回的 token 填入变量再执行：

```cmd
set "CSRF=这里替换成刚返回的token"
curl.exe -i -X PUT -b "%TEMP%\collab-notes-session.txt" -c "%TEMP%\collab-notes-session.txt" -H "X-CSRF-TOKEN: %CSRF%" -H "Content-Type: application/json" --data-binary "@docs/phase-1/http/update-note-example.json" http://localhost:8080/api/notes/%NOTE_ID%
curl.exe -i -b "%TEMP%\collab-notes-session.txt" http://localhost:8080/api/notes/%NOTE_ID%
```

两次预期 200、内容一致；ID、createdAt 和 completed 保留原值，标题/正文按示例更新，
updatedAt 为本次服务端时间。查看[列表](notes-list.md)，同一 ID 显示新标题并按更新时间排序。
401 先检查重新登录；403 检查当前会话 CSRF，不关闭防护。不提交或发送 Cookie/token。

## 代码职责与验收

- `UpdateNoteRequest`：严格输入校验，拒绝服务端字段，toString 不含私人标题或正文。
- `NoteController.update`：路由、ID 校验和会话身份。
- `NoteService.update`：服务端时间、事务、统一不可访问 404。
- `NoteRepository.updateOwnedById`：用户范围参数化 UPDATE，读取复用详情方法。
- `NoteUpdateIntegrationTests`：输入边界、归属、Cookie/CSRF、同值重复提交、保留原字段和列表展示。
- `NoteUpdateTransactionTests`：真实执行 H2 UPDATE 后模拟读取故障，检查文字和时间全部回滚。
- `NoteErrorTests`：模拟数据库异常与输入错误，不在响应或日志泄露私密信息。

2026-10-07 新增 40 项快速验证：36 项编辑集成、2 项事务回滚、2 项异常与日志脱敏。
最终离线报告共 177 项：174 项通过，3 项真实 MySQL 案例默认跳过，0 失败、0 错误。
两项真实备忘录 MySQL 综合验收单独通过，含本次编辑案例；真实注册案例未在本轮重复运行。
H2 故障注入证明读取失败时回滚，不冒充真实 MySQL 故障注入；MySQL 验证正常提交、归属、
UTC 时间、原字段保留、Unicode 长度边界、同值重复提交不新建和不影响其他记录。
本轮真实验收精确清理 6 条备忘录、4 个临时账号，原有记录保留。另以框架 DEBUG 单独通过
6 项异常/校验脱敏测试，不建议日常启用凭据链路 DEBUG/TRACE。
用户已确认编辑手动测试通过。下一小目标为[标记完成与恢复未完成](notes-completion.md)。
以上测试数量记录编辑步骤当时的结果，最新结果见[项目进度](../../PROGRESS.md)；测试通过不是高并发成绩。
