package com.collabnotes.platform.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return CurrentUserResponse.from(user);
    }

    public record CsrfResponse(String headerName, String token) {
        @Override public String toString() { return "CsrfResponse[redacted]"; }
    }
}
