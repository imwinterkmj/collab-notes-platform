package com.collabnotes.platform.user;

public class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException() {
        super("用户名已被占用");
    }
}
