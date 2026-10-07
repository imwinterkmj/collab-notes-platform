package com.collabnotes.platform.config;

import java.util.List;

import com.collabnotes.platform.auth.AuthenticatedUser;
import com.collabnotes.platform.auth.CurrentUserResponse;
import com.collabnotes.platform.auth.JsonLoginFilter;
import com.collabnotes.platform.auth.SecurityResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager manager,
                                          ObjectMapper mapper, Validator validator) throws Exception {
        var csrfRepository = new HttpSessionCsrfTokenRepository();
        var csrfHandler = new XorCsrfTokenRequestAttributeHandler();
        var contextRepository = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
        var csrfStrategy = new CsrfAuthenticationStrategy(csrfRepository);
        csrfStrategy.setRequestHandler(csrfHandler);
        var login = new JsonLoginFilter(manager, mapper, validator);
        login.setSecurityContextRepository(contextRepository);
        login.setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), csrfStrategy)));
        login.setAuthenticationSuccessHandler((request, response, authentication) ->
                SecurityResponses.write(mapper, response, 200,
                        CurrentUserResponse.from((AuthenticatedUser) authentication.getPrincipal())));
        login.setAuthenticationFailureHandler((request, response, exception) -> {
            if (exception instanceof JsonLoginFilter.InvalidLoginRequestException invalid) {
                SecurityResponses.error(mapper, response, invalid.getStatus(), "INVALID_REQUEST", "登录请求格式无效");
            } else if (exception instanceof AuthenticationServiceException) {
                LOG.error("登录服务异常，类型：{}", exception.getClass().getSimpleName());
                SecurityResponses.error(mapper, response, 500, "INTERNAL_ERROR", "服务暂时不可用");
            } else {
                // 不区分用户名不存在与密码错误，避免通过响应枚举账号。
                SecurityResponses.error(mapper, response, 401, "INVALID_CREDENTIALS", "用户名或密码错误");
            }
        });

        http.csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(csrfHandler))
                .headers(headers -> headers.contentSecurityPolicy(policy -> policy
                        .policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; "
                                + "connect-src 'self'; img-src 'self' blob:; media-src 'self' blob:; object-src 'none'; base-uri 'self'; "
                                + "frame-ancestors 'none'; form-action 'self'")))
                .securityContext(context -> context.securityContextRepository(contextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/reminder-preferences.js", "/reminder-assets.js", "/reminder-alerts.js", "/styles.css").permitAll()
                        .requestMatchers("/api/users/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                SecurityResponses.error(mapper, response, 401, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                SecurityResponses.error(mapper, response, 403,
                                        exception instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                        exception instanceof CsrfException ? "请重新获取 CSRF 校验码" : "没有访问权限")))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterAt(login, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
