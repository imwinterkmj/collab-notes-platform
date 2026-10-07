package com.collabnotes.platform.note;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;

public record UpdateNoteCompletionRequest(
        @NotNull(message = "完成状态必须提供")
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean completed
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("完成状态请求包含不支持的字段");
    }
    @Override public String toString() { return "UpdateNoteCompletionRequest[redacted]"; }
}
