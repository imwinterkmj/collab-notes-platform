package com.collabnotes.platform.reminder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReminderRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ReminderResponse> MAPPER = (rs, row) -> new ReminderResponse(
            rs.getLong("id"), rs.getLong("note_id"),
            rs.getObject("due_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getLong("generation"), rs.getString("status"));
    public record Candidate(long id, long noteId, long generation) { }
    public ReminderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<ReminderResponse> findOwned(long noteId, long userId) {
        return jdbc.query("SELECT r.id, r.note_id, r.due_at, r.generation, r.status FROM reminders r "
                + "JOIN notes n ON n.id = r.note_id WHERE r.note_id = ? AND n.user_id = ?",
                MAPPER, noteId, userId).stream().findFirst();
    }
    // 调度器专用；调用方已锁定关联备忘录，不接受客户端归属参数。
    public Optional<ReminderResponse> findForDispatch(long noteId) {
        return jdbc.query("SELECT id, note_id, due_at, generation, status FROM reminders WHERE note_id = ?",
                MAPPER, noteId).stream().findFirst();
    }
    public void insert(long noteId, Instant dueAt, Instant now) {
        jdbc.update("INSERT INTO reminders (note_id, due_at, generation, status, created_at, updated_at) "
                + "VALUES (?, ?, 1, 'SCHEDULED', ?, ?)", noteId, utc(dueAt), utc(now), utc(now));
    }
    public void reschedule(long id, Instant dueAt, Instant now) {
        jdbc.update("UPDATE reminders SET due_at = ?, generation = generation + 1, status = 'SCHEDULED', "
                + "updated_at = ? WHERE id = ?", utc(dueAt), utc(now), id);
    }
    public void cancelPending(long noteId, Instant now) {
        jdbc.update("UPDATE reminders SET status = 'CANCELLED', updated_at = ? "
                + "WHERE note_id = ? AND status = 'SCHEDULED'", utc(now), noteId);
    }
    public List<Candidate> dueCandidates(Instant now, int limit) {
        return jdbc.query("SELECT id, note_id, generation FROM reminders WHERE status = 'SCHEDULED' "
                + "AND due_at <= ? ORDER BY due_at, id LIMIT ?",
                (rs, row) -> new Candidate(rs.getLong("id"), rs.getLong("note_id"), rs.getLong("generation")),
                utc(now), limit);
    }
    public void fire(ReminderResponse reminder, String title, Instant now) {
        jdbc.update("INSERT INTO notifications (reminder_id, generation, title, due_at, created_at, is_read) "
                + "VALUES (?, ?, ?, ?, ?, FALSE)", reminder.id(), reminder.generation(), title,
                utc(reminder.dueAt()), utc(now));
        int changed = jdbc.update("UPDATE reminders SET status = 'FIRED', updated_at = ? "
                + "WHERE id = ? AND generation = ? AND status = 'SCHEDULED'",
                utc(now), reminder.id(), reminder.generation());
        if (changed != 1) { throw new IllegalStateException("提醒状态在事务内发生变化"); }
    }
    private LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
