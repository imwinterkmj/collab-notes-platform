package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.collabnotes.platform.reminder.ReminderRepository;
import com.collabnotes.platform.reminder.ReminderResponse;
import com.collabnotes.platform.support.HttpSessionTestClient;
import com.collabnotes.platform.user.RegisterUserRequest;
import com.collabnotes.platform.user.UserRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式开启，随机 HTTP 端口，扫描关闭；故障注入只在本测试应用内作用于临时记录。 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_SAVE_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${MYSQL_TEST_URL:jdbc:mysql://localhost:3306/collab_notes?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}",
        "spring.datasource.username=${MYSQL_TEST_USER:collab_notes}",
        "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.sql.init.mode=never", "app.reminders.scheduler-enabled=false"
})
class NoteSaveMySqlTests {
    private static final List<String> TABLES = List.of("users", "notes", "reminders", "notifications", "note_trash");
    private static final String PASSWORD = "save-mysql-fixture-password";
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    @Autowired private NoteService notes;
    @Autowired private TransactionTemplate transaction;
    @MockitoSpyBean private ReminderRepository reminders;
    private final List<String> fixtureNames = new ArrayList<>();
    private Map<String, Snapshot> baseline;
    private long ownerId;
    private HttpSessionTestClient owner;

    @BeforeEach
    void verifyTargetAndCaptureLearningDataBeforeAnyWrite() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("collab_notes");
        for (String table : TABLES) {
            assertThat(jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", String.class, table)).isEqualTo("InnoDB");
        }
        baseline = snapshot();
        ownerId = account();
        owner = new HttpSessionTestClient(port, mapper);
        login(owner, fixtureNames.get(0));
    }

