package com.example.sip.api.config;

import com.example.sip.api.auth.OpenApiAuthInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open API Web 配置：仅针对 /api/v1/**。
 *
 * <p>注册鉴权拦截器 {@link OpenApiAuthInterceptor}（X-API-Key 校验，
 * 配置见 sip.openapi.*），并开放 CORS 供外部系统集成。
 *
 * <p>不注册其它路径，不影响现有内部接口与页面。
 */
@Configuration
public class OpenApiWebConfig implements WebMvcConfigurer {

    private final OpenApiProperties openApiProps;
    private final ObjectMapper objectMapper;

    public OpenApiWebConfig(OpenApiProperties openApiProps, ObjectMapper objectMapper) {
        this.openApiProps = openApiProps;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(OpenApiAuthInterceptor.API_KEY_HEADER)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new OpenApiAuthInterceptor(openApiProps, objectMapper))
                .addPathPatterns("/api/v1/**");
    }
}
