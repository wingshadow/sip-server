package com.example.sip.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GB/T 28181 SIP 服务器配置。
 */
@Component
@ConfigurationProperties(prefix = "sip.server")
public class SipServerProperties {

    /** SIP 服务器 20 位国标编码 */
    private String id = "34020000002000000001";

    /** UDP 监听地址 */
    private String host = "0.0.0.0";

    /** UDP 监听端口（默认 5060） */
    private int port = 5060;

    /** 对外通告 IP（注册 Contact / 点播 SDP 中携带，需摄像头可达） */
    private String ip = "127.0.0.1";

    /** 心跳超时（秒），超过视为离线 */
    private int heartbeatTimeout = 180;

    /** SIP 域名（WWW-Authenticate realm / 点播 SDP 使用） */
    private String domain = "3402000000";

    /** 设备密码（SIP 注册 Digest 鉴权；为空则跳过鉴权） */
    private String password;

    /**
     * 注册鉴权开关（可选）。
     *
     * <p>不配置时按 {@link #password} 是否为空自动判断（非空即启用）；
     * 置 false 可临时关闭鉴权而不必删除密码配置。
     */
    private Boolean authEnabled;

    /** Digest nonce 有效期（秒）：设备需在期内携带 Authorization 重试 REGISTER */
    private int nonceTtlSeconds = 300;

    /**
     * 直播会话保活开关（默认关闭）。
     *
     * <p>部分设备侧会话超时（常见 300s+）会清理长时间无信令交互的 INVITE Dialog，
     * 导致 7×24 长直播媒体中断。启用后按 {@link #sessionKeepaliveSeconds}
     * 周期向设备发会话内 INFO 刷新 Dialog；仅 local 媒体模式生效
     * （zlm 模式由 ZLM 流保活兜底）。发送失败仅记日志，不拆会话。
     */
    private boolean sessionKeepaliveEnabled = false;

    /**
     * 直播会话保活间隔（秒）。应显著小于设备侧会话超时（默认 60，
     * 对常见 300s+ 超时有 5 倍余量）；过小会增加设备信令负载。
     */
    private int sessionKeepaliveSeconds = 60;

    /**
     * 告警 HTTP 外发开关（默认关闭）。
     *
     * <p>启用后设备告警（MESSAGE 上报与 NOTIFY 订阅事件）以 JSON POST
     * 异步推送到 {@link #alarmWebhookUrl}。异步入队、有界队列，
     * SIP 信令线程永不被阻塞；队列满丢弃最旧事件并告警。
     */
    private boolean alarmWebhookEnabled = false;

    /** 告警推送目标 URL（如 {@code http://ops.example.com/api/gb28181/alarm}） */
    private String alarmWebhookUrl = "";

    /** 告警推送连接/读取超时（毫秒） */
    private int alarmWebhookTimeoutMs = 3000;

    /**
     * 按设备编号的注册密码（key = 设备 20 位国标编码，"*" 为通配默认密码），
     * 查找优先级：device-passwords[设备编号] → device-passwords["*"] → password。
     */
    private Map<String, String> devicePasswords = new LinkedHashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public void setHeartbeatTimeout(int heartbeatTimeout) {
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(Boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public int getNonceTtlSeconds() {
        return nonceTtlSeconds;
    }

    public void setNonceTtlSeconds(int nonceTtlSeconds) {
        this.nonceTtlSeconds = nonceTtlSeconds;
    }

    public boolean isSessionKeepaliveEnabled() {
        return sessionKeepaliveEnabled;
    }

    public void setSessionKeepaliveEnabled(boolean sessionKeepaliveEnabled) {
        this.sessionKeepaliveEnabled = sessionKeepaliveEnabled;
    }

    public int getSessionKeepaliveSeconds() {
        return sessionKeepaliveSeconds;
    }

    public void setSessionKeepaliveSeconds(int sessionKeepaliveSeconds) {
        this.sessionKeepaliveSeconds = sessionKeepaliveSeconds;
    }

    public boolean isAlarmWebhookEnabled() {
        return alarmWebhookEnabled;
    }

    public void setAlarmWebhookEnabled(boolean alarmWebhookEnabled) {
        this.alarmWebhookEnabled = alarmWebhookEnabled;
    }

    public String getAlarmWebhookUrl() {
        return alarmWebhookUrl;
    }

    public void setAlarmWebhookUrl(String alarmWebhookUrl) {
        this.alarmWebhookUrl = alarmWebhookUrl;
    }

    public int getAlarmWebhookTimeoutMs() {
        return alarmWebhookTimeoutMs;
    }

    public void setAlarmWebhookTimeoutMs(int alarmWebhookTimeoutMs) {
        this.alarmWebhookTimeoutMs = alarmWebhookTimeoutMs;
    }

    public Map<String, String> getDevicePasswords() {
        return devicePasswords;
    }

    public void setDevicePasswords(Map<String, String> devicePasswords) {
        this.devicePasswords = devicePasswords;
    }

    /**
     * 是否启用注册鉴权：{@code auth-enabled} 显式值优先，
     * 未配置时按 {@link #password} 非空自动判断（向后兼容旧行为）。
     */
    public boolean isAuthEnabled() {
        if (authEnabled != null) {
            return authEnabled;
        }
        return !isBlank(password);
    }

    /**
     * 解析指定设备的鉴权密码；返回 {@code null} 表示该设备不鉴权。
     *
     * <p>查找顺序：device-passwords[设备编号] → device-passwords["*"] → password。
     */
    public String resolvePassword(String deviceId) {
        if (!isAuthEnabled()) {
            return null;
        }
        if (devicePasswords != null) {
            String p = devicePasswords.get(deviceId);
            if (isBlank(p)) {
                p = devicePasswords.get("*");
            }
            if (!isBlank(p)) {
                return p;
            }
        }
        return isBlank(password) ? null : password;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