    @Test
    void realHttpSaveLifecycleUnicodeKeepCancelFiredAndAccessProtection() throws Exception {
        Instant due = future();
        var first = created(owner, request("🔔".repeat(120), "🔔".repeat(10000), "SET", due.toString()));
        long id = first.get("note").get("id").asLong();
        assertThat(first.get("note").get("content").asText()).isEqualTo("🔔".repeat(10000));
        assertThat(reminders.findOwned(id, ownerId).orElseThrow().dueAt()).isEqualTo(due);
        assertThat(first.get("reminder").get("generation").asLong()).isEqualTo(1);
        var changed = updated(owner, id, request("改期后的标题", "改过的正文", "SET", due.plusSeconds(3600).toString()));
        assertThat(changed.get("note").get("createdAt")).isEqualTo(first.get("note").get("createdAt"));
        assertThat(changed.get("reminder").get("generation").asLong()).isEqualTo(2);
        var kept = updated(owner, id, request("只修改文字", "新正文", "KEEP", null));
        assertThat(kept.get("reminder")).isEqualTo(changed.get("reminder"));
        var cancelled = updated(owner, id, request("取消时修改文字", "", "CANCEL", null));
        assertThat(cancelled.get("reminder").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("reminder").get("generation").asLong()).isEqualTo(2);

        updated(owner, id, request("待触发的标题", "", "SET", due.toString()));
        ReminderResponse scheduled = reminders.findOwned(id, ownerId).orElseThrow();
        // 只对精确临时记录生成通知，不调用可能扫描学习任务的全库调度入口，也不改成已到期时间。
        transaction.executeWithoutResult(status -> reminders.fire(scheduled, "临时通知", Instant.now()));
        var fired = updated(owner, id, request("触发后改正文", "不应重排", "KEEP", null));
        var noWithdrawal = updated(owner, id, request("不撤回已触发通知", "", "CANCEL", null));
        assertThat(noWithdrawal.get("reminder")).isEqualTo(fired.get("reminder"));
        assertThat(fired.get("reminder").get("status").asText()).isEqualTo("FIRED");
        assertThat(fired.get("reminder").get("generation").asLong()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE reminder_id=?", Long.class, scheduled.id())).isEqualTo(1);

        var plain = created(owner, request("没有提醒", "", "KEEP", null));
        long plainId = plain.get("note").get("id").asLong();
        assertThat(plain.get("reminder").isNull()).isTrue();
        assertThat(updated(owner, plainId, request("仍无提醒", "", "CANCEL", null)).get("reminder").isNull()).isTrue();
        notes.setCompletion(plainId, ownerId, new UpdateNoteCompletionRequest(true));
        var completed = notes.detail(plainId, ownerId);
        assertThat(owner.put(path(plainId), request("不应写入", "不应写入", "SET", due.toString())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notes.detail(plainId, ownerId)).isEqualTo(completed);
        assertThat(updated(owner, plainId, request("完成后只改正文", "", "KEEP", null)).get("note").get("completed").asBoolean()).isTrue();

        var beforeInvalid = notes.detail(id, ownerId);
        var reminderBeforeInvalid = reminders.findOwned(id, ownerId).orElseThrow();
        var invalid = request("失败的修改", "草稿", "SET", Instant.now().minusSeconds(30).toString());
        assertThat(owner.post("/api/notes/save", invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(owner.put(path(id), invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noteCount(ownerId)).isEqualTo(2);
        assertThat(notes.detail(id, ownerId)).isEqualTo(beforeInvalid);
        assertThat(reminders.findOwned(id, ownerId).orElseThrow()).isEqualTo(reminderBeforeInvalid);
        assertThat(owner.post("/api/notes/save", Map.of("title", "x", "content", "", "reminderAction", "KEEP", "userId", ownerId))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        long otherId = account();
        var other = new HttpSessionTestClient(port, mapper); login(other, fixtureNames.get(1));
        var privateNote = created(other, request("另一个账号", "私密", "KEEP", null));
        long privateId = privateNote.get("note").get("id").asLong();
        var foreign = owner.put(path(privateId), request("越权修改", "", "SET", due.toString()));
        var missing = owner.put(path(Long.MAX_VALUE), request("缺失", "", "KEEP", null));
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody()).isEqualTo(missing.getBody());
        assertThat(notes.detail(privateId, otherId).title()).isEqualTo("另一个账号");
        assertThat(reminders.findOwned(privateId, otherId)).isEmpty();
        var anonymous = new HttpSessionTestClient(port, mapper);
        assertThat(anonymous.post("/api/notes/save", request("匿名", "", "KEEP", null)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.put(path(id), request("匿名", "", "KEEP", null)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withoutCsrf("POST", "/api/notes/save")).isEqualTo(403);
        assertThat(withoutCsrf("PUT", path(id))).isEqualTo(403);
    }

    @Test
    void failureAfterActualMysqlReminderInsertRollsBackNewNoteAndReminder() throws Exception {
        AtomicBoolean wrote = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            long id = invocation.getArgument(0);
            assertThat(reminders.findOwned(id, ownerId)).isPresent();
            assertThat(noteCount(ownerId)).isEqualTo(1);
            wrote.set(true); throw failure();
        }).when(reminders).insert(anyLong(), any(), any());
        expectSafeFailure(owner.post("/api/notes/save", request("未提交的正文", "仅临时草稿", "SET", future().toString())));
        assertThat(wrote).isTrue();
        assertThat(noteCount(ownerId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders r LEFT JOIN notes n ON n.id=r.note_id WHERE n.id IS NULL", Long.class)).isZero();
    }

    @Test
    void failureAfterActualMysqlRescheduleRollsBackTextTimeAndGeneration() throws Exception {
        var saved = created(owner, request("原标题", "原正文", "SET", future().toString()));
        long id = saved.get("note").get("id").asLong();
        var noteBefore = notes.detail(id, ownerId); var reminderBefore = reminders.findOwned(id, ownerId).orElseThrow();
        AtomicBoolean wrote = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(reminders.findOwned(id, ownerId).orElseThrow().generation()).isEqualTo(2);
            assertThat(notes.detail(id, ownerId).title()).isEqualTo("失败的新标题");
            wrote.set(true); throw failure();
        }).when(reminders).reschedule(eq(reminderBefore.id()), any(), any());
        expectSafeFailure(owner.put(path(id), request("失败的新标题", "失败的新正文", "SET", future().plusSeconds(3600).toString())));
        assertThat(wrote).isTrue(); assertThat(notes.detail(id, ownerId)).isEqualTo(noteBefore);
        assertThat(reminders.findOwned(id, ownerId).orElseThrow()).isEqualTo(reminderBefore);
    }

    @Test
    void failureAfterActualMysqlCancelRollsBackTextAndReminderStatus() throws Exception {
        var saved = created(owner, request("原标题", "原正文", "SET", future().toString()));
        long id = saved.get("note").get("id").asLong();
        var noteBefore = notes.detail(id, ownerId); var reminderBefore = reminders.findOwned(id, ownerId).orElseThrow();
        AtomicBoolean wrote = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(reminders.findOwned(id, ownerId).orElseThrow().status()).isEqualTo("CANCELLED");
            assertThat(notes.detail(id, ownerId).title()).isEqualTo("失败的新标题");
            wrote.set(true); throw failure();
        }).when(reminders).cancelPending(eq(id), any());
        expectSafeFailure(owner.put(path(id), request("失败的新标题", "失败的新正文", "CANCEL", null)));
        assertThat(wrote).isTrue(); assertThat(notes.detail(id, ownerId)).isEqualTo(noteBefore);
        assertThat(reminders.findOwned(id, ownerId).orElseThrow()).isEqualTo(reminderBefore);
    }

    private long account() {
        String name = "savesql_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Long.class, name)).isZero();
        fixtureNames.add(name);
        return registration.register(new RegisterUserRequest(name, PASSWORD)).id();
    }
    private void login(HttpSessionTestClient client, String name) throws Exception {
        assertThat(client.post("/api/auth/login", Map.of("username", name, "password", PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    private JsonNode created(HttpSessionTestClient client, SaveNoteRequest body) throws Exception {
        var result = client.post("/api/notes/save", body); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(result.getBody());
    }
    private JsonNode updated(HttpSessionTestClient client, long id, SaveNoteRequest body) throws Exception {
        var result = client.put(path(id), body); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(result.getBody());
    }
    private void expectSafeFailure(ResponseEntity<String> result) throws Exception {
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(mapper.readTree(result.getBody()).get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.getBody()).doesNotContain("fixture-write-failure", "SQL", "失败的新标题", "仅临时草稿");
    }
    private int withoutCsrf(String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Cookie", "JSESSIONID=" + owner.sessionId()).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request("无 CSRF", "", "KEEP", null)))).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    private static String path(long id) { return "/api/notes/" + id + "/save"; }
    private static Instant future() { return Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.MICROS); }
    private static SaveNoteRequest request(String title, String content, String action, String dueAt) {
        return new SaveNoteRequest(title, content, action, dueAt);
    }
    private static DataAccessResourceFailureException failure() { return new DataAccessResourceFailureException("fixture-write-failure"); }
    private long noteCount(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE user_id=?", Long.class, id); }

    private record Snapshot(long count, String idSum, String fingerprint) { }
    private Map<String, Snapshot> snapshot() throws Exception {
        var result = new LinkedHashMap<String, Snapshot>();
        for (String table : TABLES) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 只在内存形成指纹，禁止输出或保存原文/凭据；按主键排序，避免扫描顺序影响比较。
            jdbc.query("SELECT * FROM " + table + " ORDER BY id", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                    Object value = rs.getObject(column);
                    String text = value == null ? "N;" : "V" + value.toString().length() + ":" + value + ";";
                    digest.update(text.getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) '\n');
            });
            result.put(table, new Snapshot(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
                    jdbc.queryForObject("SELECT COALESCE(SUM(id),0) FROM " + table, String.class), HexFormat.of().formatHex(digest.digest())));
        }
        return result;
    }

    @AfterEach
    void cleanupOnlyExactFixtureAccountsAndVerifyAllOriginalRowsUnchanged() throws Exception {
        long notesRemoved = 0, usersRemoved = 0, remindersRemoved = 0, notificationsRemoved = 0, trashRemoved = 0;
        for (String name : fixtureNames) {
            for (long id : jdbc.queryForList("SELECT id FROM users WHERE username=?", Long.class, name)) {
                remindersRemoved += jdbc.queryForObject("SELECT COUNT(*) FROM reminders r JOIN notes n ON n.id=r.note_id WHERE n.user_id=?", Long.class, id);
                notificationsRemoved += jdbc.queryForObject("SELECT COUNT(*) FROM notifications x JOIN reminders r ON r.id=x.reminder_id "
                        + "JOIN notes n ON n.id=r.note_id WHERE n.user_id=?", Long.class, id);
                trashRemoved += jdbc.update("DELETE FROM note_trash WHERE user_id=?", id);
                notesRemoved += jdbc.update("DELETE FROM notes WHERE user_id=?", id);
                assertThat(jdbc.update("DELETE FROM users WHERE id=? AND username=?", id, name)).isEqualTo(1); usersRemoved++;
            }
        }
        System.out.println("MySQL save fixture cleanup: users=" + usersRemoved + ", notes=" + notesRemoved
                + ", reminders=" + remindersRemoved + ", notifications=" + notificationsRemoved + ", trash=" + trashRemoved);
        if (baseline != null) {
            assertThat(snapshot()).as("原学习数据数量、ID 合计和完整行指纹（并发学习写入也可能导致差异）").isEqualTo(baseline);
            System.out.println("MySQL save learning-data baseline: unchanged");
        }
    }
}
