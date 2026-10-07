package com.collabnotes.platform.reminder;

import java.time.Instant;

public record NotificationResponse(long id, long noteId, String title, Instant dueAt, Instant createdAt, boolean read) {
    @Override public String toString() { return "NotificationResponse[redacted]"; }
}
