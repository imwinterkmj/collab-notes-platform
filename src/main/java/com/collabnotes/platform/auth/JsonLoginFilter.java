package com.collabnotes.platform.auth;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Validator;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.http.HttpMethod;

/** 只负责读取 JSON；密码校验、会话轮换与上下文保存交给 Spring Security。不是独立的 Servlet Bean。 */
public final class JsonLoginFilter extends AbstractAuthenticationProcessingFilter {
    private final ObjectMapper mapper;
    private final Validator validator;

    public JsonLoginFilter(AuthenticationManager manager, ObjectMapper mapper, Validator validator) {
        super(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login"), manager);
        this.mapper = mapper;
        this.validator = validator;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException, IOException {
        try {
            if (request.getContentType() == null || !MediaType.APPLICATION_JSON.isCompatibleWith(
                    MediaType.parseMediaType(request.getContentType()))) {
                throw new InvalidLoginRequestException(415);
            }
            LoginRequest input = mapper.readerFor(LoginRequest.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(request.getInputStream());
            if (input == null || !validator.validate(input).isEmpty()) {
                throw new InvalidLoginRequestException(400);
            }
            return getAuthenticationManager().authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(input.username(), input.password()));
        } catch (AuthenticationServiceException exception) {
            // 父过滤器会记录 InternalAuthenticationServiceException 的完整堆栈。
            // 在进入该路径前去除原始消息和 cause，避免 SQL/凭据经框架日志泄漏。
            throw new AuthenticationServiceException("登录服务暂时不可用");
        } catch (JsonProcessingException exception) {
            // 不回显解析异常，其中可能包含密码或原始请求。
            throw new InvalidLoginRequestException(400);
        } catch (InvalidMediaTypeException exception) {
            throw new InvalidLoginRequestException(415);
        }
    }

    public static final class InvalidLoginRequestException extends AuthenticationException {
        private final int status;
        public InvalidLoginRequestException(int status) {
            super("登录请求无效");
            this.status = status;
        }
        public int getStatus() { return status; }
    }
}
