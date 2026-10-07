package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.collabnotes.platform.reminder.ReminderRepository;
import com.collabnotes.platform.user.RegisterUserRequest;
import com.collabnotes.platform.user.UserRegistrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 显式开启；不建表、不启动扫描器，只创建/清理本次精确账号及其记录。 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TRASH_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${MYSQL_TEST_URL:jdbc:mysql://localhost:3306/collab_notes?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}",
        "spring.datasource.username=${MYSQL_TEST_USER:collab_notes}",
        "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.sql.init.mode=never", "app.reminders.scheduler-enabled=false"
})
class NoteTrashMySqlTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NoteRepository notes;
    @Autowired private NoteService service;
    @Autowired private ReminderRepository reminders;
    @Autowired private UserRegistrationService registration;
    private final List<Long> owners = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private List<Long> baseline;

    @Test
    void realMysqlArchiveCapRestoreUnicodeAndIncompleteOrdering() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("collab_notes");
        baseline = statistics();
        long owner = account(), other = account();
        Instant time = Instant.parse("2026-01-01T00:00:00.123456Z");
        long completed = notes.insert(owner, "🔔".repeat(120), "🔔".repeat(10000), time);
        notes.setCompletionOwnedById(completed, owner, true, time.plusSeconds(5));
        long pending = notes.insert(owner, "still-pending", "", time);
        assertThat(service.list(owner, 0, 20).items()).extracting(NoteSummaryResponse::id).containsExactly(pending, completed);
        reminders.insert(completed, Instant.now().plusSeconds(3600), time);
        service.delete(completed, owner);
        assertThat(reminders.findOwned(completed, owner)).isEmpty();
        var copy = service.trash(owner).items().get(0);
        assertThat(copy.title()).isEqualTo("🔔".repeat(120)); assertThat(copy.content()).isEqualTo("🔔".repeat(10000));
        assertThat(service.trash(other).items()).isEmpty();
        assertThatThrownBy(() -> service.restore(copy.id(), other)).isInstanceOf(NoteNotFoundException.class);
        var restored = service.restore(copy.id(), owner);
        assertThat(restored.id()).isNotEqualTo(completed); assertThat(restored.createdAt()).isEqualTo(time);
        assertThat(restored.completed()).isTrue(); assertThat(restored.content()).isEqualTo(copy.content());
        assertThat(reminders.findOwned(restored.id(), owner)).isEmpty();
        assertThat(service.trash(owner).items()).isEmpty();
        assertThatThrownBy(() -> service.restore(copy.id(), owner)).isInstanceOf(NoteNotFoundException.class);
        for (int i = 0; i < 31; i++) {
            long id = notes.insert(owner, "archive-" + i, "", time);
            service.delete(id, owner);
        }
        assertThat(service.trash(owner).items()).hasSize(30);
        assertThat(service.trash(owner).items()).extracting(TrashNoteResponse::title).doesNotContain("archive-0").contains("archive-30");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_trash WHERE user_id=?", Long.class, owner)).isEqualTo(30);
        assertThat(notes.findOwnedById(pending, owner)).isPresent();
    }

    private long account() {
        String name = "trashsql_" + UUID.randomUUID().toString().replace("-", "").substring(0, 23);
        long id = registration.register(new RegisterUserRequest(name, "trash-mysql-test-passphrase")).id();
        owners.add(id); names.add(name); return id;
    }
    private List<Long> statistics() {
        var result = new ArrayList<Long>();
        for (String table : List.of("users", "notes", "reminders", "notifications", "note_trash")) {
            result.add(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
            result.add(jdbc.queryForObject("SELECT COALESCE(SUM(id),0) FROM " + table, Long.class));
        }
        return result;
    }
    @AfterEach
    void cleanupOnlyFixturesAndVerifyOriginalStatistics() {
        int notesRemoved = 0, archivesRemoved = 0;
        for (int i = 0; i < owners.size(); i++) {
            long id = owners.get(i);
            archivesRemoved += jdbc.update("DELETE FROM note_trash WHERE user_id=?", id);
            notesRemoved += jdbc.update("DELETE FROM notes WHERE user_id=?", id);
            assertThat(jdbc.update("DELETE FROM users WHERE id=? AND username=?", id, names.get(i))).isEqualTo(1);
        }
        System.out.println("MySQL trash fixture cleanup: notes=" + notesRemoved + ", archives=" + archivesRemoved + ", users=" + owners.size());
        if (baseline != null) assertThat(statistics()).isEqualTo(baseline);
    }
}
