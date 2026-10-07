package com.collabnotes.platform.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
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

@WebMvcTest({ReminderController.class, NotificationController.class, AuthController.class})
@Import({SecurityConfiguration.class, PasswordHashConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class MvpErrorTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PasswordEncoder encoder;
    @MockitoBean private UserDetailsService users;
    @MockitoBean private ReminderService service;
    @MockitoBean private NotificationService notifications;
    private MockHttpSession session;
    @BeforeEach
    void login() throws Exception {
        when(users.loadUserByUsername("test_user")).thenAnswer(invocation ->
                new AuthenticatedUser(new UserCredentials(42, "test_user", encoder.encode("test-only-passphrase"))));
        session = (MockHttpSession) mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"test_user\",\"password\":\"test-only-passphrase\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    @Test
    void databaseSetFailureIsPrivate500(CapturedOutput output) throws Exception {
        when(service.set(anyLong(), anyLong(), any())).thenThrow(failure());
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, put("/api/notes/1/reminder"), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"dueAt\":\"2030-01-01T00:00:00Z\"}"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertSafe(result.getResponse().getContentAsString(), output);
    }
    @Test
    void notificationReadFailureIsPrivate500(CapturedOutput output) throws Exception {
        doThrow(failure()).when(notifications).markRead(anyLong(), anyLong());
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, patch("/api/notifications/1/read"), session))
                .andExpect(status().isInternalServerError()).andReturn();
        assertSafe(result.getResponse().getContentAsString(), output);
    }
    @Test
    void notificationListFailureIsNotSuccessfulEmptyPage(CapturedOutput output) throws Exception {
        when(notifications.list(anyLong(), anyInt(), anyInt())).thenThrow(failure());
        var result = mvc.perform(get("/api/notifications").session(session))
                .andExpect(status().isInternalServerError()).andReturn();
        assertSafe(result.getResponse().getContentAsString(), output);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("items");
    }
    @Test
    void unreadListFailureDoesNotPretendRemindersHaveDisappeared(CapturedOutput output) throws Exception {
        when(notifications.listUnread(anyLong(), anyInt(), anyInt())).thenThrow(failure());
        var result = mvc.perform(get("/api/notifications?unreadOnly=true").session(session))
                .andExpect(status().isInternalServerError()).andReturn();
        assertSafe(result.getResponse().getContentAsString(), output);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("items");
    }
    @Test
    void invalidInputNeverEchoesOrLogsRawTime(CapturedOutput output) throws Exception {
        var result = mvc.perform(CsrfTestSupport.withCsrf(mvc, mapper, put("/api/notes/1/reminder"), session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"dueAt\":{\"private-time\":true}}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-time");
        assertThat(output.getAll()).doesNotContain("private-time");
    }
    private DataAccessResourceFailureException failure() {
        return new DataAccessResourceFailureException("SQL private-reminder secret-test-password");
    }
    private void assertSafe(String response, CapturedOutput output) {
        assertThat(response).contains("INTERNAL_ERROR").doesNotContain("SQL", "private-reminder", "secret-test-password");
        assertThat(output.getAll()).doesNotContain("SQL private-reminder", "secret-test-password");
    }
}
