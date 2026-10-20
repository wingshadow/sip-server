package com.example.sip.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 SIP 消息模型，适配 GB/T 28181-2022 所需的信令子集。
 *
 * <p>body 统一以 ISO-8859-1 文本保存，从而无损保留原始字节
 * （GB2312 编码的中文 XML 不会失真），需要解码时再还原字节。
 */
public class SipMessage {

    /** 请求行 / 状态行，如 "REGISTER sip:34020000002000000001@1.2.3.4:5060 SIP/2.0" */
    private final String startLine;

    /** 头部（保留原始大小写，读取时忽略大小写） */
    private final Map<String, String> headers;

    /** 消息体（ISO-8859-1 编码文本） */
    private final String body;

    private SipMessage(String startLine, Map<String, String> headers, String body) {
        this.startLine = startLine;
        this.headers = headers;
        this.body = body;
    }

    /**
     * 从 UDP 报文解析 SIP 消息。
     */
    public static SipMessage parse(byte[] data) {
        String raw = new String(data, StandardCharsets.ISO_8859_1);
        int headEnd = raw.indexOf("\r\n\r\n");
        if (headEnd < 0) {
            headEnd = raw.indexOf("\n\n");
        }
        String head = headEnd < 0 ? raw : raw.substring(0, headEnd);
        String body = headEnd >= 0 && raw.length() > headEnd + 4
                ? raw.substring(headEnd + 4)
                : "";

        Map<String, String> headers = new LinkedHashMap<>();
        String[] lines = head.split("\r\n|\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                // 同名头（如多个 Via）拼接，这里只保留第一个，够用
                headers.putIfAbsent(name, value);
            }
        }
        return new SipMessage(lines.length > 0 ? lines[0].trim() : "", headers, body);
    }

    /** 请求方法，如 REGISTER / MESSAGE / INVITE / ACK / BYE / OPTIONS */
    public String method() {
        return startLine.contains(" ") ? startLine.split(" ")[0] : "";
    }

    /** 响应状态码（响应行如 "SIP/2.0 200 OK" 时返回 200，请求消息返回 0） */
    public int statusCode() {
        String[] parts = startLine.split(" ");
        if (parts.length > 1 && "SIP/2.0".equals(parts[0])) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                // 非数字状态码，按 0 处理
            }
        }
        return 0;
    }

    /** 请求行中的 URI（如 sip:xxx） */
    public String uri() {
        String[] parts = startLine.split(" ");
        return parts.length > 1 ? parts[1] : "";
    }

    /**
     * 响应原因短语（状态行如 "SIP/2.0 404 Requested device not found"
     * 时返回 "Requested device not found"；请求消息返回空串）。
     */
    public String reasonPhrase() {
        if (!startLine.startsWith("SIP/2.0 ")) {
            return "";
        }

        int first = startLine.indexOf(' ');
        int second =
                first >= 0 ? startLine.indexOf(' ', first + 1) : -1;

        return second >= 0 && second + 1 < startLine.length()
                ? startLine.substring(second + 1).trim()
                : "";
    }

    /** 忽略大小写读取头部 */
    public String getHeader(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 全部头部快照（保留原始大小写，键无序） */
    public Map<String, String> headers() {
        return headers;
    }

    /** 消息体文本（ISO-8859-1 原样，XML/SDP 解析前请先用 getBodyBytes） */
    public String getBody() {
        return body == null ? "" : body;
    }

    /** 消息体原始字节 */
    public byte[] getBodyBytes() {
        return body == null ? new byte[0] : body.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 按指定字符集解码消息体（GB2312 / GBK / UTF-8） */
    public String getBodyText(Charset charset) {
        return charset == null ? "" : new String(getBodyBytes(), charset);
    }

    /** 原始消息文本（用于日志） */
    public String toRaw() {
        StringBuilder sb = new StringBuilder(startLine).append("\r\n");
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n").append(body);
        return sb.toString();
    }

    @Override
    public String toString() {
        return startLine;
    }
}
