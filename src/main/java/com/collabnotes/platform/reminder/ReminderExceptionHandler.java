package com.collabnotes.platform.reminder;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice(assignableTypes = {ReminderController.class, NotificationController.class})
public class ReminderExceptionHandler extends ResponseEntityExceptionHandler {
    public record Error(String code, String message, Map<String, String> fields) { }
    @ExceptionHandler(ReminderException.class)
    public ResponseEntity<Error> business(ReminderException exception) {
        return ResponseEntity.status(exception.status()).body(new Error(exception.code(), exception.getMessage(), Map.of()));
    }
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return new ResponseEntity<>(new Error("INVALID_INPUT", "请检查提醒信息", fields), headers, status);
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(new Error("INVALID_REQUEST", "请求格式、时间、编号、分页参数或方法不正确", Map.of()), headers, status);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> unexpected(Exception exception) {
        logger.error("提醒或通知操作失败，错误类型：" + exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new Error("INTERNAL_ERROR", "提醒服务暂时不可用", Map.of()));
    }
}
