package com.collabnotes.platform.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.sql.SQLException;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

@ExtendWith(MockitoExtension.class)
class UserRepositoryTests {
    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void recognizesTheNamedMysqlUsernameConstraint() {
        var error = new DuplicateKeyException("duplicate", new SQLException(
                "Duplicate entry 'test_user' for key 'users.uk_users_username'", "23000", 1062));
        doThrow(error).when(jdbcTemplate).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        assertThatThrownBy(() -> insert()).isInstanceOf(UsernameAlreadyExistsException.class);
    }

    @Test
    void anotherUniqueConstraintIsNotMistakenForUsernameConflict() {
        var error = new DuplicateKeyException("duplicate", new SQLException(
                "Duplicate entry 'uk_users_username' for key 'users.another_unique_key'", "23000", 1062));
        doThrow(error).when(jdbcTemplate).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        assertThatThrownBy(() -> insert()).isSameAs(error);
    }

    @Test
    void primaryKeyConflictIsNotMistakenForUsernameConflict() {
        var error = new DuplicateKeyException("duplicate", new SQLException(
                "Duplicate entry '1' for key 'users.PRIMARY'", "23000", 1062));
        doThrow(error).when(jdbcTemplate).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        assertThatThrownBy(() -> insert()).isSameAs(error);
    }

    @Test
    void databaseConnectionFailureIsNotMistakenForUsernameConflict() {
        var error = new DataAccessResourceFailureException("test connection failure");
        doThrow(error).when(jdbcTemplate).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        assertThatThrownBy(() -> insert()).isSameAs(error);
    }

    private long insert() {
        return new UserRepository(jdbcTemplate).insert("test_user", "$test-only-hash", Instant.now());
    }
}
