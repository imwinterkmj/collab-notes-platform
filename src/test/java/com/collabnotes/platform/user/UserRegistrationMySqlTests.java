package com.collabnotes.platform.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.collabnotes.platform.support.HttpSessionTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 明确开启时才连接已有的本地 MySQL；不建表、不删表、不清空数据库。 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_REGISTRATION_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${MYSQL_TEST_URL:jdbc:mysql://localhost:3306/collab_notes?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}",
        "spring.datasource.username=${MYSQL_TEST_USER:collab_notes}",
        "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.sql.init.mode=never",
        "server.servlet.session.cookie.http-only=true",
        "server.servlet.session.cookie.same-site=lax"
})
class UserRegistrationMySqlTests {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private final String username = "verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    private boolean cleanupAllowed;

    @Test
    void realMysqlRegistrationPreservesUniquenessHashAndUtcTime() throws Exception {
        assertThat(count()).isZero();
        cleanupAllowed = true;
        String password = " 验收-Passphrase-🔔 ";
        Map<String, String> request = Map.of("username", username, "password", password);
        var firstClient = new HttpSessionTestClient(port, objectMapper);
        var secondClient = new HttpSessionTestClient(port, objectMapper);
        Instant before = Instant.now().minusSeconds(1);
        CountDownLatch start = new CountDownLatch(1);
        ResponseEntity<String> first;
        ResponseEntity<String> second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> {
                start.await();
                return firstClient.post("/api/users/register", request);
            });
            var secondFuture = executor.submit(() -> {
                start.await();
                return secondClient.post("/api/users/register", request);
            });
            start.countDown();
            first = firstFuture.get(15, TimeUnit.SECONDS);
            second = secondFuture.get(15, TimeUnit.SECONDS);
        }
        assertThat(List.of(first.getStatusCode(), second.getStatusCode()))
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(count()).isEqualTo(1);
        String successBody = first.getStatusCode() == HttpStatus.CREATED ? first.getBody() : second.getBody();
        String conflictBody = first.getStatusCode() == HttpStatus.CONFLICT ? first.getBody() : second.getBody();
        assertThat(successBody).doesNotContain(password, "$argon2id$", "password");
        assertThat(conflictBody).contains("USERNAME_TAKEN").doesNotContain(password, "$argon2id$", "SQL");
        var response = objectMapper.readTree(successBody);
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.get("username").asText()).isEqualTo(username);
        long id = response.get("id").asLong();
        assertThat(id).isPositive();
        Instant createdAt = Instant.parse(response.get("createdAt").asText());
        assertThat(createdAt).isBetween(before, Instant.now());
        assertThat(response.get("createdAt").asText()).endsWith("Z");
        jdbcTemplate.query("SELECT password_hash, created_at FROM users WHERE id = ? AND username = ?", rs -> {
            assertThat(rs.next()).isTrue();
            String hash = rs.getString("password_hash");
            assertThat(hash).startsWith("$argon2id$").isNotEqualTo(password);
            assertThat(passwordEncoder.matches(password, hash)).isTrue();
            assertThat(passwordEncoder.matches("wrong-test-passphrase", hash)).isFalse();
            assertThat(rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
                    .isEqualTo(createdAt);
            return null;
        }, id, username);
        assertThat(firstClient.post("/api/users/register",
                Map.of("username", username, "password", "short")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count()).isEqualTo(1);

        assertThat(firstClient.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String preLoginId = firstClient.sessionId();
        var login = firstClient.post("/api/auth/login", request);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).doesNotContain(password, "$argon2id$", "password");
        assertThat(objectMapper.readTree(login.getBody()).size()).isEqualTo(2);
        assertThat(firstClient.sessionId()).isNotEqualTo(preLoginId);
        assertThat(login.getHeaders().get("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        assertThat(firstClient.replaySession(preLoginId).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var me = firstClient.get("/api/auth/me");
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(me.getBody()).get("id").asLong()).isEqualTo(id);
        assertThat(secondClient.post("/api/auth/login", request).getStatusCode()).isEqualTo(HttpStatus.OK);
        String loggedInId = firstClient.sessionId();
        assertThat(firstClient.post("/api/auth/logout", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(firstClient.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(firstClient.replaySession(loggedInId).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(secondClient.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondClient.post("/api/auth/logout", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @AfterEach
    void removeOnlyThisTestsAccount() {
        if (cleanupAllowed) {
            int removed = jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
            System.out.println("MySQL verification rows removed: " + removed);
        }
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
    }
}
