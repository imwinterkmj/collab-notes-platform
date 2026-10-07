-- MVP 手动建表：先核验 collab_notes、users/notes 和目标表是否存在，不删旧表、不重建数据卷。
-- UTC DATETIME(6) 由应用提供；外键不能代替接口中的用户归属检查。
CREATE TABLE reminders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    note_id BIGINT NOT NULL,
    due_at DATETIME(6) NOT NULL,
    generation BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_reminders_note (note_id),
    KEY idx_reminders_due (status, due_at, id),
    CONSTRAINT fk_reminders_note FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
    CONSTRAINT ck_reminders_generation CHECK (generation > 0),
    CONSTRAINT ck_reminders_status CHECK (status IN ('SCHEDULED', 'FIRED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reminder_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uk_notifications_batch (reminder_id, generation),
    CONSTRAINT fk_notifications_reminder FOREIGN KEY (reminder_id) REFERENCES reminders(id) ON DELETE CASCADE,
    CONSTRAINT ck_notifications_read CHECK (is_read IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
