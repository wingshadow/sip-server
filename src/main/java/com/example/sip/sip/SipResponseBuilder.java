package com.example.sip.sip;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.SipMessage;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 极简 SIP 响应构造器。
 *
 * <p>响应严格按照 RFC 3261 规则回显 Via / From / To / Call-ID / CSeq，
 * 保证国标摄像头能正常识别。body 按 GB2312 编码（国标默认字符集）。
 */
public final class SipResponseBuilder {

    private static final String USER_AGENT = "gb28181-sip-server/1.0";

    private SipResponseBuilder() {
    }

    /**
     * 构造 SIP 响应字节流。
     *
     * @param req         请求消息
     * @param code        状态码，如 200
     * @param reason      原因短语，如 "OK"
     * @param contentType Content-Type，仅在有 body 时携带
     * @param body        响应体（XML/SDP），可为 null
     * @param props       服务器配置（用于 Contact）
     */
    public static byte[] build(SipMessage req, int code, String reason,
                               String contentType, String body,
                               SipServerProperties props) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Via: ").append(req.getHeader("Via")).append("\r\n");
        sb.append("From: ").append(req.getHeader("From")).append("\r\n");

        String to = req.getHeader("To");
        if (to != null && !to.contains("tag=") && !to.contains("tag =")) {
            to = to + ";tag=" + shortTag();
        }
        sb.append("To: ").append(to).append("\r\n");
        sb.append("Call-ID: ").append(req.getHeader("Call-ID")).append("\r\n");
        sb.append("CSeq: ").append(req.getHeader("CSeq")).append("\r\n");
        sb.append("Contact: <sip:").append(props.getId()).append('@')
                .append(props.getIp()).append(':').append(props.getPort()).append(">\r\n");
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n");

        byte[] bodyBytes = body == null ? new byte[0]
                : body.getBytes(Charset.forName("GB2312"));
        if (bodyBytes.length > 0 && contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] resp = new byte[head.length + bodyBytes.length];
        System.arraycopy(head, 0, resp, 0, head.length);
        System.arraycopy(bodyBytes, 0, resp, head.length, bodyBytes.length);
        return resp;
    }

    /**
     * 空 body 的 200 OK
     */
    public static byte[] ok(SipMessage req, SipServerProperties props) {
        return build(req, 200, "OK", null, null, props);
    }

    /**
     * 构造 SIP 响应并强制 To 头携带指定 tag
     * （语音广播场景：平台需记录 200 OK 的 to-tag 用于 BYE）。
     */
    public static byte[] buildWithTag(SipMessage req, int code, String reason,
                                      String contentType, String body,
                                      SipServerProperties props, String toTag) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Via: ").append(req.getHeader("Via")).append("\r\n");
        sb.append("From: ").append(req.getHeader("From")).append("\r\n");

        String to = req.getHeader("To");
        if (to != null && !to.contains("tag=") && !to.contains("tag =")) {
            to = to + ";tag=" + toTag;
        }
        sb.append("To: ").append(to).append("\r\n");
        sb.append("Call-ID: ").append(req.getHeader("Call-ID")).append("\r\n");
        sb.append("CSeq: ").append(req.getHeader("CSeq")).append("\r\n");
        sb.append("Contact: <sip:").append(props.getId()).append('@')
                .append(props.getIp()).append(':').append(props.getPort()).append(">\r\n");
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n");

        byte[] bodyBytes = body == null ? new byte[0]
                : body.getBytes(Charset.forName("GB2312"));
        if (bodyBytes.length > 0 && contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] resp = new byte[head.length + bodyBytes.length];
        System.arraycopy(head, 0, resp, 0, head.length);
        System.arraycopy(bodyBytes, 0, resp, head.length, bodyBytes.length);
        return resp;
    }

    private static String shortTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * REGISTER 200 OK（携带 Date 与 Expires）。
     *
     * <p>Date 为 RFC 1123 格式（GMT），国标设备依赖它校时；
     * Expires 回显注册有效期。
     */
    public static byte[] buildRegisterOk(SipMessage req, SipServerProperties props, int expires) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("SIP/2.0 200 OK\r\n");
        sb.append("Via: ").append(req.getHeader("Via")).append("\r\n");
        sb.append("From: ").append(req.getHeader("From")).append("\r\n");
        sb.append("To: ").append(req.getHeader("To")).append("\r\n");
        sb.append("Call-ID: ").append(req.getHeader("Call-ID")).append("\r\n");
        sb.append("CSeq: ").append(req.getHeader("CSeq")).append("\r\n");
        sb.append("Date: ").append(httpDate()).append("\r\n");
        sb.append("Expires: ").append(expires).append("\r\n");
        sb.append("Contact: <sip:").append(props.getId()).append('@')
                .append(props.getIp()).append(':').append(props.getPort()).append(">\r\n");
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        sb.append("Content-Length: 0\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 401 Unauthorized（携带 WWW-Authenticate 挑战）。
     *
     * <p>设备首次 REGISTER 不带 Authorization 时回此响应，
     * 设备将携带摘要凭据重试。nonce 由调用方生成并记录（RegisterHandler），
     * 用于校验设备回带的 nonce 确为本平台发出。
     */
    public static byte[] buildUnauthorized(SipMessage req, SipServerProperties props) {
        return buildUnauthorized(req, props,
                UUID.randomUUID().toString().replace("-", ""), false);
    }

    /**
     * 401 Unauthorized，指定 nonce 与 stale 标志。
     *
     * @param nonce 平台生成并记录的挑战随机数
     * @param stale true 表示"nonce 已过期/失效，请用新 nonce 立即重试"
     *              （设备收到 stale=true 无需再等一轮挑战，直接重发 REGISTER）
     */
    public static byte[] buildUnauthorized(SipMessage req, SipServerProperties props,
                                           String nonce, boolean stale) {
        StringBuilder sb = new StringBuilder(320);
        sb.append("SIP/2.0 401 Unauthorized\r\n");
        sb.append("Via: ").append(req.getHeader("Via")).append("\r\n");
        sb.append("From: ").append(req.getHeader("From")).append("\r\n");
        sb.append("To: ").append(req.getHeader("To")).append("\r\n");
        sb.append("Call-ID: ").append(req.getHeader("Call-ID")).append("\r\n");
        sb.append("CSeq: ").append(req.getHeader("CSeq")).append("\r\n");
        sb.append("WWW-Authenticate: Digest realm=\"").append(props.getDomain())
                .append("\",nonce=\"").append(nonce).append("\",stale=")
                .append(stale).append("\r\n");
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        sb.append("Content-Length: 0\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** RFC 1123 日期（GMT），如 Tue, 14 Oct 2026 08:30:00 GMT */
    private static String httpDate() {
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern(
                        "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                        java.util.Locale.US);
        return java.time.Instant.now().atZone(java.time.ZoneId.of("GMT")).format(fmt);
    }
}
