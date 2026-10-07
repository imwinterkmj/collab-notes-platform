package com.collabnotes.platform.reminder;

import com.collabnotes.platform.user.RegistrationStringDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;

public record SetReminderRequest(
        @NotBlank(message = "提醒时间必须提供")
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String dueAt) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("提醒请求包含不支持的字段");
    }
    @Override public String toString() { return "SetReminderRequest[redacted]"; }
}
