package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Device;
import com.example.sip.model.SipMessage;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.sip.SipDigestAuth;
import com.example.sip.sip.SipResponseBuilder;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备注册处理器（GB/T 28181 REGISTER）。
 *
 * <p>职责：鉴权（配置了设备密码时校验 Authorization 摘要）→ 登记/注销设备
 * → 回复 401（带 WWW-Authenticate）或 200 OK。
 */
@Component
public class RegisterHandler {

    private static final Logger log = LoggerFactory.getLogger(RegisterHandler.class);

    /** Contact 头 sip:xxx@host[:port] 提取（与 1.0 一致） */
    private static final Pattern CONTACT_HOST_PORT =
            Pattern.compile("sip:[^@]+@([^:>]+)(?::(\\d+))?");

    /**
     * 设备编号 → 已发出的 nonce 记录。
     *
     * <p>用途：① 校验设备回带的 nonce 确由本平台发出（防伪造挑战/重放）；
     * ② 过期时以 stale=true 重新挑战，设备免二次往返立即重试。
     */
    private final Map<String, NonceEntry> nonces = new ConcurrentHashMap<>();

    private static final class NonceEntry {
        final String nonce;
        final long expireAt;

        NonceEntry(String nonce, long expireAt) {
            this.nonce = nonce;
            this.expireAt = expireAt;
        }
    }

    private final SipServerProperties props;
    private final DeviceRegistry registry;

