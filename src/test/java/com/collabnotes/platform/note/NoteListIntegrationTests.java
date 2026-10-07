package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql,classpath:db/notes-test-schema.sql"
})
@AutoConfigureMockMvc
class NoteListIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @Autowired private NoteRepository notes;
    private MockHttpSession session;
    private long userId;
    private static final String PASSWORD = "list-test-passphrase";
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456Z");

    @BeforeEach
    void authenticate() throws Exception {
        String name = uniqueName();
        userId = registration.register(new RegisterUserRequest(name, PASSWORD)).id();
        session = login(name);
    }

    @Test
    void defaultsReturnEmptyPageForNewAccount() throws Exception {
        var page = read(get("/api/notes").session(session));
        assertThat(page.size()).isEqualTo(4);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("items").isArray()).isTrue();
        assertThat(page.get("items").size()).isZero();
        assertThat(page.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void listReturnsSummaryWithoutPrivateBodyOrOwnerAndPreservesUnicode() throws Exception {
        long id = notes.insert(userId, " 面试 🔔 ' OR 1=1 -- ", "private-body-marker", TIME);
        var page = page(0, 1);
        var item = page.get("items").get(0);
        assertThat(item.size()).isEqualTo(5);
        assertThat(item.get("id").asLong()).isEqualTo(id);
        assertThat(item.get("title").asText()).isEqualTo(" 面试 🔔 ' OR 1=1 -- ");
        assertThat(item.get("completed").asBoolean()).isFalse();
        assertThat(Instant.parse(item.get("createdAt").asText())).isEqualTo(TIME);
        assertThat(item.get("updatedAt")).isEqualTo(item.get("createdAt"));
        assertThat(item.has("content")).isFalse();
        assertThat(item.has("userId")).isFalse();
        assertThat(page.toString()).doesNotContain("private-body-marker", "password");
    }

    @Test
    void staticPagesAreCompleteAndTiesUseDescendingId() throws Exception {
        List<Long> inserted = new ArrayList<>();
        for (int i = 0; i < 5; i++) { inserted.add(notes.insert(userId, "title-" + i, "", TIME)); }
        var first = page(0, 2);
        var second = page(1, 2);
        var third = page(2, 2);
        assertThat(ids(first)).containsExactly(inserted.get(4), inserted.get(3));
        assertThat(ids(second)).containsExactly(inserted.get(2), inserted.get(1));
        assertThat(ids(third)).containsExactly(inserted.get(0));
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(second.get("hasNext").asBoolean()).isTrue();
        assertThat(third.get("hasNext").asBoolean()).isFalse();
        assertThat(ids(page(3, 2))).isEmpty();
    }

    @Test
    void updatedTimeHasPriorityOverId() throws Exception {
        long newer = notes.insert(userId, "newer", "", TIME.plusSeconds(1));
        long older = notes.insert(userId, "older", "", TIME);
        assertThat(older).isGreaterThan(newer);
        assertThat(ids(page(0, 20))).containsExactly(newer, older);
    }

    @Test
    void incompleteHasPriorityAcrossPagesThenTimeThenId() throws Exception {
        long oldPending = notes.insert(userId, "old-pending", "", TIME);
        long newCompleted = notes.insert(userId, "new-completed", "", TIME.plusSeconds(100));
        notes.setCompletionOwnedById(newCompleted, userId, true, TIME.plusSeconds(200));
        long newPending = notes.insert(userId, "new-pending", "", TIME.plusSeconds(1));
        long tiedPending = notes.insert(userId, "tied-pending", "", TIME.plusSeconds(1));
        assertThat(ids(page(0, 2))).containsExactly(tiedPending, newPending);
        assertThat(ids(page(1, 2))).containsExactly(oldPending, newCompleted);
    }

    @Test
    void exactMultipleHasNoFalseNextPage() throws Exception {
        for (int i = 0; i < 4; i++) { notes.insert(userId, "title-" + i, "", TIME); }
        assertThat(page(0, 2).get("hasNext").asBoolean()).isTrue();
        assertThat(page(1, 2).get("hasNext").asBoolean()).isFalse();
        assertThat(page(2, 2).get("items").size()).isZero();
        assertThat(page(2, 2).get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void maximumSizeAndPageAreAcceptedAndExtraRowIsNotReturned() throws Exception {
        for (int i = 0; i < 101; i++) { notes.insert(userId, "title-" + i, "", TIME); }
        assertThat(page(0, 100).get("items").size()).isEqualTo(100);
        assertThat(page(0, 100).get("hasNext").asBoolean()).isTrue();
        assertThat(page(1, 100).get("items").size()).isEqualTo(1);
        assertThat(page(1, 100).get("hasNext").asBoolean()).isFalse();
        assertThat(page(10000, 100).get("items").size()).isZero();
    }

    @Test
    void otherAccountCannotSelectOwnerByQueryParameter() throws Exception {
        notes.insert(userId, "owner-private-title", "owner-private-body", TIME);
        String otherName = uniqueName();
        long otherId = registration.register(new RegisterUserRequest(otherName, PASSWORD)).id();
        var otherSession = login(otherName);
        var empty = read(get("/api/notes").session(otherSession).param("userId", Long.toString(userId)));
        assertThat(empty.get("items").size()).isZero();
        long otherNote = notes.insert(otherId, "other-title", "other-body", TIME);
        var result = read(get("/api/notes").session(otherSession).param("userId", Long.toString(userId)));
        assertThat(ids(result)).containsExactly(otherNote);
        assertThat(result.toString()).doesNotContain("owner-private-title", "owner-private-body");
        assertThat(page(0, 20).get("items").size()).isEqualTo(1);
    }

    @Test
    void emptyParametersUseDefaults() throws Exception {
        var result = read(get("/api/notes").session(session).param("page", "").param("size", ""));
        assertThat(result.get("page").asInt()).isZero();
        assertThat(result.get("size").asInt()).isEqualTo(20);
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,10001", "page,1.5", "page,abc", "page,2147483648",
            "page,9223372036854775807", "page,1 OR 1=1", "size,-1", "size,0", "size,101",
            "size,1.5", "size,abc", "size,2147483648", "size,1 OR 1=1"})
    void invalidPaginationIs400WithoutEchoingInput(String parameter, String value) throws Exception {
        var response = mvc.perform(get("/api/notes").session(session).param(parameter, value))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("INVALID_REQUEST").doesNotContain("Exception", "SELECT", "OR 1=1");
    }

    @Test
    void anonymousRequestsAre401EvenWhenParametersAreInvalid() throws Exception {
        mvc.perform(get("/api/notes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notes").param("page", "-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void summaryAndPageToStringRedactTitlesAndPageCopiesItems() {
        var summary = new NoteSummaryResponse(1, "private-title", false, TIME, TIME);
        List<NoteSummaryResponse> items = new ArrayList<>(List.of(summary));
        var page = new NotePageResponse(items, 0, 20, false);
        items.clear();
        assertThat(page.items()).hasSize(1);
        assertThat(summary.toString()).doesNotContain("private-title");
        assertThat(page.toString()).doesNotContain("private-title");
    }

    private JsonNode page(int page, int size) throws Exception {
        return read(get("/api/notes").session(session).param("page", Integer.toString(page))
                .param("size", Integer.toString(size)));
    }
    private JsonNode read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    private List<Long> ids(JsonNode page) {
        List<Long> result = new ArrayList<>();
        page.get("items").forEach(item -> result.add(item.get("id").asLong()));
        return result;
    }
    private String uniqueName() { return "list_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
