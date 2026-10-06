package com.collabnotes.platform.user;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** 仅供服务端校验凭据，不作为接口响应。 */
public record UserCredentials(long id, String username, @JsonIgnore String passwordHash) {
    @Override
    public String toString() {
        return "UserCredentials[redacted]";
    }
}
