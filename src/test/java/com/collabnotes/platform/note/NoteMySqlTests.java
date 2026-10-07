package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.collabnotes.platform.support.HttpSessionTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅在明确开启时连接已有的真实 MySQL，不自动建表，只清理本次随机测试账号及其记录。 */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_NOTES_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${MYSQL_TEST_URL:jdbc:mysql://localhost:3306/collab_notes?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}",
        "spring.datasource.username=${MYSQL_TEST_USER:collab_notes}",
        "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.sql.init.mode=never"
})
class NoteMySqlTests {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    private final List<String> cleanupNames = new ArrayList<>();

    @Test
    void realMysqlValidatesPersistenceOwnershipUnicodeAndConstraints() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("collab_notes");
        var owner = new HttpSessionTestClient(port, mapper);
        var other = new HttpSessionTestClient(port, mapper);
        long ownerId = account(owner);
        account(other);
        String title = " 面试-🔔-' OR 1=1 -- ";
        String content = " 测试私人正文\n保留空格及 emoji 🔔 ";
        Instant before = Instant.now().minusSeconds(1);
        var created = owner.post("/api/notes", Map.of("title", title, "content", content));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = mapper.readTree(created.getBody());
        assertThat(body.size()).isEqualTo(6);
        assertThat(body.get("title").asText()).isEqualTo(title);
        assertThat(body.get("content").asText()).isEqualTo(content);
        assertThat(body.get("completed").asBoolean()).isFalse();
        assertThat(body.get("updatedAt")).isEqualTo(body.get("createdAt"));
        Instant createdAt = Instant.parse(body.get("createdAt").asText());
        assertThat(createdAt).isBetween(before, Instant.now());
        long noteId = body.get("id").asLong();
        assertThat(noteId).isPositive();
        jdbc.query("SELECT user_id, title, content, is_completed, created_at, updated_at FROM notes WHERE id = ?", rs -> {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("user_id")).isEqualTo(ownerId);
            assertThat(rs.getString("title")).isEqualTo(title);
            assertThat(rs.getString("content")).isEqualTo(content);
            assertThat(rs.getBoolean("is_completed")).isFalse();
            assertThat(rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(createdAt);
            assertThat(rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(createdAt);
            return null;
        }, noteId);
        var detail = owner.get("/api/notes/" + noteId);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(detail.getBody())).isEqualTo(body);
        var denied = other.get("/api/notes/" + noteId + "?userId=" + ownerId);
        var missing = other.get("/api/notes/" + Long.MAX_VALUE);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(denied.getBody()).isEqualTo(missing.getBody()).doesNotContain(title, content);
        assertThat(new HttpSessionTestClient(port, mapper).get("/api/notes/" + noteId).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(owner.post("/api/notes", Map.of("title", "test", "content", "test", "userId", ownerId))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(owner.post("/api/notes", Map.of("title", "🔔".repeat(121), "content", "test"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(owner.post("/api/notes", Map.of("title", "test", "content", "🔔".repeat(10001)))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var unicode = owner.post("/api/notes", Map.of("title", "🔔".repeat(120), "content", "🔔".repeat(10000)));
        assertThat(unicode.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long unicodeId = mapper.readTree(unicode.getBody()).get("id").asLong();
        var lengths = jdbc.queryForMap("SELECT CHAR_LENGTH(title) AS tl, CHAR_LENGTH(content) AS cl FROM notes WHERE id = ?", unicodeId);
        assertThat(((Number) lengths.get("tl")).intValue()).isEqualTo(120);
        assertThat(((Number) lengths.get("cl")).intValue()).isEqualTo(10000);
        var repeated = owner.post("/api/notes", Map.of("title", title, "content", ""));
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long repeatedId = mapper.readTree(repeated.getBody()).get("id").asLong();
        long beforeConstraints = count(ownerId);
        assertThat(beforeConstraints).isEqualTo(3);

        // 仅改变本次测试账号的时间，验证 ID 较小但更新较新的记录排在最前。
        var tieTime = LocalDateTime.ofInstant(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                ZoneOffset.UTC);
        jdbc.update("UPDATE notes SET updated_at = ? WHERE user_id = ?", tieTime, ownerId);
        jdbc.update("UPDATE notes SET updated_at = ? WHERE id = ? AND user_id = ?",
                tieTime.plusSeconds(1), noteId, ownerId);
        var listed = owner.get("/api/notes?page=0&size=2");
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        var firstPage = mapper.readTree(listed.getBody());
        assertThat(firstPage.size()).isEqualTo(4);
        assertThat(firstPage.get("items").size()).isEqualTo(2);
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
        assertThat(firstPage.get("items").get(0).get("id").asLong()).isEqualTo(noteId);
        assertThat(firstPage.get("items").get(1).get("id").asLong()).isEqualTo(repeatedId);
        assertThat(firstPage.get("items").get(0).size()).isEqualTo(5);
        assertThat(firstPage.get("items").get(0).has("content")).isFalse();
        assertThat(firstPage.get("items").get(0).has("userId")).isFalse();
        assertThat(firstPage.get("items").get(0).get("title").asText()).isEqualTo(title);
        assertThat(Instant.parse(firstPage.get("items").get(0).get("updatedAt").asText()))
                .isEqualTo(tieTime.plusSeconds(1).toInstant(ZoneOffset.UTC));
        var secondPage = mapper.readTree(owner.get("/api/notes?page=1&size=2").getBody());
        assertThat(secondPage.get("items").size()).isEqualTo(1);
        assertThat(secondPage.get("items").get(0).get("id").asLong()).isEqualTo(unicodeId);
        assertThat(secondPage.get("hasNext").asBoolean()).isFalse();
        assertThat(mapper.readTree(owner.get("/api/notes?page=2&size=2").getBody()).get("items").size()).isZero();
        assertThat(mapper.readTree(other.get("/api/notes?userId=" + ownerId).getBody()).get("items").size()).isZero();
        assertThat(new HttpSessionTestClient(port, mapper).get("/api/notes").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(owner.get("/api/notes?page=-1&size=2").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(owner.get("/api/notes?page=0&size=101").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertCheckViolation(() -> insertDirect(ownerId, "", "test", 0), "ck_notes_title_length");
        assertCheckViolation(() -> insertDirect(ownerId, "test", "x".repeat(10001), 0), "ck_notes_content_length");
        assertCheckViolation(() -> insertDirect(ownerId, "test", "test", 2), "ck_notes_completed");
        assertThatThrownBy(() -> insertDirect(-1, "test", "test", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", ownerId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(ownerId)).isEqualTo(beforeConstraints);
    }

    @Test
    void realMysqlUpdatesOnlyOwnedNoteAndPreservesImmutableFields() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("collab_notes");
        var owner = new HttpSessionTestClient(port, mapper);
        var other = new HttpSessionTestClient(port, mapper);
        long ownerId = account(owner);
        long otherId = account(other);
        var original = createNote(owner, "old-title", "old-content");
        long id = original.get("id").asLong();
        var ownerUntouched = createNote(owner, "keep-owner", "keep-owner-content");
        var otherUntouched = createNote(other, "keep-other", "keep-other-content");
        jdbc.update("UPDATE notes SET is_completed = 1 WHERE id = ? AND user_id = ?", id, ownerId);
        String title = " 修改后 🔔 ' OR 1=1 -- ";
        String content = " 新正文\n保留空格和 emoji 🔔 ";
        Instant before = Instant.now().minusSeconds(1);
        var response = owner.put("/api/notes/" + id, Map.of("title", title, "content", content));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var updated = mapper.readTree(response.getBody());
        assertThat(updated.size()).isEqualTo(6);
        assertThat(updated.get("id").asLong()).isEqualTo(id);
        assertThat(updated.get("title").asText()).isEqualTo(title);
        assertThat(updated.get("content").asText()).isEqualTo(content);
        assertThat(updated.get("completed").asBoolean()).isTrue();
        assertThat(updated.get("createdAt")).isEqualTo(original.get("createdAt"));
        Instant updatedAt = Instant.parse(updated.get("updatedAt").asText());
        assertThat(updatedAt).isBetween(before, Instant.now());
        jdbc.query("SELECT user_id, updated_at FROM notes WHERE id = ?", rs -> {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("user_id")).isEqualTo(ownerId);
            assertThat(rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)).isEqualTo(updatedAt);
            return null;
        }, id);
        assertThat(mapper.readTree(owner.get("/api/notes/" + id).getBody())).isEqualTo(updated);
        var first = mapper.readTree(owner.get("/api/notes?page=1&size=1").getBody()).get("items").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(id);
        assertThat(first.get("title").asText()).isEqualTo(title);
        assertThat(first.has("content")).isFalse();

        var denied = other.put("/api/notes/" + id + "?userId=" + ownerId,
                Map.of("title", "attack-title", "content", "attack-content"));
        var absent = other.put("/api/notes/" + Long.MAX_VALUE, Map.of("title", "attack-title", "content", "attack-content"));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(absent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(denied.getBody()).isEqualTo(absent.getBody()).doesNotContain(title, content);
        assertThat(owner.put("/api/notes/" + id, Map.of("title", "test", "content", "test", "userId", otherId))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(owner.put("/api/notes/" + id, Map.of("title", "🔔".repeat(121), "content", "test"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new HttpSessionTestClient(port, mapper).put("/api/notes/" + id,
                Map.of("title", "test", "content", "test")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mapper.readTree(owner.get("/api/notes/" + id).getBody())).isEqualTo(updated);

        assertThat(owner.put("/api/notes/" + id,
                Map.of("title", "🔔".repeat(120), "content", "🔔".repeat(10000))).getStatusCode()).isEqualTo(HttpStatus.OK);
        var lengths = jdbc.queryForMap("SELECT CHAR_LENGTH(title) AS tl, CHAR_LENGTH(content) AS cl FROM notes WHERE id = ?", id);
        assertThat(((Number) lengths.get("tl")).intValue()).isEqualTo(120);
        assertThat(((Number) lengths.get("cl")).intValue()).isEqualTo(10000);
        for (int i = 0; i < 2; i++) {
            assertThat(owner.put("/api/notes/" + id, Map.of("title", "repeat-title", "content", ""))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        var repeated = mapper.readTree(owner.get("/api/notes/" + id).getBody());
        assertThat(repeated.get("content").asText()).isEmpty();
        assertThat(repeated.get("createdAt")).isEqualTo(original.get("createdAt"));
        assertThat(repeated.get("completed").asBoolean()).isTrue();
        assertThat(count(ownerId)).isEqualTo(2);
        assertThat(count(otherId)).isEqualTo(1);
        assertThat(mapper.readTree(owner.get("/api/notes/" + ownerUntouched.get("id").asLong()).getBody()))
                .isEqualTo(ownerUntouched);
        assertThat(mapper.readTree(other.get("/api/notes/" + otherUntouched.get("id").asLong()).getBody()))
                .isEqualTo(otherUntouched);
    }

    @Test
    void realMysqlSetsCompletionExplicitlyAndKeepsTimeForSameTarget() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("collab_notes");
        var owner = new HttpSessionTestClient(port, mapper);
        var other = new HttpSessionTestClient(port, mapper);
        long ownerId = account(owner);
        long otherId = account(other);
        var original = createNote(owner, " 完成测试 🔔 ", " 私人正文\n保留空格 🔔 ");
        long id = original.get("id").asLong();
        String path = "/api/notes/" + id + "/completion";
        var ownerUntouched = createNote(owner, "keep-owner", "keep-owner-content");
        var otherUntouched = createNote(other, "keep-other", "keep-other-content");

        // 初始状态已是 false；不能因重复设置而修改时间或错误地返回 404。
        var unchanged = owner.patch(path, Map.of("completed", false));
        assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(unchanged.getBody())).isEqualTo(original);
        Instant before = Instant.now().minusSeconds(1);
        var response = owner.patch(path, Map.of("completed", true));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var completed = mapper.readTree(response.getBody());
        assertThat(completed.size()).isEqualTo(6);
        assertThat(completed.get("id").asLong()).isEqualTo(id);
        assertThat(completed.get("completed").asBoolean()).isTrue();
        assertThat(completed.get("title")).isEqualTo(original.get("title"));
        assertThat(completed.get("content")).isEqualTo(original.get("content"));
        assertThat(completed.get("createdAt")).isEqualTo(original.get("createdAt"));
        Instant updatedAt = Instant.parse(completed.get("updatedAt").asText());
        assertThat(updatedAt).isBetween(before, Instant.now());
        assertThat(updatedAt).isAfter(Instant.parse(original.get("updatedAt").asText()));
        assertThat(updatedAt.getNano() % 1000).isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM notes WHERE id = ?", Long.class, id)).isEqualTo(ownerId);
        assertThat(jdbc.queryForObject("SELECT is_completed FROM notes WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM notes WHERE id = ?", LocalDateTime.class, id)
                .toInstant(ZoneOffset.UTC)).isEqualTo(updatedAt);
        assertThat(mapper.readTree(owner.patch(path, Map.of("completed", true)).getBody())).isEqualTo(completed);
        assertThat(mapper.readTree(owner.get("/api/notes/" + id).getBody())).isEqualTo(completed);
        var first = mapper.readTree(owner.get("/api/notes?page=1&size=1").getBody()).get("items").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(id);
        assertThat(first.get("completed").asBoolean()).isTrue();
        assertThat(first.get("updatedAt")).isEqualTo(completed.get("updatedAt"));
        assertThat(first.has("content")).isFalse();

        var reopenedResponse = owner.patch(path, Map.of("completed", false));
        assertThat(reopenedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        var reopened = mapper.readTree(reopenedResponse.getBody());
        assertThat(reopened.get("completed").asBoolean()).isFalse();
        assertThat(Instant.parse(reopened.get("updatedAt").asText())).isAfter(updatedAt);
        assertThat(reopened.get("title")).isEqualTo(original.get("title"));
        assertThat(reopened.get("content")).isEqualTo(original.get("content"));
        assertThat(reopened.get("createdAt")).isEqualTo(original.get("createdAt"));
        assertThat(mapper.readTree(owner.patch(path, Map.of("completed", false)).getBody())).isEqualTo(reopened);
        var denied = other.patch(path + "?userId=" + ownerId, Map.of("completed", true));
        var missing = other.patch("/api/notes/" + Long.MAX_VALUE + "/completion", Map.of("completed", true));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(denied.getBody()).isEqualTo(missing.getBody()).doesNotContain("私人正文");
        assertThat(owner.patch(path, Map.of("completed", true, "userId", otherId)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(owner.patch(path, Map.of("completed", "true")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(owner.patch(path, Map.of("completed", 1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new HttpSessionTestClient(port, mapper).patch(path, Map.of("completed", true)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mapper.readTree(owner.get("/api/notes/" + id).getBody())).isEqualTo(reopened);
        assertThat(count(ownerId)).isEqualTo(2);
        assertThat(count(otherId)).isEqualTo(1);
        assertThat(mapper.readTree(owner.get("/api/notes/" + ownerUntouched.get("id").asLong()).getBody()))
                .isEqualTo(ownerUntouched);
        assertThat(mapper.readTree(other.get("/api/notes/" + otherUntouched.get("id").asLong()).getBody()))
                .isEqualTo(otherUntouched);
    }

    private com.fasterxml.jackson.databind.JsonNode createNote(HttpSessionTestClient client, String title, String content)
            throws Exception {
        var result = client.post("/api/notes", Map.of("title", title, "content", content));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(result.getBody());
    }

    private long account(HttpSessionTestClient client) throws Exception {
        String username = "nverify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, username)).isZero();
        // 确认该随机名称此前不存在，才允许结束后清理，覆盖提交成功但响应失败的情况。
        cleanupNames.add(username);
        var credentials = Map.of("username", username, "password", "mysql-notes-passphrase");
        var registered = client.post("/api/users/register", credentials);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = mapper.readTree(registered.getBody()).get("id").asLong();
        assertThat(client.post("/api/auth/login", credentials).getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private long count(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE user_id = ?", Long.class, userId);
    }
    private void insertDirect(long userId, String title, String content, int completed) {
        var utc = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        jdbc.update("INSERT INTO notes (user_id, title, content, is_completed, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, title, content, completed, utc, utc);
    }

    private void assertCheckViolation(ThrowingCallable operation, String constraint) {
        // 当前驱动将 CHECK 报为 HY000/3819，Spring 不一定分类成 DataIntegrityViolationException。
        // 直接核对底层数据库证据，不能把“任何异常”都当成约束生效。
        assertThatThrownBy(operation).isInstanceOf(DataAccessException.class).satisfies(exception -> {
            Throwable root = ((DataAccessException) exception).getMostSpecificCause();
            assertThat(root).isInstanceOf(SQLException.class);
            var sql = (SQLException) root;
            assertThat(sql.getErrorCode()).isEqualTo(3819);
            assertThat(sql.getSQLState()).isEqualTo("HY000");
            assertThat(sql.getMessage()).contains(constraint);
        });
    }

    @AfterEach
    void cleanupOnlyThisTestsData() {
        int notesRemoved = 0;
        int usersRemoved = 0;
        for (String username : cleanupNames) {
            for (long id : jdbc.queryForList("SELECT id FROM users WHERE username = ?", Long.class, username)) {
                notesRemoved += jdbc.update("DELETE FROM notes WHERE user_id = ?", id);
                usersRemoved += jdbc.update("DELETE FROM users WHERE id = ? AND username = ?", id, username);
            }
        }
        System.out.println("MySQL notes verification cleanup: notes=" + notesRemoved + ", users=" + usersRemoved);
    }
}
