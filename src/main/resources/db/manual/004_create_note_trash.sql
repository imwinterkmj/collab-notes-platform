-- 手动新增回收站表；不自动迁移、不改 notes/users、不删除既有数据。
-- 执行前确认 collab_notes 与用户表，并核验 note_trash 尚不存在；不要通过删表重跑。
-- 每个账号最近删除的 30 条由应用事务维护；第 31 条会淘汰最早的副本，不是 30 天。
-- 无 notes 外键：原记录会移除且级联清理提醒/通知。恢复新建 ID，保留内容/完成/创建时间，不恢复旧提醒。
CREATE TABLE note_trash (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    is_completed BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NOT NULL,
    KEY idx_note_trash_user_deleted (user_id, deleted_at DESC, id DESC),
    CONSTRAINT fk_note_trash_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_note_trash_title CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 120),
    CONSTRAINT ck_note_trash_content CHECK (CHAR_LENGTH(content) <= 10000),
    CONSTRAINT ck_note_trash_completed CHECK (is_completed IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
