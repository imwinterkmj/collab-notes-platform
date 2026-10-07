package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.collabnotes.platform.user.RegisterUserRequest;
import com.collabnotes.platform.user.UserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 实际执行 UPDATE 后制造 SELECT 故障，验证 Spring 事务而不是只验证注解存在。 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql,classpath:db/notes-test-schema.sql"
})
class NoteUpdateTransactionTests {
    @Autowired private NoteService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRegistrationService registration;
    @MockitoSpyBean private NoteRepository notes;
    private long userId;
    private long noteId;
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456Z");

    @BeforeEach
    void seed() {
        String name = "rollback_" + UUID.randomUUID().toString().replace("-", "").substring(0, 23);
        userId = registration.register(new RegisterUserRequest(name, "rollback-test-passphrase")).id();
        noteId = notes.insert(userId, "old-title", "old-content", TIME);
    }

    @Test
    void selectFailureAfterUpdateRollsBackBothTextAndTime() {
        doThrow(new DataAccessResourceFailureException("simulated read failure"))
                .when(notes).findOwnedById(noteId, userId);
        assertThatThrownBy(() -> service.update(noteId, userId, new UpdateNoteRequest("new-title", "new-content")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        unchanged();
    }

    @Test
    void notFoundAfterUpdateAlsoRollsBack() {
        doReturn(Optional.empty()).when(notes).findOwnedById(noteId, userId);
        assertThatThrownBy(() -> service.update(noteId, userId, new UpdateNoteRequest("new-title", "new-content")))
                .isInstanceOf(NoteNotFoundException.class);
        unchanged();
    }

    @Test
    void completionReadFailureRollsBackActualStateChangeAndTime() {
        doAnswer(invocation -> {
            // 此处仍在服务事务内，确认 UPDATE 确实已经把状态改成 true，再制造读取故障。
            assertThat(jdbc.queryForObject("SELECT is_completed FROM notes WHERE id = ? AND user_id = ?",
                    Boolean.class, noteId, userId)).isTrue();
            throw new DataAccessResourceFailureException("simulated completion read failure");
        }).when(notes).findOwnedById(noteId, userId);
        assertThatThrownBy(() -> service.setCompletion(noteId, userId, new UpdateNoteCompletionRequest(true)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        unchanged();
    }

    @Test
    void completionNotFoundAfterWriteAlsoRollsBack() {
        doReturn(Optional.empty()).when(notes).findOwnedById(noteId, userId);
        assertThatThrownBy(() -> service.setCompletion(noteId, userId, new UpdateNoteCompletionRequest(true)))
                .isInstanceOf(NoteNotFoundException.class);
        unchanged();
    }

    private void unchanged() {
        jdbc.query("SELECT title, content, is_completed, created_at, updated_at FROM notes WHERE id = ? AND user_id = ?", rs -> {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("title")).isEqualTo("old-title");
            assertThat(rs.getString("content")).isEqualTo("old-content");
            assertThat(rs.getBoolean("is_completed")).isFalse();
            var utc = java.time.LocalDateTime.ofInstant(TIME, java.time.ZoneOffset.UTC);
            assertThat(rs.getObject("created_at", java.time.LocalDateTime.class)).isEqualTo(utc);
            assertThat(rs.getObject("updated_at", java.time.LocalDateTime.class)).isEqualTo(utc);
            return null;
        }, noteId, userId);
    }
}
