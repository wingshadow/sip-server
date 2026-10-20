package com.example.sip.sip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SIP 注册 Digest 鉴权计算（RFC 2617 / GB/T 28181 附录鉴权流程）。
 *
 * <p>国标设备 REGISTER 鉴权交互：
 * <ol>
 *   <li>平台对无 Authorization 的 REGISTER 回 <b>401 + WWW-Authenticate</b>（realm/nonce/stale）</li>
 *   <li>设备计算摘要后携带 Authorization 重试 REGISTER</li>
 *   <li>平台重算摘要并比对 response 字段</li>
 * </ol>
 *
 * <p>摘要计算（RFC 2617 §3.2.2）：
 * <pre>
 * HA1      = MD5(username ":" realm ":" password)
 * HA2      = MD5(method ":" uri)              // 注册场景 method=REGISTER
 * response = MD5(HA1 ":" nonce ":" HA2)       // 无 qop
 * response = MD5(HA1 ":" nonce ":" nc ":" cnonce ":" qop ":" HA2)  // qop=auth
 * </pre>
 *
 * <p>注意：{@code uri} 必须使用设备 Authorization 头中回带的原值（如
 * {@code sip:34020000002000000001@3402000000}），不能自行假设格式；
 * {@code realm} 同样以设备回带值为准，缺省回落 {@code sip.server.domain}。
 */
public final class SipDigestAuth {

    private SipDigestAuth() {
    }

    /** HA1 = MD5(username:realm:password) */
    public static String ha1(String username, String realm, String password) {
        return md5Hex(username + ":" + realm + ":" + password);
    }

    /** HA2 = MD5(method:uri)，注册场景 method 为 REGISTER */
    public static String ha2(String method, String uri) {
        return md5Hex(method + ":" + uri);
    }

    /** 无 qop：response = MD5(HA1:nonce:HA2)（国标设备主流形态） */
    public static String response(String ha1, String nonce, String ha2) {
        return md5Hex(ha1 + ":" + nonce + ":" + ha2);
    }

    /** qop=auth：response = MD5(HA1:nonce:nc:cnonce:qop:HA2) */
    public static String responseWithQop(String ha1, String nonce, String nc,
                                         String cnonce, String qop, String ha2) {
        return md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
    }

    /** MD5 十六进制串（小写） */
    public static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /** 常量时间比对两个十六进制摘要（大小写不敏感），null 安全 */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        byte[] b = actual.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }
}
