package com.collabnotes.platform.user;

import java.util.Map;

public record RegistrationError(String code, String message, Map<String, String> fields) {
}
