package com.example.sip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 页面登录鉴权配置：用户名 / 密码 / token 有效期。
 *
 * <p>仅保护管理页面（index/live/playback/voice.html），
 * 不影响 /api/** 内部接口与 /api/v1/** Open API（后者由 X-API-Key 独立鉴权）。
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 登录用户名 */
    private String username = "admin";

    /** 登录密码（内网部署可直接配置；生产建议用环境变量覆盖） */
    private String password = "Raonecloud@2026#";

    /** 登录 token 有效期（小时），过期后需重新登录 */
    private long tokenTtlHours = 12;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public long getTokenTtlHours() {
        return tokenTtlHours;
    }

    public void setTokenTtlHours(long tokenTtlHours) {
        this.tokenTtlHours = tokenTtlHours <= 0 ? 12 : tokenTtlHours;
    }

    /** token 有效期（秒），用于 Cookie Max-Age */
    public long tokenTtlSeconds() {
        return tokenTtlHours * 3600;
    }
}
