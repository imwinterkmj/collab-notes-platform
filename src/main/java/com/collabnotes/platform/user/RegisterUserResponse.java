package com.collabnotes.platform.user;

import java.time.Instant;

public record RegisterUserResponse(long id, String username, Instant createdAt) {
}
