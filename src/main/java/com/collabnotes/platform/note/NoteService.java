package com.collabnotes.platform.note;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

@Service
public class NoteService {
    private final NoteRepository notes;

    public NoteService(NoteRepository notes) { this.notes = notes; }

    public NoteResponse create(long userId, CreateNoteRequest request) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // 当前只有一条 INSERT，由数据库保证语句原子性；暂不扩大事务范围。
        long id = notes.insert(userId, request.title(), request.content(), now);
        return new NoteResponse(id, request.title(), request.content(), false, now, now);
    }

    public NoteResponse detail(long noteId, long userId) {
        return notes.findOwnedById(noteId, userId).orElseThrow(NoteNotFoundException::new);
    }
}
