package com.collabnotes.platform.note;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.collabnotes.platform.reminder.ReminderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {
    private final NoteRepository notes;
    private final ReminderRepository reminders;
    private final NoteTrashRepository trash;

    public NoteService(NoteRepository notes, ReminderRepository reminders, NoteTrashRepository trash) {
        this.notes = notes;
        this.reminders = reminders;
        this.trash = trash;
    }

    public NoteResponse create(long userId, CreateNoteRequest request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // 当前只有一条 INSERT，由数据库保证语句原子性；暂不扩大事务范围。
        long id = notes.insert(userId, request.title(), request.content(), now);
        return new NoteResponse(id, request.title(), request.content(), false, now, now);
    }

    public NoteResponse detail(long noteId, long userId) {
        return notes.findOwnedById(noteId, userId).orElseThrow(NoteNotFoundException::new);
    }

    @Transactional
    public NoteResponse update(long noteId, long userId, UpdateNoteRequest request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        notes.updateOwnedById(noteId, userId, request.title(), request.content(), now);
        // 同一事务读取；失败回滚。归属限定在两条 SQL 中，不依赖驱动的更新行数语义。
        return notes.findOwnedById(noteId, userId).orElseThrow(NoteNotFoundException::new);
    }

    public NotePageResponse list(long userId, int page, int size) {
        long offset = (long) page * size;
        var rows = notes.findOwnedPage(userId, size + 1, offset);
        boolean hasNext = rows.size() > size;
        return new NotePageResponse(rows.subList(0, Math.min(size, rows.size())), page, size, hasNext);
    }

    @Transactional
    public NoteResponse setCompletion(long noteId, long userId, UpdateNoteCompletionRequest request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        notes.setCompletionOwnedById(noteId, userId, request.completed(), now);
        var note = notes.findOwnedById(noteId, userId).orElseThrow(NoteNotFoundException::new);
        if (request.completed()) { reminders.cancelPending(noteId, now); }
        return note;
    }

    @Transactional
    public void delete(long noteId, long userId) {
        trash.lockOwner(userId);
        var note = notes.lockOwnedById(noteId, userId).orElseThrow(NoteNotFoundException::new);
        trash.archive(userId, note, Instant.now().truncatedTo(ChronoUnit.MICROS));
        // 快照、删除及上限清理同一事务；现有外键级联移除提醒/通知，恢复不重启旧提醒。
        if (notes.deleteOwnedById(noteId, userId) == 0) { throw new NoteNotFoundException(); }
        trash.trimOwned(userId);
    }

    public TrashPageResponse trash(long userId) {
        return new TrashPageResponse(trash.listOwned(userId), NoteTrashRepository.CAPACITY);
    }

    @Transactional
    public NoteResponse restore(long trashId, long userId) {
        trash.lockOwner(userId);
        var snapshot = trash.lockOwned(trashId, userId).orElseThrow(NoteNotFoundException::new);
        long id = notes.insertRestored(userId, snapshot, Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (trash.deleteOwned(trashId, userId) != 1) { throw new NoteNotFoundException(); }
        return notes.findOwnedById(id, userId).orElseThrow(NoteNotFoundException::new);
    }
}
