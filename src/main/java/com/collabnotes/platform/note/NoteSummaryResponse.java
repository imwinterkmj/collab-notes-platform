package com.collabnotes.platform.note;

import java.time.Instant;

/** 列表不加载完整正文，标题也不进入对象日志。 */
public record NoteSummaryResponse(long id, String title, boolean completed,
                                  Instant createdAt, Instant updatedAt) {
    @Override public String toString() { return "NoteSummaryResponse[id=" + id + ", redacted]"; }
}
