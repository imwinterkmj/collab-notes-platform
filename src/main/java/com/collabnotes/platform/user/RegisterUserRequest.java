package com.collabnotes.platform.user;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record RegisterUserRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-z0-9_]{3,32}$", message = "用户名须为 3～32 位小写字母、数字或下划线")
        @JsonDeserialize(using = RegistrationStringDeserializer.class)
        String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank(message = "密码不能为空或全为空白")
        @CodePointLength(min = 1, max = 128, message = "密码须为 1～128 个 Unicode 字符")
        @JsonDeserialize(using = RegistrationStringDeserializer.class)
        String password
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        // 客户端不能指定 id、password_hash、created_at 等服务端字段。
        throw new IllegalArgumentException("注册请求包含不支持的字段");
    }

    @Override
    public String toString() {
        return "RegisterUserRequest[redacted]";
    }
}
