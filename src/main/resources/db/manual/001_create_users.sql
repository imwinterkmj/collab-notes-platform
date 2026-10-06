-- 手动执行的阶段 1 建表脚本；不会在 Spring Boot 启动时自动执行。
-- 执行前确认目标数据库及 users 表是否已存在，禁止通过删表重复执行。
-- created_at 由后续注册代码按 UTC 提供；本脚本不插入任何用户数据。
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_as_cs;
