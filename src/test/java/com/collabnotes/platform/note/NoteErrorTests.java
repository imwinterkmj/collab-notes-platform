package com.collabnotes.platform.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.collabnotes.platform.auth.AuthController;
import com.collabnotes.platform.auth.AuthenticatedUser;
import com.collabnotes.platform.config.PasswordHashConfiguration;
import com.collabnotes.platform.config.SecurityConfiguration;
import com.collabnotes.platform.support.CsrfTestSupport;
import com.collabnotes.platform.user.UserCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({NoteController.class, AuthController.class})
@Import({SecurityConfiguration.class, PasswordHashConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class NoteErrorTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PasswordEncoder encoder;
    @MockitoBean private UserDetailsService users;
    @MockitoBean private NoteService service;
    private MockHttpSession session;

    @BeforeEach
    void login() throws Exception {
        when(users.loadUserByUsername("test_user")).thenAnswer(invocation ->
                new AuthenticatedUser(new UserCredentials(42, "test_user", encoder.encode("test-only-passphrase"))));
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"test_user\",\"password\":\"test-only-passphrase\"}"))
                .andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void writeFailureIs500AndNeverLeaksPrivateText(CapturedOutput output) throws Exception {
        when(service.create(anyLong(), any())).thenThrow(new DataAccessResourceFailureException(
                "INSERT notes private-title private-content secret-test-password"));
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes"), session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"private-title\",\"content\":\"private-content\"}"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("INTERNAL_ERROR")
                .doesNotContain("private-title", "private-content", "INSERT", "Exception", "secret-test-password");
        assertThat(output.getAll()).doesNotContain("private-title", "private-content", "secret-test-password");
    }

    @Test
    void readFailureIs500Not404(CapturedOutput output) throws Exception {
        when(service.detail(anyLong(), anyLong())).thenThrow(new DataAccessResourceFailureException("SQL private-content"));
        var result = mvc.perform(get("/api/notes/1").session(session))
                .andExpect(status().isInternalServerError()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SQL", "private-content");
        assertThat(output.getAll()).doesNotContain("SQL private-content");
    }

    @Test
    void validationFailureDoesNotLogOrEchoPrivateContents(CapturedOutput output) throws Exception {
        String input = mapper.writeValueAsString(java.util.Map.of(
                "title", "private-title".repeat(12), "content", "private-content"));
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/notes"), session)
                .contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-title", "private-content");
        assertThat(output.getAll()).doesNotContain("private-title", "private-content");
    }
}
