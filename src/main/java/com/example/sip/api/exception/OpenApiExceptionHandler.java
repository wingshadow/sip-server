package com.example.sip.api.exception;

import com.example.sip.api.dto.OpenApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Open API 异常处理：把异常统一转为 {code, message, data}。
 *
 * <p>仅作用于 com.example.sip.openapi 包（basePackages 限定），
 * 不影响现有内部 Controller（/api/**）的异常行为。
 *
 * <p>映射规则：
 * <ul>
 *   <li>{@link DeviceNotFoundException} → 404 / 40400</li>
 *   <li>{@link IllegalArgumentException}（Service 参数/校验失败）→ 400 / 40000</li>
 *   <li>{@link IllegalStateException}（设备离线、会话状态不允许等）→ 409 / 40900</li>
 *   <li>请求参数缺失 / JSON body 不可读 → 400 / 40000</li>
 *   <li>其余异常 → 500 / 50000（日志留痕，message 不外泄内部细节）</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.example.sip.openapi")
public class OpenApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OpenApiExceptionHandler.class);

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<OpenApiResult<Void>> onDeviceNotFound(DeviceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(OpenApiResult.error(OpenApiResult.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<OpenApiResult<Void>> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(OpenApiResult.error(OpenApiResult.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<OpenApiResult<Void>> onIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(OpenApiResult.error(OpenApiResult.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<OpenApiResult<Void>> onMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(OpenApiResult.error(OpenApiResult.BAD_REQUEST,
                        "缺少必填参数: " + e.getParameterName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<OpenApiResult<Void>> onUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(OpenApiResult.error(OpenApiResult.BAD_REQUEST,
                        "请求体缺失或 JSON 格式错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OpenApiResult<Void>> onUnexpected(Exception e) {
        log.error("[OpenAPI] 未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(OpenApiResult.error(OpenApiResult.INTERNAL_ERROR, "服务内部错误"));
    }
}
