package com.collabnotes.platform.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.collabnotes.platform.support.CsrfTestSupport;
import com.collabnotes.platform.config.SecurityConfiguration;
import com.collabnotes.platform.config.PasswordHashConfiguration;
import com.collabnotes.platform.auth.AuthController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({UserRegistrationController.class, AuthController.class})
@Import({SecurityConfiguration.class, PasswordHashConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class UserRegistrationErrorTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private UserRegistrationService registrationService;

    private MockHttpServletRequestBuilder post(String path) throws Exception {
        return CsrfTestSupport.withCsrf(mockMvc, objectMapper, MockMvcRequestBuilders.post(path), null);
    }

    @Test
    void databaseFailureIsNotAConflictAndDoesNotLeakDetails(CapturedOutput output) throws Exception {
        when(registrationService.register(any())).thenThrow(
                new DataAccessResourceFailureException("SQL secret-test-password $argon2id$ SELECT users"));
        String response = mockMvc.perform(post("/api/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"test_user\",\"password\":\"test-only-passphrase\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("SQL", "secret-test-password", "$argon2id$", "SELECT", "Exception");
        assertThat(output.getAll()).doesNotContain("secret-test-password", "$argon2id$", "SELECT users");
    }

    @Test
    void malformedJsonDoesNotEchoItsPassword() throws Exception {
        String response = mockMvc.perform(post("/api/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"secret-test-password\", invalid}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("secret-test-password", "Exception");
    }

    @Test
    void unsupportedMethodKeepsIts405Status() throws Exception {
        mockMvc.perform(get("/api/users/register")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unsupportedContentTypeKeepsIts415Status() throws Exception {
        mockMvc.perform(post("/api/users/register").contentType(MediaType.TEXT_PLAIN)
                .content("secret-test-password")).andExpect(status().isUnsupportedMediaType());
    }
}
