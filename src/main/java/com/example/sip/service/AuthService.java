package com.example.sip.service;

import com.example.sip.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 页面登录态管理：校验用户名密码、签发 / 校验 / 注销 token。
 *
 * <p>token 保存在服务端内存（token -&gt; 过期时间戳），Cookie 只存 token 本身，
 * 校验时惰性清理过期项。单节点部署足够，重启后所有登录态自然失效。
 */
@Service
public class AuthService {

    public static final String TOKEN_COOKIE = "AUTH_TOKEN";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthProperties props;

    /** token -> 过期时间戳（epoch millis） */
    private final Map<String, Long> tokenExpiry = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    public AuthService(AuthProperties props) {
        this.props = props;
    }

    /**
     * 校验用户名密码，成功返回新签发的 token，失败返回 null。
     * 密码比对使用常量时间比较，防时序侧信道。
     */
    public String login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        if (!matches(username.trim(), props.getUsername())
                || !matches(password, props.getPassword())) {
            log.warn("[Auth] 登录失败: username={}", username);
            return null;
        }

        String token = newToken();
        tokenExpiry.put(token, System.currentTimeMillis() + props.tokenTtlSeconds() * 1000);
        log.info("[Auth] 登录成功: username={} ttlHours={}",
                props.getUsername(), props.getTokenTtlHours());
        return token;
    }

    /** token 是否有效（存在且未过期，过期项惰性移除） */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long expiry = tokenExpiry.get(token);
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            tokenExpiry.remove(token);
            return false;
        }
        return true;
    }

    /** 注销 token（不存在时静默忽略） */
    public void logout(String token) {
        if (token != null) {
            tokenExpiry.remove(token);
        }
    }

    /** 当前登录用户名（配置唯一，供前端展示） */
    public String currentUsername() {
        return props.getUsername();
    }

    /** token 有效期（秒），用于 Cookie Max-Age */
    public long tokenTtlSeconds() {
        return props.tokenTtlSeconds();
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + Integer.toHexString(random.nextInt());
    }

    private boolean matches(String provided, String expected) {
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
