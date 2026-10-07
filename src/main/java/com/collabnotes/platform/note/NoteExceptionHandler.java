package com.collabnotes.platform.note;

import java.util.LinkedHashMap;
import java.util.Map;

import com.collabnotes.platform.reminder.ReminderException;

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

@RestControllerAdvice(assignableTypes = {NoteController.class, NoteSaveController.class})
public class NoteExceptionHandler extends ResponseEntityExceptionHandler {
    public record NoteError(String code, String message, Map<String, String> fields) { }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return new ResponseEntity<>(new NoteError("INVALID_INPUT", "请检查备忘录信息", fields), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(new NoteError("INVALID_REQUEST", "请求须包含合法的备忘录 JSON", Map.of()),
                headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(new NoteError("INVALID_REQUEST", "请求格式、编号、分页参数或方法不正确", Map.of()),
                headers, status);
    }

    @ExceptionHandler(NoteNotFoundException.class)
    public ResponseEntity<NoteError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NoteError("NOTE_NOT_FOUND", "备忘录不存在或不可访问", Map.of()));
    }

    @ExceptionHandler(ReminderException.class)
    public ResponseEntity<NoteError> reminderFailure(ReminderException exception) {
        return ResponseEntity.status(exception.status())
                .body(new NoteError(exception.code(), exception.getMessage(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<NoteError> unexpected(Exception exception) {
        // 数据库异常可能含正文与 SQL 参数，不记录消息、堆栈或 cause。
        logger.error("备忘录操作失败，错误类型：" + exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new NoteError("INTERNAL_ERROR", "备忘录服务暂时不可用", Map.of()));
    }
}
