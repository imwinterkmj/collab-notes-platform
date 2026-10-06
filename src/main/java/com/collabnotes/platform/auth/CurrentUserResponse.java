package com.collabnotes.platform.auth;

public record CurrentUserResponse(long id, String username) {
    public static CurrentUserResponse from(AuthenticatedUser user) {
        return new CurrentUserResponse(user.getId(), user.getUsername());
    }
}
