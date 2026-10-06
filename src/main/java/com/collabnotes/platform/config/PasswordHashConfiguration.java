package com.collabnotes.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.Assert;

/**
 * 提供可注入的密码哈希工具，不负责注册、登录或访问权限。
 */
@Configuration(proxyBeanMethods = false)
public class PasswordHashConfiguration {

    private static final int MIN_MEMORY_KIB = 19 * 1024;
    private static final int MIN_ITERATIONS = 2;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;

    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${app.security.password-hash.memory-kib:19456}") int memoryKib,
            @Value("${app.security.password-hash.iterations:2}") int iterations
    ) {
        // 当前项目选择 OWASP 的 m=19 MiB、t=2、p=1 基线，禁止通过配置降低它。
        Assert.isTrue(memoryKib >= MIN_MEMORY_KIB,
                "app.security.password-hash.memory-kib must be at least 19456");
        Assert.isTrue(iterations >= MIN_ITERATIONS,
                "app.security.password-hash.iterations must be at least 2");

        // 随机盐由库生成；返回的字符串包含算法、参数、盐及哈希，供以后验证密码。
        return new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, 1, memoryKib, iterations
        );
    }
}
