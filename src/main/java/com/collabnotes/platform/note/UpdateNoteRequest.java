package com.collabnotes.platform.note;

import com.collabnotes.platform.user.RegistrationStringDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;

/** 替换可编辑内容，不能改变归属或完成状态。 */
public record UpdateNoteRequest(
        @NotBlank(message = "标题不能为空或全为空白")
        @CodePointLength(min = 1, max = 120, message = "标题须为 1～120 个 Unicode 字符")
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String title,
        @NotNull(message = "正文必须提供，允许空字符串")
        @CodePointLength(max = 10000, message = "正文不能超过 10000 个 Unicode 字符")
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String content
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("备忘录请求包含不支持的字段");
    }
    @Override public String toString() { return "UpdateNoteRequest[redacted]"; }
}
