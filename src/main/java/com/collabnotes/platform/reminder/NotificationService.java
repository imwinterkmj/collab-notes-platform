package com.collabnotes.platform.reminder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    public NotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Page(List<NotificationResponse> items, int page, int size, boolean hasNext) {
        public Page { items = List.copyOf(items); }
    }
    public Page list(long userId, int page, int size) {
        return query(userId, page, size, false);
    }
    public Page listUnread(long userId, int page, int size) {
        return query(userId, page, size, true);
    }
    private Page query(long userId, int page, int size, boolean unreadOnly) {
        var rows = jdbc.query("SELECT f.id, r.note_id, f.title, f.due_at, f.created_at, f.is_read "
                + "FROM notifications f JOIN reminders r ON r.id = f.reminder_id JOIN notes n ON n.id = r.note_id "
                + "WHERE n.user_id = ? " + (unreadOnly ? "AND f.is_read = FALSE " : "")
                + "ORDER BY f.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new NotificationResponse(rs.getLong("id"), rs.getLong("note_id"), rs.getString("title"),
                        rs.getObject("due_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getBoolean("is_read")),
                userId, size + 1, (long) page * size);
        return new Page(rows.subList(0, Math.min(size, rows.size())), page, size, rows.size() > size);
    }
    @Transactional
    public void markRead(long id, long userId) {
        String owned = "EXISTS (SELECT 1 FROM reminders r JOIN notes n ON n.id = r.note_id "
                + "WHERE r.id = notifications.reminder_id AND n.user_id = ?)";
        jdbc.update("UPDATE notifications SET is_read = TRUE WHERE id = ? AND " + owned, id, userId);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id = ? AND " + owned,
                Long.class, id, userId);
        if (count == null || count == 0) {
            throw new ReminderException(404, "NOTIFICATION_NOT_FOUND", "通知不存在或不可访问");
        }
    }
}
