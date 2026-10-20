package com.example.sip.controller;

import com.example.sip.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 页面登录 / 登出接口。
 *
 * <ul>
 *   <li>POST /api/login  校验用户名密码，成功下发 HttpOnly Cookie（AUTH_TOKEN）</li>
 *   <li>POST /api/logout 注销 token 并清除 Cookie</li>
 *   <li>GET  /api/auth/check 查询当前登录态（供前端探测）</li>
 * </ul>
 */
@RestController
public class LoginController {

    private final AuthService authService;

    public LoginController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录：请求体 {"username":"...","password":"..."} */
    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody(required = false) Map<String, Object> body) {

        String username = body == null ? null : str(body.get("username"));
        String password = body == null ? null : str(body.get("password"));

        String token = authService.login(username, password);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(result(401, "用户名或密码错误"));
        }

        ResponseCookie cookie = ResponseCookie.from(AuthService.TOKEN_COOKIE, token)
                .httpOnly(true)
                .path("/")
                .maxAge(authService.tokenTtlSeconds())
                .sameSite("Lax")
                .build();

        Map<String, Object> ok = result(0, "登录成功");
        ok.put("username", authService.currentUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ok);
    }

    /** 登出：清除 Cookie，服务端 token 同步失效 */
    @PostMapping("/api/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        ResponseCookie cookie = ResponseCookie.from(AuthService.TOKEN_COOKIE, "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(result(0, "已退出登录"));
    }

    /** 登录态探测：有效返回 {code:0, username}，无效返回 401 */
    @GetMapping("/api/auth/check")
    public ResponseEntity<Map<String, Object>> check(
            @CookieValue(name = AuthService.TOKEN_COOKIE, required = false) String token) {

        if (!authService.isValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(result(401, "未登录或登录已过期"));
        }
        Map<String, Object> ok = result(0, "已登录");
        ok.put("username", authService.currentUsername());
        return ResponseEntity.ok(ok);
    }

    private Map<String, Object> result(int code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        return map;
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
