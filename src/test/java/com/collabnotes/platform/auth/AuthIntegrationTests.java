package com.collabnotes.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.collabnotes.platform.support.CsrfTestSupport;
import com.collabnotes.platform.user.UserRegistrationService;
import com.collabnotes.platform.user.RegisterUserRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user-registration-test-schema.sql"
})
@AutoConfigureMockMvc
class AuthIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRegistrationService registration;
    private static final String PASSWORD = " 备忘录-Passphrase-🔔 ";

    @Test
    void anonymousOrForgedCookieCannotReadIdentity() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/auth/me").cookie(new Cookie("JSESSIONID", "forged-session")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRotatesSessionAndStoresOnlyIdentity() throws Exception {
        String username = account();
        var bootstrap = mvc.perform(get("/api/auth/csrf")).andReturn();
        MockHttpSession before = (MockHttpSession) bootstrap.getRequest().getSession(false);
        var loginRequest = csrf(post("/api/auth/login"), before)
                .contentType(MediaType.APPLICATION_JSON).content(json(username, PASSWORD));
        String oldId = before.getId();
        var login = mvc.perform(loginRequest).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username)).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        var body = mapper.readTree(login.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(login.getResponse().getContentAsString()).doesNotContain(PASSWORD, "$argon2id$", "password");
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(((AuthenticatedUser) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(body.get("id").asLong()))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void wrongPasswordAndUnknownUserHaveTheSameResponse() throws Exception {
        String known = failedLogin(account(), "wrong-test-passphrase");
        String unknown = failedLogin(uniqueName(), "wrong-test-passphrase");
        assertThat(known).isEqualTo(unknown).doesNotContain("password_hash", "$argon2id$");
    }

    @Test
    void passwordWhitespaceIsSignificant() throws Exception {
        failedLogin(account(), PASSWORD.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/register", "/api/auth/login", "/api/auth/logout"})
    void unsafeRequestsRequireCsrf(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post(path).header("X-CSRF-TOKEN", "forged").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void preLoginCsrfIsInvalidatedAndLogoutNeedsAFreshToken() throws Exception {
        String username = account();
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andReturn();
        var token = mapper.readTree(csrfResult.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        mvc.perform(post("/api/auth/login").session(session)
                .header(token.get("headerName").asText(), token.get("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content(json(username, PASSWORD))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session)
                .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(csrf(post("/api/auth/logout"), session)).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loggingOutOneDeviceDoesNotLogOutAnotherDevice() throws Exception {
        String username = account();
        MockHttpSession first = login(username);
        MockHttpSession second = login(username);
        assertThat(first.getId()).isNotEqualTo(second.getId());
        mvc.perform(csrf(post("/api/auth/logout"), first)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(second)).andExpect(status().isOk());
    }

    @Test
    void getLogoutDoesNotEndTheSession() throws Exception {
        MockHttpSession session = login(account());
        mvc.perform(get("/api/auth/logout").session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{invalid}",
            "{\"username\":123,\"password\":\"test-only-passphrase\"}",
            "{\"username\":\"test_user\",\"password\":true}",
            "{\"username\":\"test_user\",\"password\":\"short\"}",
            "{\"username\":\"test_user\",\"password\":\"test-only-passphrase\",\"id\":1}",
            "{\"username\":\"test_user\",\"password\":\"test-only-passphrase\"} {}"})
    void invalidLoginRequestsAreNotEchoed(String input) throws Exception {
        var result = mvc.perform(csrf(post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("test-only-passphrase", "Exception", "password_hash");
    }

    @Test
    void loginAcceptsOnlyJsonAndDoesNotExposeRequestPasswords() throws Exception {
        mvc.perform(csrf(post("/api/auth/login"), null).contentType(MediaType.TEXT_PLAIN).content(PASSWORD))
                .andExpect(status().isUnsupportedMediaType());
        LoginRequest request = new LoginRequest("test_user", PASSWORD);
        assertThat(request.toString()).doesNotContain(PASSWORD);
        assertThat(mapper.writeValueAsString(request)).doesNotContain(PASSWORD, "password");
    }

    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(csrf(post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON).content(json(username, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String failedLogin(String username, String password) throws Exception {
        var result = mvc.perform(csrf(post("/api/auth/login"), null)
                .contentType(MediaType.APPLICATION_JSON).content(json(username, password)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        return result.getResponse().getContentAsString();
    }

    private String account() {
        String username = uniqueName();
        registration.register(new RegisterUserRequest(username, PASSWORD));
        return username;
    }
    private String uniqueName() { return "auth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24); }
    private String json(String username, String password) throws Exception {
        return mapper.writeValueAsString(Map.of("username", username, "password", password));
    }
    private MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request, MockHttpSession session)
            throws Exception {
        return CsrfTestSupport.withCsrf(mvc, mapper, request, session);
    }
}
