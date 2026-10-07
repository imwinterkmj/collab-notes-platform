package com.collabnotes.platform.reminder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;

import com.collabnotes.platform.note.NoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReminderService {
    private final NoteRepository notes;
    private final ReminderRepository reminders;
    public ReminderService(NoteRepository notes, ReminderRepository reminders) {
        this.notes = notes;
        this.reminders = reminders;
    }
    public ReminderResponse get(long noteId, long userId) {
        return reminders.findOwned(noteId, userId).orElseThrow(ReminderException::missing);
    }
    @Transactional
    public ReminderResponse set(long noteId, long userId, SetReminderRequest request) {
        var note = notes.lockOwnedById(noteId, userId).orElseThrow(ReminderException::missing);
        if (note.completed()) { throw new ReminderException(409, "NOTE_COMPLETED", "请先恢复未完成，再设置提醒"); }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant due;
        try { due = OffsetDateTime.parse(request.dueAt()).toInstant().truncatedTo(ChronoUnit.MICROS); }
        catch (DateTimeParseException exception) {
            throw new ReminderException(400, "INVALID_TIME", "提醒时间须为带时区的 ISO 时间");
        }
        if (!due.isAfter(now) || due.isAfter(now.plus(365, ChronoUnit.DAYS))) {
            throw new ReminderException(400, "INVALID_TIME", "提醒时间须在未来一年内");
        }
        var existing = reminders.findOwned(noteId, userId);
        if (existing.isEmpty()) { reminders.insert(noteId, due, now); }
        else { reminders.reschedule(existing.get().id(), due, now); }
        return reminders.findOwned(noteId, userId).orElseThrow(ReminderException::missing);
    }
    @Transactional
    public void cancel(long noteId, long userId) {
        notes.lockOwnedById(noteId, userId).orElseThrow(ReminderException::missing);
        var existing = reminders.findOwned(noteId, userId).orElseThrow(ReminderException::missing);
        if ("FIRED".equals(existing.status())) {
            throw new ReminderException(409, "ALREADY_FIRED", "站内通知已生成，不能撤回；可以重新设置提醒");
        }
        reminders.cancelPending(noteId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
