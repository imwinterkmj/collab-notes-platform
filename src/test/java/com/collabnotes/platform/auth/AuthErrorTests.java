package com.collabnotes.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.collabnotes.platform.support.CsrfTestSupport;
import com.collabnotes.platform.config.PasswordHashConfiguration;
import com.collabnotes.platform.config.SecurityConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, PasswordHashConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthErrorTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private UserDetailsService users;

    @Test
    void databaseFailureIs500WithoutLeakingSecrets(CapturedOutput output) throws Exception {
        when(users.loadUserByUsername("test_user")).thenThrow(new DataAccessResourceFailureException(
                "SELECT users secret-test-password $argon2id$"));
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"test_user\",\"password\":\"test-only-passphrase\"}"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("INTERNAL_ERROR")
                .doesNotContain("SELECT", "secret-test-password", "$argon2id$", "Exception");
        assertThat(output.getAll()).doesNotContain("SELECT users", "secret-test-password", "$argon2id$");
    }
}
