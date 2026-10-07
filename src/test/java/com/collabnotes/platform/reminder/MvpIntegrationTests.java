package com.collabnotes.platform.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.collabnotes.platform.note.NoteRepository;
import com.collabnotes.platform.note.NoteService;
import com.collabnotes.platform.note.UpdateNoteCompletionRequest;
import com.collabnotes.platform.support.CsrfTestSupport;
import com.collabnotes.platform.user.RegisterUserRequest;
import com.collabnotes.platform.user.UserRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql,classpath:db/notes-test-schema.sql"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MvpIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @Autowired private NoteRepository notes;
    @Autowired private NoteService noteService;
    @Autowired private ReminderService service;
    @Autowired private ReminderDispatcher dispatcher;
    @Autowired private NotificationService notifications;
    @MockitoSpyBean private ReminderRepository reminders;
    private long userId;
    private long noteId;
    private MockHttpSession session;
    private final List<Long> cleanupUsers = new ArrayList<>();
    private static final String PASSWORD = "mvp-test-passphrase";

    @BeforeEach
    void seed() throws Exception {
        String name = "mvp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        userId = registration.register(new RegisterUserRequest(name, PASSWORD)).id();
        cleanupUsers.add(userId);
        noteId = notes.insert(userId, "测试 <img src=x onerror=alert(1)> 🔔", "private-test-body",
                Instant.parse("2026-01-01T00:00:00Z"));
        session = login(name);
    }
    @Test
    void staticPageIsPublicButApisRemainProtected() throws Exception {
        for (String path : new String[]{"/", "/index.html", "/app.js", "/reminder-preferences.js", "/reminder-assets.js", "/reminder-alerts.js", "/styles.css"}) {
            var response = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getHeader("Content-Security-Policy")).contains("script-src 'self'", "object-src 'none'");
            assertThat(response.getHeader("Content-Security-Policy")).contains("img-src 'self' blob:", "media-src 'self' blob:")
                    .doesNotContain("script-src 'self' blob:", "unsafe-inline", "unsafe-eval");
        }
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications?unreadOnly=true")).andExpect(status().isUnauthorized());
        mvc.perform(get(reminderPath())).andExpect(status().isUnauthorized());
    }
    @Test
    void createsReschedulesAndCancelsOneReminder() throws Exception {
        Instant time = Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var first = putReminder(time.atOffset(ZoneOffset.ofHours(8)).toString());
        assertThat(Instant.parse(first.get("dueAt").asText())).isEqualTo(time);
        assertThat(first.get("generation").asLong()).isEqualTo(1);
        assertThat(first.get("status").asText()).isEqualTo("SCHEDULED");
        var next = putReminder(time.plusSeconds(30).toString());
        assertThat(next.get("id")).isEqualTo(first.get("id"));
        assertThat(next.get("generation").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE note_id = ?", Long.class, noteId)).isEqualTo(1);
        mvc.perform(write(delete(reminderPath()), session)).andExpect(status().isNoContent());
        mvc.perform(write(delete(reminderPath()), session)).andExpect(status().isNoContent());
        assertThat(service.get(noteId, userId).status()).isEqualTo("CANCELLED");
        assertThat(dispatcher.dispatchDue(time.plusSeconds(60), 1000)).isZero();
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"dueAt\":null}", "{\"dueAt\":123}", "{\"dueAt\":true}",
            "{\"dueAt\":[]}", "{\"dueAt\":\"\"}", "{\"dueAt\":\"private-time\"}", "{\"dueAt\":\"2026-10-07T12:00:00\"}",
            "{\"dueAt\":\"2000-01-01T00:00:00Z\"}", "{\"dueAt\":\"9999-01-01T00:00:00Z\"}",
            "{\"dueAt\":\"2099-01-01T00:00:00Z\",\"userId\":1}"})
    void rejectsInvalidOrUnzonedTimesAndExtraFields(String json) throws Exception {
        var response = mvc.perform(write(put(reminderPath()), session).content(json))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private-time", "Exception");
        assertThat(reminders.findOwned(noteId, userId)).isEmpty();
    }
    @Test
    void rejectsPastNearTimeAndOverYearAndCompletedNotes() throws Exception {
        for (Instant due : new Instant[]{Instant.now().minusSeconds(1), Instant.now().plusSeconds(366L * 86400)}) {
            mvc.perform(write(put(reminderPath()), session).content(mapper.writeValueAsString(Map.of("dueAt", due.toString()))))
                    .andExpect(status().isBadRequest());
        }
        noteService.setCompletion(noteId, userId, new UpdateNoteCompletionRequest(true));
        mvc.perform(write(put(reminderPath()), session).content(futureJson())).andExpect(status().isConflict());
    }
    @Test
    void isolatesAllReminderOperationsAndNotifications() throws Exception {
        var expected = putReminder(Instant.now().plusSeconds(60).toString());
        String otherName = "mvp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        long otherId = registration.register(new RegisterUserRequest(otherName, PASSWORD)).id();
        cleanupUsers.add(otherId);
        var other = login(otherName);
        var denied = mvc.perform(write(put(reminderPath()), other).param("userId", Long.toString(userId)).content(futureJson()))
                .andExpect(status().isNotFound()).andReturn();
        var missing = mvc.perform(write(put("/api/notes/" + Long.MAX_VALUE + "/reminder"), other).content(futureJson()))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(denied.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        mvc.perform(get(reminderPath()).session(other)).andExpect(status().isNotFound());
        mvc.perform(write(delete(reminderPath()), other)).andExpect(status().isNotFound());
        assertThat(mapper.readTree(mvc.perform(get(reminderPath()).session(session)).andReturn().getResponse().getContentAsString()))
                .isEqualTo(expected);
        dueNow(); dispatcher.dispatchDue(Instant.now(), 1000);
        var page = notifications.list(userId, 0, 20);
        assertThat(page.items()).hasSize(1);
        long notificationId = page.items().get(0).id();
        assertThat(notifications.list(otherId, 0, 20).items()).isEmpty();
        mvc.perform(write(patch("/api/notifications/" + notificationId + "/read"), other)).andExpect(status().isNotFound());
        assertThat(notifications.list(userId, 0, 20).items().get(0).read()).isFalse();
    }
    @Test
    void dueTaskFiresOnceAndReadIsIndependent() throws Exception {
        var created = putReminder(Instant.now().plusSeconds(60).toString());
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isZero();
        dueNow();
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isEqualTo(1);
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isZero();
        assertThat(service.get(noteId, userId).status()).isEqualTo("FIRED");
        var page = notifications.list(userId, 0, 20);
        assertThat(page.items()).hasSize(1);
        var notification = page.items().get(0);
        assertThat(notification.title()).contains("<img");
        assertThat(notification.read()).isFalse();
        assertThat(notification.createdAt()).isAfterOrEqualTo(notification.dueAt());
        assertThat(created.get("generation").asLong()).isEqualTo(1);
        for (int i = 0; i < 2; i++) {
            mvc.perform(write(patch("/api/notifications/" + notification.id() + "/read"), session))
                    .andExpect(status().isNoContent());
        }
        assertThat(notifications.list(userId, 0, 20).items().get(0).read()).isTrue();
        mvc.perform(write(delete(reminderPath()), session)).andExpect(status().isConflict());
        putReminder(Instant.now().plusSeconds(60).toString()); dueNow();
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isEqualTo(1);
        assertThat(notifications.list(userId, 0, 1).hasNext()).isTrue();
        assertThat(notifications.list(userId, 1, 1).items()).hasSize(1);
        assertThat(notifications.list(userId, 2, 1).items()).isEmpty();
    }

    @Test
    void unreadFilterExcludesReadRowsAndKeepsOwnershipAndPagination() throws Exception {
        putReminder(Instant.now().plusSeconds(60).toString()); dueNow(); dispatcher.dispatchDue(Instant.now(), 1000);
        long readId = notifications.list(userId, 0, 20).items().get(0).id();
        notifications.markRead(readId, userId);
        for (int i = 0; i < 2; i++) {
            putReminder(Instant.now().plusSeconds(60).toString()); dueNow(); dispatcher.dispatchDue(Instant.now(), 1000);
        }
        var first = mapper.readTree(mvc.perform(get("/api/notifications?unreadOnly=true&size=1").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.get("items").size()).isEqualTo(1);
        assertThat(first.get("items").get(0).get("read").asBoolean()).isFalse();
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        var second = notifications.listUnread(userId, 1, 1);
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasNext()).isFalse();
        assertThat(notifications.listUnread(userId, 2, 1).items()).isEmpty();
        assertThat(notifications.list(userId, 0, 20).items()).hasSize(3);
        long other = registration.register(new RegisterUserRequest("unread_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 22), PASSWORD)).id();
        cleanupUsers.add(other);
        assertThat(notifications.listUnread(other, 0, 20).items()).isEmpty();
        mvc.perform(get("/api/notifications?unreadOnly=nonsense").session(session)).andExpect(status().isBadRequest());
    }
    @Test
    void completesCancelPendingAndReopeningDoesNotResume() throws Exception {
        putReminder(Instant.now().plusSeconds(60).toString()); dueNow();
        noteService.setCompletion(noteId, userId, new UpdateNoteCompletionRequest(true));
        assertThat(service.get(noteId, userId).status()).isEqualTo("CANCELLED");
        noteService.setCompletion(noteId, userId, new UpdateNoteCompletionRequest(false));
        assertThat(service.get(noteId, userId).status()).isEqualTo("CANCELLED");
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isZero();
        putReminder(Instant.now().plusSeconds(60).toString()); dueNow(); dispatcher.dispatchDue(Instant.now(), 1000);
        noteService.setCompletion(noteId, userId, new UpdateNoteCompletionRequest(true));
        assertThat(notifications.list(userId, 0, 20).items()).hasSize(1);
    }
    @Test
    void deletionCascadesButCannotDeleteOtherUsersOrRepeat() throws Exception {
        putReminder(Instant.now().plusSeconds(60).toString()); dueNow(); dispatcher.dispatchDue(Instant.now(), 1000);
        String otherName = "mvp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        long otherId = registration.register(new RegisterUserRequest(otherName, PASSWORD)).id();
        cleanupUsers.add(otherId);
        long otherNote = notes.insert(otherId, "keep", "keep", Instant.now());
        mvc.perform(write(delete("/api/notes/" + otherNote), session)).andExpect(status().isNotFound());
        mvc.perform(write(delete("/api/notes/" + noteId), session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/notes/" + noteId).session(session)).andExpect(status().isNotFound());
        mvc.perform(write(delete("/api/notes/" + noteId), session)).andExpect(status().isNotFound());
        assertThat(reminders.findOwned(noteId, userId)).isEmpty();
        assertThat(notifications.list(userId, 0, 20).items()).isEmpty();
        assertThat(notes.findOwnedById(otherNote, otherId)).isPresent();
    }
    @Test
    void writingRequiresSessionAndCsrf() throws Exception {
        mvc.perform(write(put(reminderPath()), null).content(futureJson())).andExpect(status().isUnauthorized());
        for (var request : new MockHttpServletRequestBuilder[]{put(reminderPath()), delete(reminderPath()),
                delete("/api/notes/" + noteId), patch("/api/notifications/1/read")}) {
            mvc.perform(request.session(session).contentType(MediaType.APPLICATION_JSON).content(futureJson()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/notifications?page=-1").session(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/notifications?size=101").session(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/notes/0/reminder").session(session)).andExpect(status().isBadRequest());
        mvc.perform(write(put(reminderPath()), session).contentType(MediaType.TEXT_PLAIN).content("private-time"))
                .andExpect(status().isUnsupportedMediaType());
    }
    @Test
    void failedFireRollsBackNotificationAndStateAndCanRetry(CapturedOutput output) throws Exception {
        putReminder(Instant.now().plusSeconds(60).toString()); dueNow();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("SQL private-reminder-title secret-test-password");
        }).when(reminders).fire(any(), anyString(), any());
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isZero();
        assertThat(service.get(noteId, userId).status()).isEqualTo("SCHEDULED");
        assertThat(notifications.list(userId, 0, 20).items()).isEmpty();
        assertThat(output.getAll()).doesNotContain("private-reminder-title", "secret-test-password");
        doCallRealMethod().when(reminders).fire(any(), anyString(), any());
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isEqualTo(1);
        assertThat(notifications.list(userId, 0, 20).items()).hasSize(1);
    }
    @Test
    void completionFailureRollsBackStateAndReminder() throws Exception {
        putReminder(Instant.now().plusSeconds(60).toString());
        doAnswer(invocation -> { invocation.callRealMethod(); throw new DataAccessResourceFailureException("private-failure"); })
                .when(reminders).cancelPending(anyLong(), any());
        assertThatThrownBy(() -> noteService.setCompletion(noteId, userId, new UpdateNoteCompletionRequest(true)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(notes.findOwnedById(noteId, userId).orElseThrow().completed()).isFalse();
        assertThat(service.get(noteId, userId).status()).isEqualTo("SCHEDULED");
    }
    @Test
    void twoConcurrentScansProduceOnlyOneNotification() throws Exception {
        putReminder(Instant.now().plusSeconds(60).toString()); dueNow();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> dispatcher.dispatchDue(Instant.now(), 1000));
            var second = pool.submit(() -> dispatcher.dispatchDue(Instant.now(), 1000));
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(notifications.list(userId, 0, 20).items()).hasSize(1);
    }
    @Test
    void staleScannedCandidateCannotFireRescheduledOrDeletedNote() throws Exception {
        var first = putReminder(Instant.now().plusSeconds(60).toString());
        dueNow();
        var candidates = reminders.dueCandidates(Instant.now(), 1000);
        assertThat(candidates).hasSize(1);
        putReminder(Instant.now().plusSeconds(600).toString());
        doReturn(candidates).when(reminders).dueCandidates(any(), anyInt());
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isZero();
        assertThat(service.get(noteId, userId).generation()).isEqualTo(first.get("generation").asLong() + 1);
        assertThat(notifications.list(userId, 0, 20).items()).isEmpty();
        noteService.delete(noteId, userId);
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isZero();
    }
    @Test
    void eachScanIsBoundedAndOldestDueTaskIsFirst() throws Exception {
        putReminder(Instant.now().plusSeconds(60).toString()); dueNow();
        long second = notes.insert(userId, "second", "second", Instant.now());
        service.set(second, userId, new SetReminderRequest(Instant.now().plusSeconds(60).toString()));
        jdbc.update("UPDATE reminders SET due_at = ? WHERE note_id = ?",
                LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC), second);
        assertThat(dispatcher.dispatchDue(Instant.now(), 1)).isEqualTo(1);
        assertThat(service.get(noteId, userId).status()).isEqualTo("FIRED");
        assertThat(service.get(second, userId).status()).isEqualTo("SCHEDULED");
        assertThat(dispatcher.dispatchDue(Instant.now(), 1)).isEqualTo(1);
        assertThat(notifications.list(userId, 0, 20).items()).hasSize(2);
        assertThatThrownBy(() -> dispatcher.dispatchDue(Instant.now(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dispatcher.dispatchDue(Instant.now(), 1001)).isInstanceOf(IllegalArgumentException.class);
    }
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, MockHttpSession target) throws Exception {
        return CsrfTestSupport.withCsrf(mvc, mapper, request, target).contentType(MediaType.APPLICATION_JSON);
    }
    private JsonNode putReminder(String due) throws Exception {
        return mapper.readTree(mvc.perform(write(put(reminderPath()), session)
                .content(mapper.writeValueAsString(Map.of("dueAt", due)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    private String reminderPath() { return "/api/notes/" + noteId + "/reminder"; }
    private String futureJson() throws Exception {
        return mapper.writeValueAsString(Map.of("dueAt", Instant.now().plusSeconds(60).toString()));
    }
    private void dueNow() {
        jdbc.update("UPDATE reminders SET due_at = ? WHERE note_id = ?",
                LocalDateTime.ofInstant(Instant.now().minusSeconds(2), ZoneOffset.UTC), noteId);
    }
    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) mvc.perform(write(post("/api/auth/login"), null)
                .content(mapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @AfterEach
    void cleanThisTestsH2Fixtures() {
        for (long id : cleanupUsers) {
            jdbc.update("DELETE FROM notes WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }
}
