package com.example.sip.config;

import com.example.sip.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 页面登录过滤器：未登录访问受保护页面时重定向到 /login.html。
 *
 * <ul>
 *   <li>保护范围：*.html（login.html 自身除外）与根路径 /</li>
 *   <li>放行：/api/**、静态 css/js、/hls/** 播放切片、WebSocket 握手等一切非页面请求</li>
 *   <li>凭证：Cookie AUTH_TOKEN（登录时由 {@code /api/login} 下发）</li>
 *   <li>重定向携带 next 参数，登录成功后回到原页面</li>
 * </ul>
 */
@Component
public class PageAuthFilter extends OncePerRequestFilter {

    private static final String LOGIN_PAGE = "/login.html";

    private final AuthService authService;

    public PageAuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 只保护页面；login.html 与全部非页面请求（API / 静态资源 / WS）直接放行
        return !"/".equals(uri)
                && (!uri.endsWith(".html") || LOGIN_PAGE.equals(uri));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String token = cookieToken(request);
        if (authService.isValid(token)) {
            chain.doFilter(request, response);
            return;
        }

        String next = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            next = next + "?" + query;
        }
        response.sendRedirect(LOGIN_PAGE + "?next="
                + URLEncoder.encode(next, StandardCharsets.UTF_8));
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (AuthService.TOKEN_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
