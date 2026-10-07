package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
class NoteUpdateIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NoteRepository notes;
    @Autowired private UserRegistrationService registration;
    private MockHttpSession session;
    private long userId;
    private long noteId;
    private static final String PASSWORD = "update-test-passphrase";
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456Z");

    @BeforeEach
    void authenticateAndSeed() throws Exception {
        String name = uniqueName();
        userId = registration.register(new RegisterUserRequest(name, PASSWORD)).id();
        session = login(name);
        noteId = notes.insert(userId, "old-title", "old-content", TIME);
    }

    @Test
    void replacesTextAndPreservesIdentityCreationAndCompletedState() throws Exception {
        jdbc.update("UPDATE notes SET is_completed = ? WHERE id = ?", true, noteId);
        String title = " 面试 🔔 ' OR 1=1 -- ";
        String content = " 新正文\n保留空格 🔔 ' ";
        Instant before = Instant.now().minusSeconds(1);
        var response = mvc.perform(request(noteId, session).content(json(title, content)))
                .andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(response.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.get("id").asLong()).isEqualTo(noteId);
        assertThat(body.get("title").asText()).isEqualTo(title);
        assertThat(body.get("content").asText()).isEqualTo(content);
        assertThat(body.get("completed").asBoolean()).isTrue();
        assertThat(Instant.parse(body.get("createdAt").asText())).isEqualTo(TIME);
        Instant updated = Instant.parse(body.get("updatedAt").asText());
        assertThat(updated).isBetween(before, Instant.now());
        assertThat(updated.getNano() % 1000).isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM notes WHERE id = ?", Long.class, noteId)).isEqualTo(userId);
        assertThat(count()).isEqualTo(1);
        var detail = mvc.perform(get("/api/notes/" + noteId).session(session)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(detail.getResponse().getContentAsString())).isEqualTo(body);
        var page = mvc.perform(get("/api/notes").session(session)).andExpect(status().isOk()).andReturn();
        var summary = mapper.readTree(page.getResponse().getContentAsString()).get("items").get(0);
        assertThat(summary.get("title").asText()).isEqualTo(title);
        assertThat(summary.get("updatedAt")).isEqualTo(body.get("updatedAt"));
        assertThat(summary.has("content")).isFalse();
    }

    @Test
    void anotherUserAndMissingNoteReturnSame404WithoutChangingOwnerRecord() throws Exception {
        String otherName = uniqueName();
        registration.register(new RegisterUserRequest(otherName, PASSWORD));
        var other = login(otherName);
        var denied = mvc.perform(request(noteId, other).param("userId", Long.toString(userId))
                .content(json("attack-title", "attack-content"))).andExpect(status().isNotFound()).andReturn();
        var absent = mvc.perform(request(Long.MAX_VALUE, other).content(json("attack-title", "attack-content")))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(denied.getResponse().getContentAsString()).isEqualTo(absent.getResponse().getContentAsString())
                .contains("NOTE_NOT_FOUND").doesNotContain("old-title", "old-content");
        unchanged();
    }

    @Test
    void repeatedUpdatesAndEmptyContentDoNotInsertNewRecords() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(request(noteId, session).content(json("same-title", ""))).andExpect(status().isOk());
        }
        var note = notes.findOwnedById(noteId, userId).orElseThrow();
        assertThat(note.title()).isEqualTo("same-title");
        assertThat(note.content()).isEmpty();
        assertThat(note.createdAt()).isEqualTo(TIME);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void acceptsUnicodeBoundaries() throws Exception {
        mvc.perform(request(noteId, session).content(json("🔔".repeat(120), "🔔".repeat(10000))))
                .andExpect(status().isOk());
        assertThat(notes.findOwnedById(noteId, userId).orElseThrow().title()).isEqualTo("🔔".repeat(120));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "x", "🔔"})
    void rejectsBlankOrOverlongTitle(String title) throws Exception {
        if (!title.isBlank()) { title = title.repeat(121); }
        mvc.perform(request(noteId, session).content(json(title, "private-content")))
                .andExpect(status().isBadRequest());
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "🔔"})
    void rejectsOverlongContentWithoutEcho(String character) throws Exception {
        String content = character.repeat(10001);
        var result = mvc.perform(request(noteId, session).content(json("title", content)))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(content);
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"title\":\"test\"}", "{\"content\":\"test\"}",
            "{\"title\":null,\"content\":\"test\"}", "{\"title\":\"test\",\"content\":null}",
            "{\"title\":12,\"content\":\"test\"}", "{\"title\":\"test\",\"content\":false}",
            "{\"title\":[],\"content\":\"test\"}", "{\"title\":\"test\",\"content\":{}}",
            "{\"title\":\"private-title\", invalid}"})
    void rejectsMissingNullWrongTypeAndMalformedJson(String input) throws Exception {
        var result = mvc.perform(request(noteId, session).content(input)).andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-title", "Exception");
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "userId", "user_id", "completed", "is_completed", "createdAt", "updatedAt"})
    void rejectsServerControlledFields(String field) throws Exception {
        mvc.perform(request(noteId, session).content(mapper.writeValueAsString(
                Map.of("title", "test", "content", "test", field, 1))))
                .andExpect(status().isBadRequest());
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808", "1%20OR%201=1"})
    void invalidIdIs400(String id) throws Exception {
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, put("/api/notes/" + id), session)
                .contentType(MediaType.APPLICATION_JSON).content(json("test", "test")))
                .andExpect(status().isBadRequest());
        unchanged();
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(request(noteId, null).content(json("test", "test"))).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/notes/" + noteId).session(session).contentType(MediaType.APPLICATION_JSON)
                .content(json("test", "test"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/notes/" + noteId).session(session).header("X-CSRF-TOKEN", "forged")
                .contentType(MediaType.APPLICATION_JSON).content(json("test", "test")))
                .andExpect(status().isForbidden());
        unchanged();
    }

    @Test
    void nonJsonIs415AndRequestToStringIsRedacted() throws Exception {
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, put("/api/notes/" + noteId), session)
                .contentType(MediaType.TEXT_PLAIN).content("private-content"))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(new UpdateNoteRequest("private-title", "private-content").toString())
                .doesNotContain("private-title", "private-content");
        unchanged();
    }

    private void unchanged() {
        var note = notes.findOwnedById(noteId, userId).orElseThrow();
        assertThat(note.title()).isEqualTo("old-title");
        assertThat(note.content()).isEqualTo("old-content");
        assertThat(note.createdAt()).isEqualTo(TIME);
        assertThat(note.updatedAt()).isEqualTo(TIME);
        assertThat(note.completed()).isFalse();
        assertThat(count()).isEqualTo(1);
    }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE user_id = ?", Long.class, userId); }
    private MockHttpServletRequestBuilder request(long id, MockHttpSession targetSession) throws Exception {
        return CsrfTestSupport.withCsrf(mvc, mapper, put("/api/notes/" + id), targetSession)
                .contentType(MediaType.APPLICATION_JSON);
    }
    private String json(String title, String content) throws Exception {
        return mapper.writeValueAsString(Map.of("title", title, "content", content));
    }
    private String uniqueName() { return "edit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
