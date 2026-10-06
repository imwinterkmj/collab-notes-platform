package com.collabnotes.platform.note;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class NoteRepository {
    private final JdbcTemplate jdbc;

    public NoteRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(long userId, String title, String content, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO notes (user_id, title, content, is_completed, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, title);
            statement.setString(3, content);
            statement.setBoolean(4, false);
            var utc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
            statement.setObject(5, utc);
            statement.setObject(6, utc);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) { throw new IllegalStateException("数据库未返回备忘录 ID"); }
        return id.longValue();
    }

    public Optional<NoteResponse> findOwnedById(long noteId, long userId) {
        return jdbc.query("SELECT id, title, content, is_completed, created_at, updated_at FROM notes "
                        + "WHERE id = ? AND user_id = ?",
                (rs, row) -> new NoteResponse(rs.getLong("id"), rs.getString("title"),
                        rs.getString("content"), rs.getBoolean("is_completed"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                noteId, userId).stream().findFirst();
    }
}
