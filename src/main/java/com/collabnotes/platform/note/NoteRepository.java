package com.collabnotes.platform.note;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class NoteRepository {
    private final JdbcTemplate jdbc;

    public NoteRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(long userId, String title, String content, Instant now) {
        return insert(userId, title, content, false, now, now);
    }

    public long insertRestored(long userId, TrashNoteResponse note, Instant now) {
        return insert(userId, note.title(), note.content(), note.completed(), note.createdAt(), now);
    }

    private long insert(long userId, String title, String content, boolean completed, Instant createdAt, Instant updatedAt) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO notes (user_id, title, content, is_completed, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, title);
            statement.setString(3, content);
            statement.setBoolean(4, completed);
            statement.setObject(5, LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            statement.setObject(6, LocalDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
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

    public List<NoteSummaryResponse> findOwnedPage(long userId, int limit, long offset) {
        return jdbc.query("SELECT id, title, is_completed, created_at, updated_at FROM notes "
                        + "WHERE user_id = ? ORDER BY is_completed ASC, updated_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new NoteSummaryResponse(rs.getLong("id"), rs.getString("title"),
                        rs.getBoolean("is_completed"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                userId, limit, offset);
    }

    public void updateOwnedById(long noteId, long userId, String title, String content, Instant now) {
        jdbc.update("UPDATE notes SET title = ?, content = ?, updated_at = ? WHERE id = ? AND user_id = ?",
                title, content, LocalDateTime.ofInstant(now, ZoneOffset.UTC), noteId, userId);
    }

    public void setCompletionOwnedById(long noteId, long userId, boolean completed, Instant now) {
        // updated_at 赋值须位于状态赋值之前，比较原状态；同值请求保持原时间。
        jdbc.update("UPDATE notes SET updated_at = CASE WHEN is_completed = ? THEN updated_at ELSE ? END, "
                        + "is_completed = ? WHERE id = ? AND user_id = ?",
                completed, LocalDateTime.ofInstant(now, ZoneOffset.UTC), completed, noteId, userId);
    }

    public int deleteOwnedById(long noteId, long userId) {
        return jdbc.update("DELETE FROM notes WHERE id = ? AND user_id = ?", noteId, userId);
    }
    public Optional<NoteResponse> lockOwnedById(long noteId, long userId) {
        return locked("id = ? AND user_id = ?", noteId, userId);
    }
    public Optional<NoteResponse> lockForDispatch(long noteId) { return locked("id = ?", noteId); }
    private Optional<NoteResponse> locked(String condition, Object... parameters) {
        // condition 仅由上面两个内部常量调用，不拼接客户端内容。
        return jdbc.query("SELECT id, title, content, is_completed, created_at, updated_at FROM notes WHERE "
                + condition + " FOR UPDATE",
                (rs, row) -> new NoteResponse(rs.getLong("id"), rs.getString("title"), rs.getString("content"),
                        rs.getBoolean("is_completed"), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                parameters).stream().findFirst();
    }
}
