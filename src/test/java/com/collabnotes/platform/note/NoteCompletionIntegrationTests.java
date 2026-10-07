package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class NoteCompletionIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private NoteRepository notes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    private MockHttpSession session;
    private long userId;
    private long noteId;
    private static final String PASSWORD = "completion-test-passphrase";
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456Z");

    @BeforeEach
    void seedAndLogin() throws Exception {
        String name = uniqueName();
        userId = registration.register(new RegisterUserRequest(name, PASSWORD)).id();
        noteId = notes.insert(userId, " 完成测试 🔔 ", " 原始正文\n保留空格 🔔 ", TIME);
        session = login(name);
    }

    @Test
    void completesOnlyStateAndTimeAndPreservesAllOtherFields() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        var body = set(true);
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.get("id").asLong()).isEqualTo(noteId);
        assertThat(body.get("completed").asBoolean()).isTrue();
        assertThat(body.get("title").asText()).isEqualTo(" 完成测试 🔔 ");
        assertThat(body.get("content").asText()).isEqualTo(" 原始正文\n保留空格 🔔 ");
        assertThat(Instant.parse(body.get("createdAt").asText())).isEqualTo(TIME);
        var updated = Instant.parse(body.get("updatedAt").asText());
        assertThat(updated).isBetween(before, Instant.now());
        assertThat(updated.getNano() % 1000).isZero();
        assertThat(notes.findOwnedById(noteId, userId).orElseThrow().completed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT user_id FROM notes WHERE id = ?", Long.class, noteId)).isEqualTo(userId);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void reopensCompletedRecord() throws Exception {
        notes.setCompletionOwnedById(noteId, userId, true, TIME);
        var body = set(false);
        assertThat(body.get("completed").asBoolean()).isFalse();
        assertThat(Instant.parse(body.get("updatedAt").asText())).isAfter(TIME);
        assertThat(Instant.parse(body.get("createdAt").asText())).isEqualTo(TIME);
        assertThat(notes.findOwnedById(noteId, userId).orElseThrow().completed()).isFalse();
    }

    @Test
    void sameTargetKeepsTimeAndDoesNotToggleOrInsert() throws Exception {
        // 与 HTTP JSON 使用相同的数值节点类型，避免 LongNode/IntNode 的类型差异。
        var original = mapper.readTree(mapper.writeValueAsString(notes.findOwnedById(noteId, userId).orElseThrow()));
        assertThat(set(false)).isEqualTo(original);
        var completed = set(true);
        assertThat(set(true)).isEqualTo(completed);
        var reopened = set(false);
        assertThat(set(false)).isEqualTo(reopened);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void detailAndListReflectNewStateAndOrderingWithoutReturningBodyInList() throws Exception {
        long otherNote = notes.insert(userId, "另一条", "另一条正文", TIME.plusSeconds(1));
        var completed = set(true);
        var detail = mvc.perform(get("/api/notes/" + noteId).session(session)).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(detail.getResponse().getContentAsString())).isEqualTo(completed);
        var result = mvc.perform(get("/api/notes").session(session).param("size", "1"))
                .andExpect(status().isOk()).andReturn();
        var first = mapper.readTree(result.getResponse().getContentAsString()).get("items").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(otherNote);
        assertThat(first.get("completed").asBoolean()).isFalse();
        var second = mapper.readTree(mvc.perform(get("/api/notes").session(session).param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("items").get(0);
        assertThat(second.get("id").asLong()).isEqualTo(noteId);
        assertThat(second.get("updatedAt")).isEqualTo(completed.get("updatedAt"));
        assertThat(first.has("content")).isFalse();
        assertThat(notes.findOwnedById(otherNote, userId).orElseThrow().completed()).isFalse();
    }

    @Test
    void otherAccountAndMissingRecordHaveSame404AndOwnerIsUnchanged() throws Exception {
        String name = uniqueName();
        registration.register(new RegisterUserRequest(name, PASSWORD));
        var other = login(name);
        var denied = mvc.perform(request(noteId, other).param("userId", Long.toString(userId))
                .content("{\"completed\":true}")).andExpect(status().isNotFound()).andReturn();
        var missing = mvc.perform(request(Long.MAX_VALUE, other).content("{\"completed\":true}"))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(denied.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString())
                .contains("NOTE_NOT_FOUND").doesNotContain("完成测试", "原始正文");
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"completed\":null}", "{\"completed\":\"true\"}",
            "{\"completed\":\"false\"}", "{\"completed\":0}", "{\"completed\":1}",
            "{\"completed\":-1}", "{\"completed\":0.1}", "{\"completed\":[]}", "{\"completed\":{}}",
            "{\"completed\":\"\"}", "\"private-state\"", "{\"completed\":true, private-state}",
            "{\"completed\":\"private-state\"}"})
    void rejectsMissingNullNonBooleanAndMalformedJson(String input) throws Exception {
        var result = mvc.perform(request(noteId, session).content(input)).andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-state", "Exception");
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "userId", "user_id", "title", "content", "is_completed", "createdAt", "updatedAt"})
    void rejectsExtraFields(String field) throws Exception {
        mvc.perform(request(noteId, session).content(mapper.writeValueAsString(
                Map.of("completed", true, field, "private-field")))).andExpect(status().isBadRequest());
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808", "1%20OR%201=1"})
    void invalidIdsAre400(String id) throws Exception {
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, patch("/api/notes/" + id + "/completion"), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isBadRequest());
        unchanged();
    }

    @Test
    void requiresLoginAndCsrf() throws Exception {
        mvc.perform(request(noteId, null).content("{\"completed\":true}")).andExpect(status().isUnauthorized());
        mvc.perform(patch(path(noteId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"completed\":true}")).andExpect(status().isForbidden());
        mvc.perform(patch(path(noteId)).session(session).header("X-CSRF-TOKEN", "forged")
                .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isForbidden());
        unchanged();
    }

    @Test
    void rejectsNonJsonAndUnsupportedMethods() throws Exception {
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, patch(path(noteId)), session)
                .contentType(MediaType.TEXT_PLAIN).content("true")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get(path(noteId)).session(session)).andExpect(status().isMethodNotAllowed());
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post(path(noteId)), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isMethodNotAllowed());
        unchanged();
    }

    @Test
    void requestToStringIsRedacted() {
        assertThat(new UpdateNoteCompletionRequest(true).toString()).isEqualTo("UpdateNoteCompletionRequest[redacted]");
    }

    private JsonNode set(boolean value) throws Exception {
        var result = mvc.perform(request(noteId, session).content(mapper.writeValueAsString(Map.of("completed", value))))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private MockHttpServletRequestBuilder request(long id, MockHttpSession targetSession) throws Exception {
        return CsrfTestSupport.withCsrf(mvc, mapper, patch(path(id)), targetSession).contentType(MediaType.APPLICATION_JSON);
    }
    private String path(long id) { return "/api/notes/" + id + "/completion"; }
    private void unchanged() {
        var note = notes.findOwnedById(noteId, userId).orElseThrow();
        assertThat(note.completed()).isFalse();
        assertThat(note.updatedAt()).isEqualTo(TIME);
        assertThat(note.createdAt()).isEqualTo(TIME);
        assertThat(note.title()).isEqualTo(" 完成测试 🔔 ");
        assertThat(note.content()).isEqualTo(" 原始正文\n保留空格 🔔 ");
        assertThat(count()).isEqualTo(1);
    }
    private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE user_id = ?", Long.class, userId); }
    private String uniqueName() { return "comp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
