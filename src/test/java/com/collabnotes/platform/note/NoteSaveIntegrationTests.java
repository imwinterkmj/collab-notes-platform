package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.collabnotes.platform.reminder.ReminderRepository;
import com.collabnotes.platform.reminder.ReminderService;
import com.collabnotes.platform.reminder.SetReminderRequest;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 仅使用临时 H2 验证真实 Spring 事务，既有 MySQL 学习数据不参与。 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql,classpath:db/notes-test-schema.sql"
})
@AutoConfigureMockMvc
class NoteSaveIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @Autowired private NoteService notes;
    @Autowired private NoteSaveService saving;
    @Autowired private ReminderService reminderService;
    @MockitoSpyBean private ReminderRepository reminders;
    private MockHttpSession session;
    private long userId;

    @BeforeEach
    void login() throws Exception {
        String name = "save_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        userId = registration.register(new RegisterUserRequest(name, "local-save-test")).id();
        var login = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", name, "password", "local-save-test"))))
                .andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    void createsTextAndScheduledReminderInOneRequest() throws Exception {
        var saved = create("SET", future());
        assertThat(saved.get("note").get("title").asText()).isEqualTo("会议 🔔");
        assertThat(saved.get("reminder").get("noteId")).isEqualTo(saved.get("note").get("id"));
        assertThat(saved.get("reminder").get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(saved.get("reminder").get("generation").asInt()).isEqualTo(1);
        assertThat(noteCount()).isEqualTo(1);
        assertThat(saved.get("note").has("userId")).isFalse();
    }

    @Test
    void createsWithoutReminderAndPreservesUnicodeLengthBoundaries() throws Exception {
        var request = new SaveNoteRequest("🔔".repeat(120), "🔔".repeat(10000), "KEEP", null);
        var result = mvc.perform(write(post("/api/notes/save"), request)).andExpect(status().isCreated()).andReturn();
        var saved = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(saved.get("reminder").isNull()).isTrue();
        assertThat(saved.get("note").get("content").asText()).isEqualTo(request.content());
    }

    @Test
    void updatesTextAndReschedulesTogetherButKeepDoesNotIncrementGeneration() throws Exception {
        var first = create("SET", future());
        long id = first.get("note").get("id").asLong();
        var changed = update(id, "改过的会议", "改正文", "SET", Instant.now().plusSeconds(900).toString());
        assertThat(changed.get("note").get("createdAt")).isEqualTo(first.get("note").get("createdAt"));
        assertThat(changed.get("reminder").get("generation").asInt()).isEqualTo(2);
        var kept = update(id, "只改标题", "正文", "KEEP", null);
        assertThat(kept.get("reminder")).isEqualTo(changed.get("reminder"));
        assertThat(noteCount()).isEqualTo(1);
    }

    @Test
    void cancellationIsAtomicWithTextAndNoReminderCancellationIsSafe() throws Exception {
        var first = create("SET", future());
        var cancelled = update(first.get("note").get("id").asLong(), "取消后的标题", "正文", "CANCEL", null);
        assertThat(cancelled.get("note").get("title").asText()).isEqualTo("取消后的标题");
        assertThat(cancelled.get("reminder").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("reminder").get("generation").asInt()).isEqualTo(1);
        assertThat(create("CANCEL", null).get("reminder").isNull()).isTrue();
    }

    @Test
    void keepOrCancelNeverRearmsFiredReminderOrWithdrawsItsNotification() throws Exception {
        var first = create("SET", future());
        long id = first.get("note").get("id").asLong();
        var reminder = reminders.findOwned(id, userId).orElseThrow();
        reminders.fire(reminder, "到期时的标题", Instant.now());
        var kept = update(id, "已到期后编辑", "正文", "KEEP", null);
        var cancelled = update(id, "继续编辑", "正文", "CANCEL", null);
        assertThat(kept.get("reminder").get("status").asText()).isEqualTo("FIRED");
        assertThat(cancelled.get("reminder")).isEqualTo(kept.get("reminder"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE reminder_id = ?", Long.class, reminder.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"past", "far", "local", "broken"})
    void invalidReminderTimeRollsBackNewNoteAndEditedText(String mode) throws Exception {
        String due = switch (mode) {
            case "past" -> Instant.now().minusSeconds(30).toString();
            case "far" -> Instant.now().plusSeconds(366L * 86400).toString();
            case "local" -> "2027-01-01T12:00:00";
            default -> "not-a-time";
        };
        mvc.perform(write(post("/api/notes/save"), request("新标题", "秘密正文", "SET", due)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME"));
        assertThat(noteCount()).isZero();
        var note = notes.create(userId, new CreateNoteRequest("原标题", "原正文"));
        mvc.perform(write(put("/api/notes/" + note.id() + "/save"), request("新标题", "新正文", "SET", due)))
                .andExpect(status().isBadRequest());
        assertThat(notes.detail(note.id(), userId)).isEqualTo(note);
        assertThat(reminders.findOwned(note.id(), userId)).isEmpty();
    }

    @Test
    void completedNoteRejectsSetAndRollsBackTextButAllowsContentOnlySave() throws Exception {
        var note = notes.create(userId, new CreateNoteRequest("原标题", "原正文"));
        var completed = notes.setCompletion(note.id(), userId, new UpdateNoteCompletionRequest(true));
        mvc.perform(write(put("/api/notes/" + note.id() + "/save"), request("新标题", "新正文", "SET", future())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTE_COMPLETED"));
        assertThat(notes.detail(note.id(), userId)).isEqualTo(completed);
        assertThat(update(note.id(), "只编辑完成记录", "", "KEEP", null).get("note").get("completed").asBoolean()).isTrue();
    }

    @Test
    void foreignNoteAndMissingIdBothReturn404WithoutChangingOwnerData() throws Exception {
        long other = registration.register(new RegisterUserRequest("other_" + UUID.randomUUID().toString().replace("-", "").substring(0, 22), "local-test")).id();
        var note = notes.create(other, new CreateNoteRequest("私密标题", "私密正文"));
        String first = mvc.perform(write(put("/api/notes/" + note.id() + "/save"), request("攻击", "攻击", "SET", future())))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String absent = mvc.perform(write(put("/api/notes/" + Long.MAX_VALUE + "/save"), request("攻击", "攻击", "KEEP", null)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(first).isEqualTo(absent).doesNotContain("私密");
        assertThat(notes.detail(note.id(), other)).isEqualTo(note);
        assertThat(reminders.findOwned(note.id(), other)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"title\":4,\"content\":\"x\",\"reminderAction\":\"KEEP\"}",
            "{\"title\":\"x\",\"content\":false,\"reminderAction\":\"KEEP\"}",
            "{\"title\":\"x\",\"content\":\"x\",\"reminderAction\":true}",
            "{\"title\":\"x\",\"content\":\"x\",\"reminderAction\":\"SET\"}",
            "{\"title\":\"x\",\"content\":\"x\",\"reminderAction\":\"KEEP\",\"dueAt\":\"ignored\"}",
            "{\"title\":\"x\",\"content\":\"x\",\"reminderAction\":\"CANCEL\",\"dueAt\":1}",
            "{\"title\":\"x\",\"content\":\"x\",\"reminderAction\":\"OTHER\"}",
            "{\"title\":\"x\",\"content\":\"x\",\"reminderAction\":\"KEEP\",\"userId\":1}",
            "{\"title\":\"x\",\"content\":\"x\",\"reminderAction\":\"KEEP\",\"completed\":true}"})
    void rejectsMalformedMissingWrongTypeUnknownFieldsAndActionTimeMismatch(String body) throws Exception {
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes/save"), session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertThat(noteCount()).isZero();
    }

    @Test
    void validatesTextBoundariesAndRequiresLoginAndCsrfOnBothSaveRoutes() throws Exception {
        for (String title : new String[] {"", "  ", "🔔".repeat(121)}) {
            mvc.perform(write(post("/api/notes/save"), request(title, "", "KEEP", null))).andExpect(status().isBadRequest());
        }
        mvc.perform(write(post("/api/notes/save"), request("标题", "🔔".repeat(10001), "KEEP", null))).andExpect(status().isBadRequest());
        for (var method : new MockHttpServletRequestBuilder[] {post("/api/notes/save"), put("/api/notes/1/save")}) {
            mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, method, null).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request("标题", "", "KEEP", null)))).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/notes/save").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request("标题", "", "KEEP", null)))).andExpect(status().isForbidden());
        mvc.perform(put("/api/notes/1/save").session(session).header("X-CSRF-TOKEN", "forged")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request("标题", "", "KEEP", null))))
                .andExpect(status().isForbidden());
        assertThat(noteCount()).isZero();
    }

    @Test
    void storageFailureAfterActualReminderInsertRollsBackBothRows() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            long id = invocation.getArgument(0);
            assertThat(reminders.findOwned(id, userId)).isPresent();
            throw new DataAccessResourceFailureException("simulated private SQL failure");
        }).when(reminders).insert(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> saving.save(null, userId, request("私密标题", "私密正文", "SET", future())))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(noteCount()).isZero();
    }

    @Test
    void failureAfterActualRescheduleRollsBackTextTimeAndGeneration() {
        var note = notes.create(userId, new CreateNoteRequest("原标题", "原正文"));
        var reminder = reminderService.set(note.id(), userId, new SetReminderRequest(future()));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(reminders.findOwned(note.id(), userId).orElseThrow().generation()).isEqualTo(2);
            throw new DataAccessResourceFailureException("simulated private SQL failure");
        }).when(reminders).reschedule(org.mockito.ArgumentMatchers.eq(reminder.id()), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> saving.save(note.id(), userId, request("新标题", "新正文", "SET", future())))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(notes.detail(note.id(), userId)).isEqualTo(note);
        assertThat(reminders.findOwned(note.id(), userId).orElseThrow()).isEqualTo(reminder);
    }

    @Test
    void failureAfterActualCancelRollsBackTextAndReminder() {
        var note = notes.create(userId, new CreateNoteRequest("原标题", "原正文"));
        var reminder = reminderService.set(note.id(), userId, new SetReminderRequest(future()));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(reminders.findOwned(note.id(), userId).orElseThrow().status()).isEqualTo("CANCELLED");
            throw new DataAccessResourceFailureException("simulated private SQL failure");
        }).when(reminders).cancelPending(org.mockito.ArgumentMatchers.eq(note.id()), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> saving.save(note.id(), userId, request("新标题", "新正文", "CANCEL", null)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(notes.detail(note.id(), userId)).isEqualTo(note);
        assertThat(reminders.findOwned(note.id(), userId).orElseThrow()).isEqualTo(reminder);
    }

    @Test
    void redactsRequestAndCombinedResponseToString() {
        var request = request("私密标题", "私密正文", "KEEP", null);
        assertThat(request.toString()).doesNotContain("私密");
        var saved = saving.save(null, userId, request);
        assertThat(saved.toString()).doesNotContain("私密");
    }

    private static SaveNoteRequest request(String title, String content, String action, String due) {
        return new SaveNoteRequest(title, content, action, due);
    }
    private String future() { return Instant.now().plusSeconds(600).toString(); }
    private long noteCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE user_id = ?", Long.class, userId); }
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder method, SaveNoteRequest request) throws Exception {
        return CsrfTestSupport.withCsrf(mvc, mapper, method, session).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request));
    }
    private JsonNode create(String action, String due) throws Exception {
        var result = mvc.perform(write(post("/api/notes/save"), request("会议 🔔", "会议正文", action, due)))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode update(long id, String title, String content, String action, String due) throws Exception {
        var result = mvc.perform(write(put("/api/notes/" + id + "/save"), request(title, content, action, due)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
}
