package com.collabnotes.platform.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserRegistrationService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserRegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public RegisterUserResponse register(RegisterUserRequest request) {
        // 在取得数据库连接前完成耗时的密码计算；单条 INSERT 自身具有原子性。
        String passwordHash = passwordEncoder.encode(request.password());
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long id = userRepository.insert(request.username(), passwordHash, createdAt);
        return new RegisterUserResponse(id, request.username(), createdAt);
    }
}
