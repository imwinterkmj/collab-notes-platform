package com.collabnotes.platform.auth;

import com.collabnotes.platform.user.RegistrationStringDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record LoginRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9_]{3,32}$")
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String username,
        @NotBlank @CodePointLength(min = 15, max = 128)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @JsonDeserialize(using = RegistrationStringDeserializer.class) String password
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("登录请求包含不支持的字段");
    }
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
