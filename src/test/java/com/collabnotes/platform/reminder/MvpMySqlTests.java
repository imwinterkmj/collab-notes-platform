package com.collabnotes.platform.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.collabnotes.platform.support.HttpSessionTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅显式开启时运行；不建表，真实调度器只处理本次创建的临时数据。 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_MVP_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${MYSQL_TEST_URL:jdbc:mysql://localhost:3306/collab_notes?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}",
        "spring.datasource.username=${MYSQL_TEST_USER:collab_notes}",
        "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.sql.init.mode=never",
        "app.reminders.scheduler-enabled=true", "app.reminders.poll-delay-ms=250", "app.reminders.batch-size=5"
})
class MvpMySqlTests {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    private final List<String> cleanupNames = new ArrayList<>();
    @Test
    void browserApiLifecycleAndRealSchedulerPersistReadCancelAndCascade() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("collab_notes");
        // 在已有学习提醒存在时不允许这个验收以快速轮询处理它们。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders", Long.class)).isZero();
        var owner = new HttpSessionTestClient(port, mapper);
        var other = new HttpSessionTestClient(port, mapper);
        account(owner); account(other);
        assertThat(owner.get("/").getStatusCode()).isEqualTo(HttpStatus.OK);
        var note = create(owner, "MVP 中文 🔔 <script>test</script>");
        var keepOwner = create(owner, "keep-owner");
        var keepOther = create(other, "keep-other");
        long id = note.get("id").asLong();
        String reminder = "/api/notes/" + id + "/reminder";
        assertThat(owner.put(reminder, Map.of("dueAt", Instant.now().plusSeconds(120).toString())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var due = owner.put(reminder, Map.of("dueAt", Instant.now().plusSeconds(2).toString()));
        assertThat(due.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(due.getBody()).get("generation").asLong()).isEqualTo(2);
        assertThat(other.get(reminder).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.delete("/api/notes/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var fired = waitForOneNotification(owner);
        assertThat(fired.get("title")).isEqualTo(note.get("title"));
        assertThat(fired.has("content")).isFalse();
        assertThat(fired.get("read").asBoolean()).isFalse();
        assertThat(Instant.parse(fired.get("createdAt").asText())).isAfterOrEqualTo(Instant.parse(fired.get("dueAt").asText()));
        assertThat(mapper.readTree(owner.get(reminder).getBody()).get("status").asText()).isEqualTo("FIRED");
        long notificationId = fired.get("id").asLong();
        assertThat(mapper.readTree(other.get("/api/notifications").getBody()).get("items").size()).isZero();
        assertThat(other.patch("/api/notifications/" + notificationId + "/read", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (int i = 0; i < 2; i++) {
            assertThat(owner.patch("/api/notifications/" + notificationId + "/read", null).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }
        assertThat(mapper.readTree(owner.get("/api/notifications").getBody()).get("items").get(0).get("read").asBoolean()).isTrue();
        assertThat(owner.delete(reminder).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // 数据库唯一约束是真实 MySQL 的证据，不依赖应用先查是否存在。
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notifications "
                + "(reminder_id,generation,title,due_at,created_at,is_read) "
                + "SELECT reminder_id,generation,title,due_at,created_at,is_read FROM notifications WHERE id = ?", notificationId))
                .isInstanceOf(DataIntegrityViolationException.class);
        long cancelId = keepOwner.get("id").asLong();
        String cancelPath = "/api/notes/" + cancelId + "/reminder";
        assertThat(owner.put(cancelPath, Map.of("dueAt", Instant.now().plusSeconds(120).toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(owner.patch("/api/notes/" + cancelId + "/completion", Map.of("completed", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(owner.get(cancelPath).getBody()).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(owner.put(cancelPath, Map.of("dueAt", Instant.now().plusSeconds(120).toString())).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(owner.patch("/api/notes/" + cancelId + "/completion", Map.of("completed", false)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(owner.get(cancelPath).getBody()).get("status").asText()).isEqualTo("CANCELLED");
        Thread.sleep(750);
        assertThat(mapper.readTree(owner.get("/api/notifications").getBody()).get("items").size()).isEqualTo(1);
        assertThat(owner.delete("/api/notes/" + id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(owner.get("/api/notes/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owner.delete("/api/notes/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mapper.readTree(owner.get("/api/notifications").getBody()).get("items").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE note_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id = ?", Long.class, notificationId)).isZero();
        assertThat(mapper.readTree(owner.get("/api/notes/" + cancelId).getBody()).get("title")).isEqualTo(keepOwner.get("title"));
        assertThat(mapper.readTree(other.get("/api/notes/" + keepOther.get("id").asLong()).getBody())).isEqualTo(keepOther);
        assertThat(owner.post("/api/auth/logout", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(owner.get("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    private JsonNode waitForOneNotification(HttpSessionTestClient client) throws Exception {
        for (int attempt = 0; attempt < 75; attempt++) {
            var response = client.get("/api/notifications");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var items = mapper.readTree(response.getBody()).get("items");
            if (items.size() > 0) { assertThat(items.size()).isEqualTo(1); return items.get(0); }
            Thread.sleep(200);
        }
        throw new AssertionError("真实调度器 15 秒内未生成站内通知");
    }
    private JsonNode create(HttpSessionTestClient client, String title) throws Exception {
        var result = client.post("/api/notes", Map.of("title", title, "content", "MVP 私人正文"));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(result.getBody());
    }
    private void account(HttpSessionTestClient client) throws Exception {
        String name = "mvpsql_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, name)).isZero();
        cleanupNames.add(name);
        var payload = Map.of("username", name, "password", "mvp-mysql-test-passphrase");
        assertThat(client.post("/api/users/register", payload).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(client.post("/api/auth/login", payload).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @AfterEach
    void cleanOnlyThisTestsData() {
        int notesRemoved = 0, usersRemoved = 0;
        for (String name : cleanupNames) {
            for (long id : jdbc.queryForList("SELECT id FROM users WHERE username = ?", Long.class, name)) {
                notesRemoved += jdbc.update("DELETE FROM notes WHERE user_id = ?", id);
                usersRemoved += jdbc.update("DELETE FROM users WHERE id = ? AND username = ?", id, name);
            }
        }
        System.out.println("MySQL MVP fixture cleanup: notes=" + notesRemoved + ", users=" + usersRemoved);
    }
}
