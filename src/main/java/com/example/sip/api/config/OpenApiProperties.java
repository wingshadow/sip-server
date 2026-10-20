package com.example.sip.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Open API 安全配置（sip.openapi.*）。
 *
 * <p>enabled=true 且 api-keys 非空时，/api/v1/** 所有请求要求请求头
 * X-API-Key 与配置之一匹配，否则返回 401 / 40100。
 *
 * <p>未配置 api-keys 时直接放行（保持向后兼容），启动时打 WARN 提示。
 */
@Component
@ConfigurationProperties(prefix = "sip.openapi")
public class OpenApiProperties {

    /** 是否启用 API Key 校验 */
    private boolean enabled = true;

    /** 合法 API Key 列表（多接入方可各持一把） */
    private List<String> apiKeys = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    /** 是否需要鉴权：启用且至少配置了一个 key */
    public boolean isAuthRequired() {
        return enabled && apiKeys != null && !apiKeys.isEmpty();
    }
}
