package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.collabnotes.platform.reminder.ReminderDispatcher;
import com.collabnotes.platform.reminder.ReminderRepository;
import com.collabnotes.platform.support.CsrfTestSupport;
import com.collabnotes.platform.user.RegisterUserRequest;
import com.collabnotes.platform.user.UserRegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** H2 正确性/竞态/回滚验证，不冒充 MySQL 容量或高并发压测。 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql,classpath:db/notes-test-schema.sql"
})
@AutoConfigureMockMvc
class NoteTrashIntegrationTests {
    @Autowired private NoteService service;
    @Autowired private NoteRepository notes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @Autowired private ReminderRepository reminders;
    @Autowired private ReminderDispatcher dispatcher;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoSpyBean private NoteTrashRepository trash;
    private final List<Long> owners = new ArrayList<>();
    private long userId, noteId;
    private MockHttpSession session;
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456Z");
    private static final String PASSWORD = "trash-test-passphrase";

    @BeforeEach
    void seed() throws Exception {
        String name = "trash_" + UUID.randomUUID().toString().replace("-", "").substring(0, 23);
        userId = registration.register(new RegisterUserRequest(name, PASSWORD)).id(); owners.add(userId);
        noteId = notes.insert(userId, " 标题 🔔 <script> ", " 正文\n' OR 1=1 -- ", TIME);
        session = login(name);
    }

