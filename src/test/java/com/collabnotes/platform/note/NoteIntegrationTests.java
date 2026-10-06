package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.collabnotes.platform.support.CsrfTestSupport;
import com.collabnotes.platform.user.RegisterUserRequest;
import com.collabnotes.platform.user.UserRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql,classpath:db/notes-test-schema.sql"
})
@AutoConfigureMockMvc
class NoteIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    private MockHttpSession session;
    private long userId;
    private static final String PASSWORD = "notes-test-passphrase";

    @BeforeEach
    void authenticate() throws Exception {
        String username = uniqueName();
        userId = registration.register(new RegisterUserRequest(username, PASSWORD)).id();
        session = login(username);
    }

    @Test
    void createPersistsOwnerTextStateAndUtcTimesAndOwnerCanRead() throws Exception {
        String title = " 面试-🔔-' OR 1=1 -- ";
        String content = " 私人正文\nJava 索引 🔔 ' ";
        Instant before = Instant.now().minusSeconds(1);
        JsonNode note = create(title, content);
        assertThat(note.size()).isEqualTo(6);
        assertThat(note.get("id").asLong()).isPositive();
        assertThat(note.get("title").asText()).isEqualTo(title);
        assertThat(note.get("content").asText()).isEqualTo(content);
        assertThat(note.get("completed").asBoolean()).isFalse();
        assertThat(note.has("userId")).isFalse();
        assertThat(note.get("createdAt").asText()).endsWith("Z");
        Instant created = Instant.parse(note.get("createdAt").asText());
        assertThat(created).isBetween(before, Instant.now());
        assertThat(note.get("updatedAt")).isEqualTo(note.get("createdAt"));
        jdbc.query("SELECT * FROM notes WHERE id = ?", rs -> {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("user_id")).isEqualTo(userId);
            assertThat(rs.getString("title")).isEqualTo(title);
            assertThat(rs.getString("content")).isEqualTo(content);
            assertThat(rs.getBoolean("is_completed")).isFalse();
            assertThat(rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(created);
            assertThat(rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(created);
            return null;
        }, note.get("id").asLong());
        var detail = mvc.perform(get("/api/notes/" + note.get("id").asLong()).session(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(detail.getResponse().getContentAsString())).isEqualTo(note);
    }

    @Test
    void anotherUserCannotReadEvenBySupplyingOwnerId() throws Exception {
        JsonNode note = create("本人备忘录", "不应泄漏的正文");
        String other = uniqueName();
        registration.register(new RegisterUserRequest(other, PASSWORD));
        MockHttpSession second = login(other);
        var denied = mvc.perform(get("/api/notes/" + note.get("id").asLong())
                .param("userId", Long.toString(userId)).session(second)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTE_NOT_FOUND")).andReturn();
        var absent = mvc.perform(get("/api/notes/" + Long.MAX_VALUE).session(second))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(denied.getResponse().getContentAsString()).isEqualTo(absent.getResponse().getContentAsString())
                .doesNotContain("不应泄漏的正文", "本人备忘录");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void repeatedTitlesAreAllowedAndEmptyContentIsPreserved() throws Exception {
        JsonNode first = create("重复标题", "");
        JsonNode second = create("重复标题", "");
        assertThat(first.get("id")).isNotEqualTo(second.get("id"));
        assertThat(first.get("content").asText()).isEmpty();
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void acceptsUnicodeLengthBoundaries() throws Exception {
        create("🔔", "");
        create("🔔".repeat(120), "🔔".repeat(10000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "x", "🔔"})
    void rejectsBlankOrOverlongTitles(String title) throws Exception {
        if (!title.isBlank()) { title = title.repeat(121); }
        mvc.perform(createRequest().content(json(title, "private-content"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.title").exists());
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "🔔"})
    void rejectsOverlongContents(String character) throws Exception {
        String content = character.repeat(10001);
        var result = mvc.perform(createRequest().content(json("正常标题", content)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.content").exists()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(content);
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"title\":\"test\"}", "{\"content\":\"test\"}",
            "{\"title\":null,\"content\":\"test\"}", "{\"title\":\"test\",\"content\":null}",
            "{\"title\":12,\"content\":\"test\"}", "{\"title\":\"test\",\"content\":false}",
            "{\"title\":[],\"content\":\"test\"}", "{\"title\":\"test\",\"content\":{}}",
            "{\"title\":\"private-text\", invalid}"})
    void rejectsMissingNullWrongTypeAndMalformedRequests(String input) throws Exception {
        var result = mvc.perform(createRequest().content(input)).andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-text", "Exception");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "user_id", "id", "completed", "is_completed", "createdAt", "updatedAt"})
    void rejectsClientControlledOwnershipAndServerFields(String field) throws Exception {
        String input = mapper.writeValueAsString(Map.of("title", "test", "content", "test", field, userId));
        mvc.perform(createRequest().content(input)).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808", "1%20OR%201=1"})
    void invalidIdsReturn400(String id) throws Exception {
        mvc.perform(get("/api/notes/" + id).session(session)).andExpect(status().isBadRequest());
    }

    @Test
    void authenticationAndCsrfAreRequired() throws Exception {
        var note = create("本人标题", "本人正文");
        mvc.perform(get("/api/notes/" + note.get("id").asLong())).andExpect(status().isUnauthorized());
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes"), null)
                .contentType(MediaType.APPLICATION_JSON).content(json("test", "test")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notes").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(json("test", "test"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/notes").session(session).header("X-CSRF-TOKEN", "forged")
                .contentType(MediaType.APPLICATION_JSON).content(json("test", "test")))
                .andExpect(status().isForbidden());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void contentTypeAndUnsupportedMethodsKeepTheirStatuses() throws Exception {
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes"), session)
                .contentType(MediaType.TEXT_PLAIN).content("private-text"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/notes").session(session)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void requestAndResponseToStringRedactPrivateText() {
        assertThat(new CreateNoteRequest("private-title", "private-content").toString())
                .doesNotContain("private-title", "private-content");
        assertThat(new NoteResponse(1, "private-title", "private-content", false, Instant.now(), Instant.now()).toString())
                .doesNotContain("private-title", "private-content");
    }

    private JsonNode create(String title, String content) throws Exception {
        var result = mvc.perform(createRequest().content(json(title, content))).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private MockHttpServletRequestBuilder createRequest() throws Exception {
        return CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes"), session)
                .contentType(MediaType.APPLICATION_JSON);
    }
    private String json(String title, String content) throws Exception {
        return mapper.writeValueAsString(Map.of("title", title, "content", content));
    }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE user_id = ?", Long.class, userId); }
    private String uniqueName() { return "note_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
