package com.collabnotes.platform.reminder;

import java.time.Instant;

public record ReminderResponse(long id, long noteId, Instant dueAt, long generation, String status) {
    @Override public String toString() { return "ReminderResponse[redacted]"; }
}