    @Test
    void deleteArchivesFullSnapshotAndRemovesFiredNotifications() throws Exception {
        var original = service.detail(noteId, userId);
        reminders.insert(noteId, TIME, TIME); dispatcher.dispatchDue(Instant.now(), 1000);
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, delete("/api/notes/" + noteId), session))
                .andExpect(status().isNoContent());
        assertThat(notes.findOwnedById(noteId, userId)).isEmpty();
        assertThat(reminders.findOwned(noteId, userId)).isEmpty();
        var page = service.trash(userId); assertThat(page.limit()).isEqualTo(30); assertThat(page.items()).hasSize(1);
        var saved = page.items().get(0);
        assertThat(saved.title()).isEqualTo(original.title()); assertThat(saved.content()).isEqualTo(original.content());
        assertThat(saved.completed()).isFalse(); assertThat(saved.createdAt()).isEqualTo(TIME);
        assertThat(saved.updatedAt()).isEqualTo(TIME); assertThat(saved.deletedAt()).isAfter(TIME);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications n JOIN reminders r ON r.id=n.reminder_id "
                + "WHERE r.note_id=?", Long.class, noteId)).isZero();
        mvc.perform(get("/api/notes/" + noteId).session(session)).andExpect(status().isNotFound());
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, delete("/api/notes/" + noteId), session))
                .andExpect(status().isNotFound());
        assertThat(service.trash(userId).items()).hasSize(1);
    }

    @Test
    void deletedPendingReminderCannotFireAndRestoreDoesNotReactivateIt() {
        reminders.insert(noteId, TIME, TIME);
        service.delete(noteId, userId);
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isZero();
        var restored = service.restore(service.trash(userId).items().get(0).id(), userId);
        assertThat(restored.id()).isNotEqualTo(noteId);
        assertThat(reminders.findOwned(restored.id(), userId)).isEmpty();
        assertThat(dispatcher.dispatchDue(Instant.now(), 1000)).isZero();
    }

    @Test
    void restoresCompletedContentCreationTimeWithNewIdAndFreshUpdateOnlyOnce() throws Exception {
        notes.setCompletionOwnedById(noteId, userId, true, TIME.plusSeconds(1));
        var original = service.detail(noteId, userId); service.delete(noteId, userId);
        long id = service.trash(userId).items().get(0).id();
        var response = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes/trash/" + id + "/restore"), session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var restored = mapper.readValue(response, NoteResponse.class);
        assertThat(restored.id()).isNotEqualTo(noteId); assertThat(restored.title()).isEqualTo(original.title());
        assertThat(restored.content()).isEqualTo(original.content()); assertThat(restored.completed()).isTrue();
        assertThat(restored.createdAt()).isEqualTo(original.createdAt()); assertThat(restored.updatedAt()).isAfter(original.updatedAt());
        assertThat(service.trash(userId).items()).isEmpty();
        mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes/trash/" + id + "/restore"), session))
                .andExpect(status().isNotFound());
        assertThat(service.list(userId, 0, 100).items()).hasSize(1);
    }

    @Test
    void retainsLatestThirtyPerOwnerWithStableTimestampTies() {
        List<Long> archiveIds = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            long id = notes.insert(userId, "note-" + i, "", TIME);
            trash.archive(userId, service.detail(id, userId), TIME); // 精确同时间测试 ID 次序。
            archiveIds.add(jdbc.queryForObject("SELECT MAX(id) FROM note_trash WHERE user_id=?", Long.class, userId));
        }
        // 服务删除新增最新一条并在事务内淘汰溢出的旧记录。
        service.delete(noteId, userId);
        var kept = service.trash(userId).items(); assertThat(kept).hasSize(30);
        assertThat(kept.get(0).title()).isEqualTo(" 标题 🔔 <script> ");
        assertThat(kept.stream().map(TrashNoteResponse::id)).doesNotContain(archiveIds.get(0), archiveIds.get(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_trash WHERE user_id=?", Long.class, userId)).isEqualTo(30);
    }

    @Test
    void concurrentDeletesStillRespectCapacity() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 32; i++) { ids.add(notes.insert(userId, "parallel-" + i, "", TIME)); }
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = ids.stream().map(id -> pool.submit(() -> service.delete(id, userId))).toList();
            for (var future : futures) { future.get(15, TimeUnit.SECONDS); }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_trash WHERE user_id=?", Long.class, userId)).isEqualTo(30);
        assertThat(service.list(userId, 0, 100).items()).extracting(NoteSummaryResponse::id).containsExactly(noteId);
    }

    @Test
    void ownerIsolationAuthenticationCsrfAndBadIds() throws Exception {
        service.delete(noteId, userId); long id = service.trash(userId).items().get(0).id();
        String name = "trash_" + UUID.randomUUID().toString().replace("-", "").substring(0, 23);
        long otherId = registration.register(new RegisterUserRequest(name, PASSWORD)).id(); owners.add(otherId);
        var other = login(name);
        var empty = mvc.perform(get("/api/notes/trash").session(other).param("userId", Long.toString(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(empty).get("items").size()).isZero();
        for (long attempt : new long[]{id, Long.MAX_VALUE}) {
            mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes/trash/" + attempt + "/restore"), other))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/notes/trash")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notes/trash/" + id + "/restore").session(session)).andExpect(status().isForbidden());
        for (String bad : new String[]{"0", "-1", "abc", "9223372036854775808"}) {
            mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes/trash/" + bad + "/restore"), session))
                    .andExpect(status().isBadRequest());
        }
        assertThat(service.trash(userId).items()).hasSize(1);
    }

    @Test
    void failureAfterActualDeleteRollsBackSnapshotNoteReminderAndNotification() {
        reminders.insert(noteId, TIME, TIME); dispatcher.dispatchDue(Instant.now(), 1000);
        long reminderId = reminders.findOwned(noteId, userId).orElseThrow().id();
        doAnswer(invocation -> {
            assertThat(notes.findOwnedById(noteId, userId)).isEmpty();
            throw new DataAccessResourceFailureException("simulated trim failure");
        }).when(trash).trimOwned(userId);
        assertThatThrownBy(() -> service.delete(noteId, userId)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(notes.findOwnedById(noteId, userId)).isPresent(); assertThat(service.trash(userId).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE reminder_id=?", Long.class, reminderId)).isEqualTo(1);
    }

    @Test
    void restoreRemovalFailureRollsBackInsertedNoteAndPreservesSnapshot() {
        service.delete(noteId, userId); long id = service.trash(userId).items().get(0).id();
        doAnswer(invocation -> {
            assertThat(invocation.callRealMethod()).isEqualTo(1);
            assertThat(service.list(userId, 0, 100).items()).hasSize(1);
            throw new DataAccessResourceFailureException("simulated failure after actual restore and snapshot removal");
        }).when(trash).deleteOwned(id, userId);
        assertThatThrownBy(() -> service.restore(id, userId)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(service.list(userId, 0, 100).items()).isEmpty(); assertThat(service.trash(userId).items()).hasSize(1);
    }

    @Test
    void twoConcurrentRestoresCreateOnlyOneNote() throws Exception {
        service.delete(noteId, userId); long id = service.trash(userId).items().get(0).id();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> restoreOrMissing(id));
            var second = pool.submit(() -> restoreOrMissing(id));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.trash(userId).items()).isEmpty(); assertThat(service.list(userId, 0, 100).items()).hasSize(1);
    }

    private boolean restoreOrMissing(long id) {
        try { service.restore(id, userId); return true; }
        catch (NoteNotFoundException missing) { return false; }
    }

    @Test
    void snapshotsDoNotLeakPrivateTextViaDebugToString() {
        service.delete(noteId, userId); var page = service.trash(userId);
        assertThat(page.toString()).doesNotContain("标题", "正文", "OR 1=1");
        assertThat(page.items().get(0).toString()).doesNotContain("标题", "正文", "OR 1=1");
    }

    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("username", name, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @AfterEach
    void cleanOnlyFixtures() {
        for (long id : owners) { jdbc.update("DELETE FROM notes WHERE user_id=?", id); jdbc.update("DELETE FROM users WHERE id=?", id); }
    }
}
