package com.collabnotes.platform.note;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NoteTrashRepository {
    public static final int CAPACITY = 30;
    private final JdbcTemplate jdbc;
    private static final String COLUMNS = "id, title, content, is_completed, created_at, updated_at, deleted_at";
    private static final RowMapper<TrashNoteResponse> MAPPER = (rs, row) -> new TrashNoteResponse(
            rs.getLong("id"), rs.getString("title"), rs.getString("content"), rs.getBoolean("is_completed"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("deleted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    public NoteTrashRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // 删除/恢复都先锁当前用户，串行维护每个账号的 30 条上限，再锁备忘录；不依赖单 JVM 锁。
    public void lockOwner(long userId) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId).isEmpty()) {
            throw new NoteNotFoundException();
        }
    }

    public void archive(long userId, NoteResponse note, Instant deletedAt) {
        jdbc.update("INSERT INTO note_trash (user_id, title, content, is_completed, created_at, updated_at, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", userId, note.title(), note.content(), note.completed(),
                utc(note.createdAt()), utc(note.updatedAt()), utc(deletedAt));
    }

    public void trimOwned(long userId) {
        var expired = jdbc.queryForList("SELECT id FROM note_trash WHERE user_id = ? "
                + "ORDER BY deleted_at DESC, id DESC LIMIT 2147483647 OFFSET 30", Long.class, userId);
        for (long id : expired) { deleteOwned(id, userId); }
    }

    public List<TrashNoteResponse> listOwned(long userId) {
        // 最多 30 条，正文仅在用户主动打开回收站时读取，不加入主页备忘录列表。
        return jdbc.query("SELECT " + COLUMNS + " FROM note_trash WHERE user_id = ? "
                + "ORDER BY deleted_at DESC, id DESC LIMIT 30", MAPPER, userId);
    }

    public Optional<TrashNoteResponse> lockOwned(long id, long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM note_trash WHERE id = ? AND user_id = ? FOR UPDATE",
                MAPPER, id, userId).stream().findFirst();
    }

    public int deleteOwned(long id, long userId) {
        return jdbc.update("DELETE FROM note_trash WHERE id = ? AND user_id = ?", id, userId);
    }

    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
