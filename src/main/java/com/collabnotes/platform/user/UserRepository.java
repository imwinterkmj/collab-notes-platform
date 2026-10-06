package com.collabnotes.platform.user;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final String INSERT_SQL =
            "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)";
    private static final Pattern MYSQL_USERNAME_KEY = Pattern.compile(
            "for key '(?:[^']+\\.)?uk_users_username'"
    );
    // 仅供已有的 H2 快速测试；真实 MySQL 的行为另行验收。
    private static final Pattern H2_USERNAME_KEY = Pattern.compile(
            "Unique index or primary key violation: \\\"(?:[^\\\"]+\\.)?UK_USERS_USERNAME(?:_INDEX_[A-Z0-9]+)? ON "
    );

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserCredentials> findByUsername(String username) {
        return jdbcTemplate.query("SELECT id, username, password_hash FROM users WHERE username = ?",
                (rs, row) -> new UserCredentials(rs.getLong("id"), rs.getString("username"),
                        rs.getString("password_hash")), username).stream().findFirst();
    }

    public long insert(String username, String passwordHash, Instant createdAt) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, username);
                statement.setString(2, passwordHash);
                // DATETIME 不带时区：显式写入 UTC 的年月日时分秒，不依赖 JVM 或数据库会话时区。
                statement.setObject(3, LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            if (isUsernameConflict(exception)) {
                throw new UsernameAlreadyExistsException();
            }
            throw exception;
        }
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("数据库未返回用户 ID");
        }
        return generatedId.longValue();
    }

    private boolean isUsernameConflict(DuplicateKeyException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getMessage() != null) {
                String state = sqlException.getSQLState();
                String message = sqlException.getMessage();
                if (sqlException.getErrorCode() == 1062 && "23000".equals(state)
                        && MYSQL_USERNAME_KEY.matcher(message).find()) {
                    return true;
                }
                if (sqlException.getErrorCode() == 23505 && "23505".equals(state)
                        && H2_USERNAME_KEY.matcher(message).find()) {
                    return true;
                }
            }
        }
        return false;
    }
}
