package com.example.sip.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>将业务异常（设备未注册、端口耗尽、媒体未就绪等 IllegalStateException，
 * 参数非法 IllegalArgumentException）转换为前端统一的
 * {@code {"code":..,"message":".."}} JSON，便于页面直接提示错误原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return json(500, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return json(400, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> json(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message",
                (message == null || message.isBlank()) ? "未知错误" : message);

        return ResponseEntity.status(code).body(body);
    }
}
