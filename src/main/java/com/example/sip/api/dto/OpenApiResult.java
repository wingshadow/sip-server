package com.example.sip.api.dto;

/**
 * Open API 统一响应体：{code, message, data}。
 *
 * <p>code 语义：
 * <ul>
 *   <li>0 —— 成功</li>
 *   <li>40000 —— 参数校验失败 / 请求格式错误</li>
 *   <li>40100 —— 鉴权失败（缺少或无效的 X-API-Key）</li>
 *   <li>40400 —— 设备不存在</li>
 *   <li>40900 —— 会话状态冲突（设备离线、会话不存在或状态不允许）</li>
 *   <li>50000 —— 服务内部错误</li>
 * </ul>
 */
public class OpenApiResult<T> {

    public static final int OK = 0;
    public static final int BAD_REQUEST = 40000;
    public static final int UNAUTHORIZED = 40100;
    public static final int NOT_FOUND = 40400;
    public static final int CONFLICT = 40900;
    public static final int INTERNAL_ERROR = 50000;

    private final int code;
    private final String message;
    private final T data;

    private OpenApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（data 可为任意可序列化对象） */
    public static <T> OpenApiResult<T> ok(T data) {
        return new OpenApiResult<>(OK, "success", data);
    }

    /** 失败（code 与 HTTP 状态对应，message 透传给调用方） */
    public static <T> OpenApiResult<T> error(int code, String message) {
        return new OpenApiResult<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
