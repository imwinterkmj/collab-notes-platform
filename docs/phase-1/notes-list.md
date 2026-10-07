# 阶段 1 · 第四个小目标：本人备忘录分页列表

## 小目标与接口约定

不再要求用户记住备忘录 ID；登录后一次查看一页自己的标题，再按 ID 打开详情。
本步只增加 `GET /api/notes`，不实现编辑、删除、完成切换、筛选、搜索或提醒。
不下载依赖、不改表、不修改认证规则；沿用现有联合索引和 JDBC。

请求示例：`GET /api/notes?page=0&size=20`。

| 参数 | 规则 |
| --- | --- |
| `page` | 从 0 开始，默认 0；允许 0～10000。 |
| `size` | 默认 20；允许 1～100。 |

省略或空参数采用默认值；其他非法数字、越界或整数溢出返回脱敏 400。
这两个上限是学习版本的资源限制，不是实测容量结论。当前不接受自定义排序或用户选择；
其他查询参数被忽略，传入 `userId` 不能改变数据归属。

需要已登录的 Cookie；GET 不要求 CSRF。匿名请求返回 401，数据库故障返回 500，
不能假装成空列表。没有数据或超出实际末页返回 200 和空 `items`。

响应只有 `items`、`page`、`size`、`hasNext`；每条记录只包含 `id`、`title`、`completed`、
`createdAt`、`updatedAt`，不返回正文、所属用户或账号信息。时间继续用 UTC `Z` 格式。
标题仍是私人数据，响应对象的 `toString` 脱敏，不记录完整成功响应。

## 分页、排序与用户隔离

```sql
SELECT id, title, is_completed, created_at, updated_at
FROM notes
WHERE user_id = ?
ORDER BY is_completed ASC, updated_at DESC, id DESC
LIMIT ? OFFSET ?;
```

归属参数只能来自已验证的登录身份。其余参数绑定而不是拼接，偏移量按 long 计算为
`page * size`；一次多取一条，即 LIMIT 为 `size + 1`，判断是否有下一页后去掉多出的记录。
这样只执行一次查询，不查询总数，也不宣称支持总页数或跳转最后一页。

当前按用户要求改为未完成优先，同状态按更新时间倒序、再按 ID 倒序；排序在 SQL 分页前执行。
原小目标只按更新时间/ID 排序，历史验收记录保留；新的规则见 [编辑与回收站](editor-and-trash.md)。
现有 `(user_id, updated_at DESC, id DESC)` 索引不能完全覆盖新增排序，本次不新增索引，不声称已经测得优化收益。

这是简单的 OFFSET 分页：没有并发变更时可稳定翻页；翻页期间新增或更新可能造成重复或
遗漏，深分页也可能扫描大量记录。后续数据库基线阶段再测量并比较游标分页；不能把
确定排序等同于跨请求的数据快照。单条读取不增加跨请求事务。

## 验收覆盖

- 默认参数、空列表、第一页、中间页、末页、超出末页、最大合法参数。
- 更新时间倒序和相同时间的 ID 倒序；静态数据翻页没有重复或遗漏。
- 每页不超过 size、hasNext 正确、标题保留中文和 emoji、列表不返回正文。
- 未登录 401；另一个账号只能看到自己的列表；伪造 userId 无效。
- 负数、零 size、超上限、非整数、溢出及类似 SQL 注入的参数返回 400。
- 数据库异常返回脱敏 500；响应和日志不泄露 SQL、密码或私密正文。
- 快速测试使用 H2；真实 MySQL 单独验收，只清理本次随机测试账号和备忘录。

## 实现与自动验收结果

2026-10-07 已实现并验证：

- `NoteController.list`：获取会话身份、绑定分页参数并校验范围。
- `NoteService.list`：计算偏移量、多取一条并返回 hasNext。
- `NoteRepository.findOwnedPage`：单次参数化 SQL、限定所属用户、确定排序、不加载正文。
- `NoteSummaryResponse` / `NotePageResponse`：列表摘要、不可变页内容与脱敏 toString。
- `NoteExceptionHandler`：分页非法返回通用 400，数据库故障仍返回脱敏 500。

新增 25 项快速测试：24 项分页集成验证、1 项错误响应与日志验证。
分页小目标完成时离线报告共 136 项：134 项通过，2 项真实 MySQL 验收默认跳过，0 失败、0 错误。
扩展后的 `NoteMySqlTests` 已单独通过，覆盖真实 SQL 分页、排序、字段、时间和隔离；
精确清理本轮 3 条备忘录和 2 个临时账号，原有用户/备忘录数量与 ID 合计保持不变。
未重建表、未删卷、未更改用户手动创建的数据；测试 HTTP 服务随机端口，结束后自动关闭。
另以框架 DEBUG 配置单独通过 4 项异常/输入校验脱敏测试，其中包含本次列表故障测试。

当前不返回总数，不支持筛选或自定义排序，不保证并发变更时跨页快照，不宣称已测出高并发容量。
分页代码先留在本地，待手动验收后独立提交；GitHub 里程碑 `55dc2bb` 是此前的创建/详情版本。
用户已重新登录并确认本人列表第一页返回 200；边界验证由自动测试覆盖，当前还未再次提交 GitHub。

## 手动验收（CMD）

更新代码后重启原应用；重启会使内存会话失效，按[登录说明](user-login.md)
重新登录。以下假定 Cookie 已保存在 `%TEMP%\collab-notes-session.txt`：

```cmd
curl.exe -i -b "%TEMP%\collab-notes-session.txt" "http://localhost:8080/api/notes?page=0&size=2"
curl.exe -i -b "%TEMP%\collab-notes-session.txt" "http://localhost:8080/api/notes?page=1&size=2"
curl.exe -i -b "%TEMP%\collab-notes-session.txt" "http://localhost:8080/api/notes?page=-1&size=2"
curl.exe -i "http://localhost:8080/api/notes?page=0&size=2"
```

预期依次为 200、200、400、401。CMD 中含 `&` 的 URL 必须放进双引号。
实际列表取决于自己的记录数；只有一条时第二页为空且 hasNext=false，这不是错误。
不需要为验收反复注册、重新建表或生成大量数据。
