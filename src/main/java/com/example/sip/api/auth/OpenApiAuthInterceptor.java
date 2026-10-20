package com.example.sip.api.auth;

import com.example.sip.api.dto.OpenApiResult;
import com.example.sip.api.config.OpenApiProperties;
import com.example.sip.api.config.OpenApiWebConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Open API 鉴权拦截器：校验请求头 X-API-Key。
 *
 * <ul>
 *   <li>CORS 预检（OPTIONS）放行</li>
 *   <li>未启用 / 未配置 key 时放行</li>
 *   <li>key 匹配采用常量时间比较（{@link MessageDigest#isEqual}），防时序侧信道</li>
 *   <li>失败返回 401 / 40100，响应体与 Open API 统一格式一致</li>
 * </ul>
 *
 * <p>仅拦截 /api/v1/**（在 {@link OpenApiWebConfig} 注册），
 * 不影响内部接口与页面。
 */
public class OpenApiAuthInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-API-Key";

    private static final Logger log = LoggerFactory.getLogger(OpenApiAuthInterceptor.class);

    private final OpenApiProperties props;
    private final ObjectMapper objectMapper;

    public OpenApiAuthInterceptor(OpenApiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!props.isAuthRequired()) {
            return true;
        }

        String key = request.getHeader(API_KEY_HEADER);
        if (key != null && matches(key)) {
            return true;
        }

        String message = key == null
                ? "缺少 X-API-Key 请求头"
                : "X-API-Key 无效或已失效";
        log.warn("[OpenAPI] 鉴权失败: {} {} key={}",
                request.getMethod(), request.getRequestURI(),
                key == null ? "<missing>" : "<invalid>");
        writeUnauthorized(response, message);
        return false;
    }

    /** 常量时间逐个比对，避免时序侧信道泄露 key 前缀 */
    private boolean matches(String key) {
        byte[] provided = key.trim().getBytes(StandardCharsets.UTF_8);
        for (String candidate : props.getApiKeys()) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            byte[] expected = candidate.trim().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, provided)) {
                return true;
            }
        }
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                OpenApiResult.error(OpenApiResult.UNAUTHORIZED, message)));
    }
}
