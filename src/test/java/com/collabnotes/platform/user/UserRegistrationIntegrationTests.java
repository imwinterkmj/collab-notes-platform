package com.collabnotes.platform.user;

import static org.assertj.core.api.Assertions.assertThat;
import com.collabnotes.platform.support.CsrfTestSupport;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql"
})
@AutoConfigureMockMvc
class UserRegistrationIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void registersAUserAndPersistsTheHashAndUtcTime() throws Exception {
        String username = uniqueUsername();
        String password = " 备忘录-Passphrase-🔔 ";
        Instant before = Instant.now().minusSeconds(1);
        String body = mockMvc.perform(post("/api/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(username, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        var response = objectMapper.readTree(body);
        assertThat(response.size()).isEqualTo(3);
        long id = response.get("id").asLong();
        assertThat(id).isPositive();
        Instant createdAt = Instant.parse(response.get("createdAt").asText());
        assertThat(createdAt).isBetween(before, Instant.now());
        assertThat(response.get("createdAt").asText()).endsWith("Z");
        assertThat(count(username)).isEqualTo(1);
        jdbcTemplate.query("SELECT password_hash, created_at FROM users WHERE id = ?", rs -> {
            assertThat(rs.next()).isTrue();
            String hash = rs.getString("password_hash");
            assertThat(hash).startsWith("$argon2id$").isNotEqualTo(password);
            assertThat(passwordEncoder.matches(password, hash)).isTrue();
            assertThat(passwordEncoder.matches(password.strip(), hash)).isFalse();
            assertThat(rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))
                    .isEqualTo(createdAt);
            return null;
        }, id);
        assertThat(body).doesNotContain(password, "$argon2id$");
    }

    @Test
    void rejectsDuplicateUsernameWithoutReplacingTheExistingHash() throws Exception {
        String username = uniqueUsername();
        register(username, "first-test-passphrase");
        String originalHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE username = ?", String.class, username);

        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(username, "second-test-passphrase")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));

        assertThat(count(username)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE username = ?",
                String.class, username)).isEqualTo(originalHash);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "UPPERCASE", "中文用户名", "user name", "user';--",
            "abcdefghijklmnopqrstuvwxyz1234567"})
    void rejectsInvalidUsernames(String username) throws Exception {
        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(username, "test-only-passphrase")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.username").exists());
        assertThat(count(username)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 129})
    void rejectsPasswordsOutsideTheLengthLimits(int length) throws Exception {
        String username = uniqueUsername();
        String password = "x".repeat(length);
        String body = mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(username, password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.password").exists())
                .andReturn().getResponse().getContentAsString();
        if (!password.isEmpty()) {
            assertThat(body).doesNotContain(password);
        }
        assertThat(count(username)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 128})
    void acceptsTheUnicodeCodePointLengthBoundaries(int length) throws Exception {
        // emoji 在 UTF-16 中占两个 char，但这里应按一个 Unicode 码点计数。
        register(uniqueUsername(), "🔔".repeat(length));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"username\":null,\"password\":null}",
            "{\"username\":\"valid_user\"}", "{\"password\":\"test-only-passphrase\"}",
            "{\"username\":\"valid_user\",\"password\":\"               \"}"})
    void rejectsMissingOrBlankFields(String content) throws Exception {
        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(content)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "password_hash", "created_at"})
    void rejectsServerControlledFields(String field) throws Exception {
        String username = uniqueUsername();
        String content = objectMapper.writeValueAsString(Map.of("username", username,
                "password", "test-only-passphrase", field, "client-value"));
        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(content)).andExpect(status().isBadRequest());
        assertThat(count(username)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"username\":12345,\"password\":\"test-only-passphrase\"}",
            "{\"username\":\"valid_user\",\"password\":123456789012345}",
            "{\"username\":\"valid_user\",\"password\":true}",
            "{\"username\":\"valid_user\",\"password\":{\"value\":\"test-only-passphrase\"}}"
    })
    void requiresJsonStringsInsteadOfSilentlyConvertingOtherTypes(String content) throws Exception {
        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(content)).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRegistrationHasOneWinner() throws Exception {
        String username = uniqueUsername();
        String content = json(username, "test-only-passphrase");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content(content)).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content(content)).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(new int[] {first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count(username)).isEqualTo(1);
    }

    @Test
    void requestObjectDoesNotExposeItsPasswordWhenPrintedOrSerialized() throws Exception {
        var request = new RegisterUserRequest("test_user", "test-only-passphrase");
        assertThat(request.toString()).doesNotContain(request.password());
        assertThat(objectMapper.writeValueAsString(request)).doesNotContain(request.password(), "password");
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(username, password))).andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder post(String path) throws Exception {
        return CsrfTestSupport.withCsrf(mockMvc, objectMapper, MockMvcRequestBuilders.post(path), null);
    }

    private String json(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    private int count(String username) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class, username);
    }

    private String uniqueUsername() {
        return "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
