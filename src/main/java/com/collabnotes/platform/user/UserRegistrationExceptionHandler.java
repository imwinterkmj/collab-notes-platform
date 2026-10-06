package com.collabnotes.platform.user;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = UserRegistrationController.class)
public class UserRegistrationExceptionHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return new ResponseEntity<>(new RegistrationError("INVALID_INPUT", "请检查注册信息", fields),
                headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request
    ) {
        return new ResponseEntity<>(new RegistrationError("INVALID_REQUEST", "请求须包含合法的注册 JSON", Map.of()),
                headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request
    ) {
        return new ResponseEntity<>(new RegistrationError("INVALID_REQUEST", "请求格式或方法不正确", Map.of()),
                headers, status);
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<RegistrationError> handleUsernameConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new RegistrationError("USERNAME_TAKEN", "用户名已被占用", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RegistrationError> handleUnexpectedFailure(Exception exception) {
        // 不打印异常内容或堆栈：数据库异常可能包含 SQL 参数，含密码哈希。
        logger.error("用户注册失败，错误类型：" + exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new RegistrationError("INTERNAL_ERROR", "注册暂时失败，请稍后重试", Map.of()));
    }
}
