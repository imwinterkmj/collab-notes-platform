package com.collabnotes.platform.note;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.collabnotes.platform.reminder.ReminderRepository;
import com.collabnotes.platform.reminder.ReminderService;
import com.collabnotes.platform.reminder.SetReminderRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 复用原业务服务的事务入口，不在浏览器串接两个独立写请求。 */
@Service
public class NoteSaveService {
    private final NoteService notes;
    private final NoteRepository noteRepository;
    private final ReminderService reminders;
    private final ReminderRepository reminderRepository;

    public NoteSaveService(NoteService notes, NoteRepository noteRepository,
                           ReminderService reminders, ReminderRepository reminderRepository) {
        this.notes = notes;
        this.noteRepository = noteRepository;
        this.reminders = reminders;
        this.reminderRepository = reminderRepository;
    }

    @Transactional
    public SavedNoteResponse save(Long noteId, long userId, SaveNoteRequest request) {
        if (noteId != null) {
            // 与调度/完成/取消一样先锁本人备忘录，再操作提醒，统一不存在与他人记录的错误。
            noteRepository.lockOwnedById(noteId, userId).orElseThrow(NoteNotFoundException::new);
        }
        var note = noteId == null
                ? notes.create(userId, new CreateNoteRequest(request.title(), request.content()))
                : notes.update(noteId, userId, new UpdateNoteRequest(request.title(), request.content()));
        switch (request.reminderAction()) {
            case "SET" -> reminders.set(note.id(), userId, new SetReminderRequest(request.dueAt()));
            case "CANCEL" -> reminderRepository.cancelPending(note.id(), Instant.now().truncatedTo(ChronoUnit.MICROS));
            case "KEEP" -> { /* 只改正文不重排任务，不递增批次，也不重新触发已到期通知。 */ }
            default -> throw new IllegalArgumentException("未知提醒操作");
        }
        // CANCEL 对 FIRED/无提醒为无操作，不撤回已有通知。所有读取/写入失败使整个保存回滚。
        return new SavedNoteResponse(note, reminderRepository.findOwned(note.id(), userId).orElse(null));
    }
}
