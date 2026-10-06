-- 阶段 1 手动建表脚本：2026-10-07 已在本机 MySQL 8.4.11 执行并核验。
-- 不会随 Spring Boot 启动自动执行；执行前确认目标数据库、users 结构和 notes 是否已存在。
-- 禁止通过删表或删数据卷重复执行；本脚本不插入学习账号或备忘录。
-- user_id 由服务端从登录身份获取；外键不能代替接口的归属检查。
-- created_at / updated_at 均由应用按 UTC 提供，创建时两者相同。
CREATE TABLE notes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    is_completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notes_user_updated (user_id, updated_at DESC, id DESC),
    CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_notes_title_length CHECK (CHAR_LENGTH(title) BETWEEN 1 AND 120),
    CONSTRAINT ck_notes_content_length CHECK (CHAR_LENGTH(content) <= 10000),
    CONSTRAINT ck_notes_completed CHECK (is_completed IN (0, 1))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_as_cs;
