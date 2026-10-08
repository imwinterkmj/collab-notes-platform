package com.collabnotes.platform.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 Flutter IO 客户端 → HTTPS → 既有 Spring 安全/控制器 → 临时 H2；不访问 MySQL。 */
@EnabledIfEnvironmentVariable(named = "RUN_CLIENT_HTTP_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.ssl.enabled=true", "server.ssl.key-store-type=PKCS12",
        "server.ssl.key-alias=collabnotes-dev", "server.servlet.session.cookie.secure=true",
        "spring.datasource.url=jdbc:h2:mem:client_https_contract;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql,classpath:db/notes-test-schema.sql",
        "app.reminders.scheduler-enabled=false", "debug=false"
})
class ClientHttpContractTests {
    @LocalServerPort private int port;
    private static JsonNode localConfig() {
        try {
            return new ObjectMapper().readTree(Files.readString(Path.of("target/local-https/server-local.json")));
        } catch (Exception exception) {
            throw new IllegalStateException("请先准备已获准的本机 HTTPS 材料；不自动生成或输出秘密");
        }
    }
    @DynamicPropertySource
    static void tls(DynamicPropertyRegistry registry) {
        var config = localConfig();
        registry.add("server.ssl.key-store", () -> config.path("keyStore").asText());
        registry.add("server.ssl.key-store-password", () -> config.path("password").asText());
    }
    @Test
    void dartClientUsesExistingApiOverVerifiedHttps() throws Exception {
        String flutter = System.getenv("FLUTTER_SDK");
        assertThat(flutter).as("本机 Flutter SDK 必须明确指定；不下载工具").isNotBlank();
        var report = Path.of("target/client-contract-output.log").toAbsolutePath();
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", Path.of(flutter, "bin/flutter.bat").toString(),
                "test", "--no-pub", "tool/live_api_contract_test.dart")
                .directory(Path.of("client").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(report.toFile());
        process.environment().put("CLIENT_CONTRACT_URL", "https://127.0.0.1:" + port);
        process.environment().put("CLIENT_CONTRACT_CERT", localConfig().path("certificateFile").asText());
        var running = process.start();
        if (!running.waitFor(180, TimeUnit.SECONDS)) {
            running.descendants().forEach(child -> child.destroyForcibly());
            running.destroyForcibly();
            throw new AssertionError("客户端隔离验收超时，已停止本次子进程");
        }
        assertThat(running.exitValue()).as("客户端 HTTPS 合同验收；只在 target 查看脱敏测试结果").isZero();
        assertThat(Files.readString(report)).contains("+10: All tests passed!");
    }
}
