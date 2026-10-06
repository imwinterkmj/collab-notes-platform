-- 仅用于 H2 快速测试；H2 字符串长度按 UTF-16 计数，放宽标题列以测试 Unicode 码点校验。
-- MySQL 的实际列长度、CHECK 与外键必须另行验收，不把这个测试结构当成生产结构。
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
