-- 仅用于 H2 快速测试；H2 字符串长度按 UTF-16 计数，放宽标题列以测试 Unicode 码点校验。
-- MySQL 的实际列长度、CHECK 与外键必须另行验收，不把这个测试结构当成生产结构。
CREATE TABLE IF NOT EXISTS note_trash (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    content TEXT NOT NULL,
    is_completed BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS notes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    content TEXT NOT NULL,
    is_completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE IF NOT EXISTS reminders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    note_id BIGINT NOT NULL UNIQUE,
    due_at DATETIME(6) NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SCHEDULED', 'FIRED', 'CANCELLED')),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reminder_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    title VARCHAR(240) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE CHECK (is_read IN (TRUE, FALSE)),
    UNIQUE (reminder_id, generation),
    FOREIGN KEY (reminder_id) REFERENCES reminders(id) ON DELETE CASCADE
);
