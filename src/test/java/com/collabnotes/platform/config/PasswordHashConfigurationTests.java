package com.collabnotes.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordHashConfigurationTests {

    // 仅启动密码配置，不连接数据库，也不启动 HTTP 服务。
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PasswordHashConfiguration.class);

    @Test
    void encodesWithArgon2idAndVerifiesTheCorrectPassword() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PasswordEncoder.class);
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            String rawPassword = "test-only-passphrase";

            String encoded = encoder.encode(rawPassword);

            assertThat(encoded).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
            assertThat(encoded).doesNotContain(rawPassword);
            assertThat(encoded.length()).isLessThanOrEqualTo(255);
            assertThat(encoder.matches(rawPassword, encoded)).isTrue();
        });
    }

    @Test
    void rejectsAnIncorrectPassword() {
        contextRunner.run(context -> {
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            String encoded = encoder.encode("test-only-passphrase");

            assertThat(encoder.matches("wrong-test-passphrase", encoded)).isFalse();
        });
    }

    @Test
    void generatesDifferentSaltsForTheSamePassword() {
        contextRunner.run(context -> {
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            String rawPassword = "test-only-passphrase";

            String first = encoder.encode(rawPassword);
            String second = encoder.encode(rawPassword);

            assertThat(first).isNotEqualTo(second);
            // Argon2 编码格式中的第 5 段为盐；不只是比较完整哈希字符串。
            assertThat(first.split("\\$")[4]).isNotEqualTo(second.split("\\$")[4]);
            assertThat(encoder.matches(rawPassword, first)).isTrue();
            assertThat(encoder.matches(rawPassword, second)).isTrue();
        });
    }

    @Test
    void preservesWhitespaceCaseAndUnicodeCharacters() {
        contextRunner.run(context -> {
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            String rawPassword = " 备忘录-Passphrase-🔔 ";
            String encoded = encoder.encode(rawPassword);

            assertThat(encoder.matches(rawPassword, encoded)).isTrue();
            assertThat(encoder.matches(rawPassword.strip(), encoded)).isFalse();
            assertThat(encoder.matches(" 备忘录-passphrase-🔔 ", encoded)).isFalse();
        });
    }

    @Test
    void acceptsStrongerConfiguredParameters() {
        contextRunner.withPropertyValues(
                "app.security.password-hash.memory-kib=32768",
                "app.security.password-hash.iterations=3"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            String rawPassword = "test-only-passphrase";
            String encoded = encoder.encode(rawPassword);

            assertThat(encoded).startsWith("$argon2id$v=19$m=32768,t=3,p=1$");
            assertThat(encoder.matches(rawPassword, encoded)).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "app.security.password-hash.memory-kib=0",
            "app.security.password-hash.memory-kib=19455",
            "app.security.password-hash.iterations=0",
            "app.security.password-hash.iterations=1"
    })
    void failsStartupWhenParametersFallBelowTheSecurityBaseline(String property) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("must be at least");
        });
    }
}
