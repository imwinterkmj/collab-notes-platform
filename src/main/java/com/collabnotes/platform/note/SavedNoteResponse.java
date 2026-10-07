package com.collabnotes.platform.note;

import com.collabnotes.platform.reminder.ReminderResponse;

public record SavedNoteResponse(NoteResponse note, ReminderResponse reminder) {
    @Override public String toString() { return "SavedNoteResponse[redacted]"; }
}
