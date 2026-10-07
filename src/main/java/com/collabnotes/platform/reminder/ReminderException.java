package com.collabnotes.platform.reminder;

public class ReminderException extends RuntimeException {
    private final int status;
    private final String code;
    public ReminderException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ReminderException missing() {
        return new ReminderException(404, "REMINDER_NOT_FOUND", "提醒不存在或不可访问");
    }
}
