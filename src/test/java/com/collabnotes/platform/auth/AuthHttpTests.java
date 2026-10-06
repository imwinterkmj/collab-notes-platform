package com.collabnotes.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.collabnotes.platform.support.HttpSessionTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql",
        "server.servlet.session.cookie.http-only=true",
        "server.servlet.session.cookie.same-site=lax"
})
class AuthHttpTests {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper mapper;

    @Test
    void realCookieIsRotatedAndInvalidated() throws Exception {
        var client = new HttpSessionTestClient(port, mapper);
        String username = "http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var credentials = Map.of("username", username, "password", "http-test-passphrase");
        var register = client.post("/api/users/register", credentials);
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(client.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String before = client.sessionId();
        var login = client.post("/api/auth/login", credentials);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(client.sessionId()).isNotEqualTo(before);
        assertThat(login.getHeaders().get("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        assertThat(client.replaySession(before).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.OK);
        String after = client.sessionId();
        assertThat(client.post("/api/auth/logout", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(client.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.replaySession(after).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