    public RegisterHandler(SipServerProperties props, DeviceRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    /**
     * 处理设备 REGISTER 请求。
     *
     * @param req    解析后的 SIP 请求
     * @param sender 设备信令地址（用于登记 host:port）
     * @param ctx    Netty 通道（用于回写响应）
     */
    public void handle(SipMessage req, InetSocketAddress sender, ChannelHandlerContext ctx) {
        String deviceId = SipUtil.extractSipId(req.getHeader("From"));
        if (deviceId == null) {
            log.warn("[28181] REGISTER 缺少 From 设备编号，忽略");
            return;
        }

        String auth = req.getHeader("Authorization");
        // 与 1.0 一致：无 Expires 头按缺省 3600 秒处理（缺省 0 会把正常注册当注销）
        int expires = 3600;
        for (Map.Entry<String, String> e : req.headers().entrySet()) {
            if (e.getKey().equalsIgnoreCase("Expires")) {
                try {
                    expires = Integer.parseInt(e.getValue().trim());
                } catch (NumberFormatException ignore) {
                    expires = 0;
                }
            }
        }

        // 鉴权密码解析顺序：device-passwords[设备编号] → device-passwords["*"] → password；
        // 返回 null（未配置密码 / 显式关闭）时跳过鉴权，直接放行注册
        String pwd = props.resolvePassword(deviceId);
        if (pwd != null) {
            if (auth == null) {
                // 首次注册：回 401 挑战，设备将带 Authorization 重试
                SipResponder.reply(ctx, sender,
                        SipResponseBuilder.buildUnauthorized(req, props, issueNonce(deviceId), false));
                log.info("[28181] 请求设备鉴权: deviceId={}", deviceId);
                return;
            }
            String failure = verifyDigest(deviceId, pwd, auth);
            if (failure != null) {
                // nonce 过期/失效时以 stale=true 重新挑战，设备免二次往返立即重试
                boolean stale = failure.startsWith("nonce");
                SipResponder.reply(ctx, sender,
                        SipResponseBuilder.buildUnauthorized(req, props, issueNonce(deviceId), stale));
                log.warn("[28181] 设备鉴权失败: deviceId={} 原因={}", deviceId, failure);
                return;
            }
            nonces.remove(deviceId);
            log.info("[28181] 设备鉴权通过: deviceId={}", deviceId);
        }

        if (expires <= 0) {
            registry.unregister(deviceId);
            nonces.remove(deviceId);
            log.info("[28181] 设备注销(Expires=0): {}", deviceId);
        } else {
            // 与 1.0 一致：优先按 Contact 头登记设备地址（NAT 场景），回退 UDP 源地址
            String[] hp = parseContactHostPort(req.getHeader("Contact"));
            String host = hp[0] != null ? hp[0] : sender.getAddress().getHostAddress();
            int contactPort;
            try {
                contactPort = Integer.parseInt(hp[1]);
            } catch (NumberFormatException ignore) {
                contactPort = -1;
            }
            int port = contactPort > 0 ? contactPort : sender.getPort();

            Device d = registry.register(deviceId, host, port);
            log.info("[28181] 设备注册: {} @ {}:{} expires={}",
                    deviceId, d.getHost(), d.getPort(), expires);
        }

        SipResponder.reply(ctx, sender, SipResponseBuilder.buildRegisterOk(req, props, expires));
    }

    /**
     * 校验设备 Authorization 摘要（RFC 2617 / GB/T 28181 Digest）。
     *
     * <p>HA1=MD5(username:realm:password)，HA2=MD5("REGISTER":uri)，
     * response=MD5(HA1:nonce:HA2)；设备携带 qop=auth 时按
     * MD5(HA1:nonce:nc:cnonce:qop:HA2) 计算。uri/realm 一律以设备回带值为准
     * （uri 常见 {@code sip:平台ID@域名}，不可自行假设格式）。
     *
     * @return {@code null} 表示校验通过；否则返回失败原因（摘要已脱敏，完整值见 debug 日志）
     */
    private String verifyDigest(String deviceId, String pwd, String auth) {
        String user = SipUtil.extractAuthParam(auth, "username");
        String realm = SipUtil.extractAuthParam(auth, "realm");
        String nonce = SipUtil.extractAuthParam(auth, "nonce");
        String uri = SipUtil.extractAuthParam(auth, "uri");
        String resp = SipUtil.extractAuthParam(auth, "response");
        String qop = SipUtil.extractAuthParam(auth, "qop");
        String nc = SipUtil.extractAuthParam(auth, "nc");
        String cnonce = SipUtil.extractAuthParam(auth, "cnonce");
        String algorithm = SipUtil.extractAuthParam(auth, "algorithm");

        if (user == null || nonce == null || uri == null || resp == null) {
            return "Authorization 缺少必要参数(username/nonce/uri/response)";
        }
        if (algorithm != null && !"MD5".equalsIgnoreCase(algorithm)) {
            return "不支持的 algorithm=" + algorithm + "（仅 MD5）";
        }

        NonceEntry entry = nonces.get(deviceId);
        if (entry == null) {
            return "nonce 非本平台发出（或已被清除）";
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            return "nonce 已过期(ttl=" + props.getNonceTtlSeconds() + "s)";
        }
        if (!entry.nonce.equalsIgnoreCase(nonce)) {
            return "nonce 不匹配";
        }

        String r = realm != null ? realm : props.getDomain();
        String ha1 = SipDigestAuth.ha1(user, r, pwd);
        String ha2 = SipDigestAuth.ha2("REGISTER", uri);
        String expect;
        if (qop != null) {
            if (nc == null || cnonce == null) {
                return "qop=" + qop + " 模式缺少 nc/cnonce";
            }
            expect = SipDigestAuth.responseWithQop(ha1, nonce, nc, cnonce, qop, ha2);
        } else {
            expect = SipDigestAuth.response(ha1, nonce, ha2);
        }
        if (!SipDigestAuth.matches(expect, resp)) {
            // 完整摘要 + 有效 nonce 即重放入场券，不进 warn 日志：仅 debug 留明细，
            // 失败原因里只带前缀；user/realm/uri 本就随 REGISTER 明文传输，无脱敏必要
            log.debug("[28181] Digest 明细: deviceId={} expect={} actual={} user={} realm={} uri={}",
                    deviceId, expect, resp, user, r, uri);
            return "摘要不匹配(expect=" + mask(expect) + ", actual=" + mask(resp)
                    + ", user=" + user + ", realm=" + r + ", uri=" + uri + ")";
        }
        return null;
    }

    /** 摘要脱敏：仅保留前 6 位 */
    private static String mask(String digest) {
        if (digest == null) {
            return "null";
        }
        return digest.length() <= 6 ? digest : digest.substring(0, 6) + "...";
    }

    /** 生成并记录 nonce（同设备再次挑战时覆盖旧记录） */
    private String issueNonce(String deviceId) {
        cleanExpiredNonces();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        nonces.put(deviceId, new NonceEntry(nonce,
                System.currentTimeMillis() + props.getNonceTtlSeconds() * 1000L));
        return nonce;
    }

    /** 清理过期 nonce（记录量极小，注册请求顺带执行，无需定时任务） */
    private void cleanExpiredNonces() {
        long now = System.currentTimeMillis();
        nonces.values().removeIf(e -> now > e.expireAt);
    }

    /**
     * 从 Contact 头提取 [host, port]；无匹配返回 [null, "-1"]（由调用方回退 UDP 源地址）。
     * 与 1.0 解析逻辑一致。
     */
    private String[] parseContactHostPort(String contact) {
        String[] result = new String[]{null, "-1"};
        if (contact == null) {
            return result;
        }
        Matcher m = CONTACT_HOST_PORT.matcher(contact);
        if (m.find()) {
            result[0] = m.group(1);
            if (m.group(2) != null) {
                result[1] = m.group(2);
            }
        }
        return result;
    }
}
