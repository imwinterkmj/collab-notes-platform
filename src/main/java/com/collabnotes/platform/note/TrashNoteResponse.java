package com.collabnotes.platform.note;

import java.time.Instant;

/** 回收站快照；仅正常业务 JSON 返回正文，调试日志不输出私人内容。 */
public record TrashNoteResponse(long id, String title, String content, boolean completed,
                                Instant createdAt, Instant updatedAt, Instant deletedAt) {
    @Override public String toString() { return "TrashNoteResponse[id=" + id + ", redacted]"; }
}
