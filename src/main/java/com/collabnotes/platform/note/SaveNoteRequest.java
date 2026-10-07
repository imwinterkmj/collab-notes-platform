package com.collabnotes.platform.note;

import com.collabnotes.platform.user.RegistrationStringDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

/** 编辑器的一次保存；KEEP 不改提醒，SET 设置/改期，CANCEL 只取消待执行任务。 */
public record SaveNoteRequest(
        @NotBlank(message = "标题不能为空或全为空白")
        @CodePointLength(min = 1, max = 120, message = "标题须为 1～120 个 Unicode 字符")
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String title,
        @NotNull(message = "正文必须提供，允许空字符串")
        @CodePointLength(max = 10000, message = "正文不能超过 10000 个 Unicode 字符")
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String content,
        @NotBlank(message = "提醒操作必须提供")
        @Pattern(regexp = "KEEP|SET|CANCEL", message = "提醒操作须为 KEEP、SET 或 CANCEL")
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String reminderAction,
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String dueAt
) {
    @JsonIgnore
    @AssertTrue(message = "设置提醒须提供时间；保留或取消提醒不能附带时间")
    public boolean isReminderSelectionValid() {
        return "SET".equals(reminderAction) ? dueAt != null && !dueAt.isBlank() : dueAt == null;
    }
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("保存请求包含不支持的字段");
    }
    @Override public String toString() { return "SaveNoteRequest[redacted]"; }
}
