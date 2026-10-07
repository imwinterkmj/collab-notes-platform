package com.collabnotes.platform.reminder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.collabnotes.platform.note.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReminderDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(ReminderDispatcher.class);
    private final NoteRepository notes;
    private final ReminderRepository reminders;
    private final TransactionTemplate transaction;
    public ReminderDispatcher(NoteRepository notes, ReminderRepository reminders, PlatformTransactionManager manager) {
        this.notes = notes;
        this.reminders = reminders;
        this.transaction = new TransactionTemplate(manager);
    }
    public int dispatchDue(Instant now, int limit) {
        if (limit < 1 || limit > 1000) { throw new IllegalArgumentException("提醒批量须为 1～1000"); }
        Instant utcNow = now.truncatedTo(ChronoUnit.MICROS);
        int fired = 0;
        for (var candidate : reminders.dueCandidates(utcNow, limit)) {
            try {
                Boolean sent = transaction.execute(status -> {
                    // 所有修改路径统一先锁备忘录，再访问提醒；扫描结果只作为候选，不是发送授权。
                    var note = notes.lockForDispatch(candidate.noteId());
                    if (note.isEmpty()) { return false; }
                    var current = reminders.findForDispatch(candidate.noteId());
                    if (current.isEmpty()) { return false; }
                    var reminder = current.get();
                    if (reminder.id() != candidate.id() || reminder.generation() != candidate.generation()
                            || !"SCHEDULED".equals(reminder.status()) || reminder.dueAt().isAfter(utcNow)) {
                        return false;
                    }
                    if (note.get().completed()) {
                        reminders.cancelPending(candidate.noteId(), utcNow);
                        return false;
                    }
                    reminders.fire(reminder, note.get().title(), utcNow);
                    return true;
                });
                if (Boolean.TRUE.equals(sent)) { fired++; }
            } catch (RuntimeException exception) {
                // 单条失败回滚，下次轮询仍可重试；不输出 SQL、标题、正文或 cause。
                LOG.error("提醒事务失败，错误类型：{}", exception.getClass().getSimpleName());
            }
        }
        return fired;
    }
}
