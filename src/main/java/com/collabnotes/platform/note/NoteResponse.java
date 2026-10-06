package com.collabnotes.platform.note;

import java.time.Instant;

/** 仅在正常业务响应返回正文，避免框架调试日志通过 toString 输出私人内容。 */
public record NoteResponse(long id, String title, String content, boolean completed,
                           Instant createdAt, Instant updatedAt) {
    @Override public String toString() { return "NoteResponse[id=" + id + ", redacted]"; }
}
