package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.SipMessage;
import com.example.sip.sip.SipResponseBuilder;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SIP 信令路由器（原单体处理器拆分的编排层）。
 *
 * <p>职责仅限：解析 SIP 报文 → 按方法/CSeq 分发到各职责处理器 → 兜底响应。
 * 业务逻辑全部下沉到：
 * <ul>
 *   <li>{@link RegisterHandler} —— REGISTER 注册鉴权</li>
 *   <li>{@link DeviceMessageHandler} —— MANSCDP MESSAGE（心跳/目录/告警/录像）</li>
 *   <li>{@link AlarmSubscribeHandler} —— SUBSCRIBE/NOTIFY 告警订阅</li>
 *   <li>{@link RecordQueryHandler} —— RecordInfo 录像查询聚合</li>
 *   <li>{@link MediaSignalHandler} —— 视频媒体 INVITE/响应/BYE</li>
 *   <li>{@link VoiceSignalHandler} —— 语音对讲 INVITE/ACK/BYE/响应</li>
 * </ul>
 */
@Component
public class SipRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(SipRequestHandler.class);

    /** 无法解析报文告警的限流间隔（毫秒）：同一来源 10s 最多一条。 */
    private static final long UNPARSEABLE_WARN_INTERVAL_MILLIS = 10_000;

    /** 各来源最近一次"无法解析"告警时间戳（限流用，设备数量有限，无需清理）。 */
    private final Map<String, Long> lastUnparseableWarn = new ConcurrentHashMap<>();

    private final SipServerProperties props;
    private final SipChannelHolder channelHolder;
    private final RegisterHandler registerHandler;
    private final DeviceMessageHandler deviceMessageHandler;
    private final AlarmSubscribeHandler alarmSubscribeHandler;
    private final MediaSignalHandler mediaSignalHandler;
    private final VoiceSignalHandler voiceSignalHandler;

    public SipRequestHandler(SipServerProperties props,
                             SipChannelHolder channelHolder,
                             RegisterHandler registerHandler,
                             DeviceMessageHandler deviceMessageHandler,
                             AlarmSubscribeHandler alarmSubscribeHandler,
                             MediaSignalHandler mediaSignalHandler,
                             VoiceSignalHandler voiceSignalHandler) {
        this.props = props;
        this.channelHolder = channelHolder;
        this.registerHandler = registerHandler;
        this.deviceMessageHandler = deviceMessageHandler;
        this.alarmSubscribeHandler = alarmSubscribeHandler;
        this.mediaSignalHandler = mediaSignalHandler;
        this.voiceSignalHandler = voiceSignalHandler;
    }

    /**
     * SIP 报文入口（由 UdpSipServer 的 Netty channelRead0 调用）。
     */
    public void handle(byte[] data, InetSocketAddress sender, ChannelHandlerContext ctx) {
        try {
            // SIP Outbound keep-alive（RFC 5626 §5.1）：设备周期性发送双 CRLF（4 字节
            // \r\n\r\n）探测/NAT 保活。必须回单 CRLF pong 维持双向映射，且不能进入
            // SIP 解析——此前被当作"无法解析的SIP报文: 4 字节"每次 WARN 刷屏。
            // 摄像机注册成功后，服务器可以通过已建立的映射向设备发送 SIP 请求。但如果这条 NAT 映射长时间没有流量，路由器可能会将它清除。
            if (isCrlfKeepAlive(data)) {
                channelHolder.set(ctx.channel());
                SipResponder.sendRaw(ctx.channel(), sender, new byte[] {'\r', '\n'});
                log.debug("[28181] CRLF keep-alive 应答: from={}", sender);
                return;
            }

            SipMessage msg = SipMessage.parse(data);
            if (msg.method().isEmpty()) {
                warnUnparseableThrottled(data, sender);
                return;
            }

            // UDP 无连接：每次报文刷新活跃通道，供主动外发请求（SUBSCRIBE/MESSAGE）使用
            channelHolder.set(ctx.channel());

            // 报文原文留痕：body 以 ISO-8859-1 原样输出（与解析输入一致，GB2312 XML
            // 会显示为转义字符但字节无损）。默认 DEBUG 不输出；需要看设备响应/请求
            // 完整内容（如 INVITE 200 OK 的 SDP）时，将 com.example.sip 调为 DEBUG
            log.debug("[28181] SIP报文原文: from={}\n{}", sender, msg.toRaw());

            String method = msg.method();
            if (method.startsWith("SIP/")) {
                handleResponse(msg, sender);
                return;
            }

            switch (method) {
                case "REGISTER":
                    registerHandler.handle(msg, sender, ctx);
                    break;
                case "MESSAGE":
                    deviceMessageHandler.handle(msg, sender, ctx);
                    break;
                case "INVITE":
                    handleInvite(msg, sender, ctx);
                    break;
                case "ACK":
                    voiceSignalHandler.handleAck(msg, ctx);
                    break;
                case "BYE":
                    handleBye(msg, sender, ctx);
                    break;
                case "OPTIONS":
                    SipResponder.reply(ctx, sender, SipResponseBuilder.ok(msg, props));
                    break;
                case "SUBSCRIBE":
                    alarmSubscribeHandler.handleSubscribe(msg, sender, ctx);
                    break;
                case "NOTIFY":
                    handleNotify(msg, sender, ctx);
                    break;
                default:
                    log.warn("[28181] 未支持的SIP方法: {} from={}", method, sender);
                    SipResponder.reply(ctx, sender, SipResponseBuilder.build(
                            msg, 400, "Bad Request", null, null, props));
            }
        } catch (Exception e) {
            log.error("[28181] SIP报文处理异常: from={}", sender, e);
        }
    }

    /**
     * 是否 SIP Outbound CRLF keep-alive：全部字节均为 CR/LF。
     *
     * <p>双 CRLF（{@code \r\n\r\n}）为 ping，单 CRLF 为 pong，均非 SIP 报文。
     * 只认 CR/LF 字节，避免误判设备栈其它短保活（如 4 字节二进制）。
     */
    private static boolean isCrlfKeepAlive(byte[] data) {
        if (data == null || data.length < 2 || data.length > 4) {
            return false;
        }
        for (byte b : data) {
            if (b != '\r' && b != '\n') {
                return false;
            }
        }
        return true;
    }

    /**
     * 无法解析报文的限流告警：同一来源每 {@link #UNPARSEABLE_WARN_INTERVAL_MILLIS}
     * 最多一条，防止设备杂包/其它保活把日志打满。
     */
    private void warnUnparseableThrottled(byte[] data, InetSocketAddress sender) {
        String key = sender.toString();
        long now = System.currentTimeMillis();
        Long last = lastUnparseableWarn.get(key);
        if (last == null || now - last >= UNPARSEABLE_WARN_INTERVAL_MILLIS) {
            lastUnparseableWarn.put(key, now);
            log.warn("[28181] 无法解析的SIP报文: {} 字节 from={} ({}ms 内同类告警已合并)",
                    data.length, sender, UNPARSEABLE_WARN_INTERVAL_MILLIS);
        }
    }

    // =========================================================
    // INVITE / BYE / NOTIFY / 响应路由
    // =========================================================

    /**
     * 设备 INVITE：按 SDP 媒体类型分流。
     *
     * <p>audio → 语音广播/对讲（设备反向 INVITE，200 OK 通告独占端口）；
     * video → 非常规，拒绝；无 SDP → 语音广播常见形态（GB28181 广播 INVITE 不带 SDP）。
     */
    private void handleInvite(SipMessage msg, InetSocketAddress sender, ChannelHandlerContext ctx) {
        String sdp = msg.getBodyText(java.nio.charset.StandardCharsets.ISO_8859_1);
        String from = SipUtil.extractSipId(msg.getHeader("From"));

        if (sdp != null && sdp.contains("m=audio")) {
            log.info("[28181] 设备INVITE(语音): from={} callId={}", from, msg.getHeader("Call-ID"));
            voiceSignalHandler.handleInvite(msg, ctx, sender);
            return;
        }

        if (sdp != null && sdp.contains("m=video")) {
            mediaSignalHandler.handleInvite(msg, sender, ctx);
            return;
        }

        // 无 SDP：语音广播场景（设备被 MESSAGE 通知后反向 INVITE 平台）
        log.info("[28181] 设备INVITE(无SDP,按语音处理): from={} callId={}",
                from, msg.getHeader("Call-ID"));
        voiceSignalHandler.handleInvite(msg, ctx, sender);
    }

    /** 设备 BYE：语音优先，其次视频；都不匹配回 200 OK 兜底 */
    private void handleBye(SipMessage msg, InetSocketAddress sender, ChannelHandlerContext ctx) {
        if (voiceSignalHandler.handleBye(msg)) {
            SipResponder.reply(ctx, sender, SipResponseBuilder.ok(msg, props));
            return;
        }
        if (mediaSignalHandler.handleBye(msg)) {
            SipResponder.reply(ctx, sender, SipResponseBuilder.ok(msg, props));
            return;
        }
        log.warn("[28181] 未知会话BYE: callId={}", msg.getHeader("Call-ID"));
        SipResponder.reply(ctx, sender, SipResponseBuilder.build(msg, 200, "OK", null, null, props));
    }

    /** 设备 NOTIFY：解析 XML 转交告警处理器，并回 200 OK */
    private void handleNotify(SipMessage msg, InetSocketAddress sender, ChannelHandlerContext ctx) {
        try {
            Document doc = SipUtil.parseXml(msg.getBodyBytes());
            Element root = doc.getDocumentElement();
            alarmSubscribeHandler.handleNotify(msg, root);
        } catch (Exception e) {
            log.warn("[28181] NOTIFY XML 解析失败: {}", e.getMessage());
        }
        SipResponder.reply(ctx, sender, SipResponseBuilder.ok(msg, props));
    }

    /**
     * SIP 响应路由：按 CSeq 方法分发。
     *
     * <p>INVITE 响应 → 语音优先（语音会话优先匹配），否则视频；
     * SUBSCRIBE → 告警订阅；MESSAGE → 语音广播应答。
     */
    private void handleResponse(SipMessage msg, InetSocketAddress sender) {
        String callId = msg.getHeader("Call-ID");
        String cseq = msg.getHeader("CSeq");
        int status = msg.statusCode();

        String cseqMethod = "";
        if (cseq != null) {
            String[] parts = cseq.trim().split("\\s+");
            if (parts.length >= 2) {
                cseqMethod = parts[1].toUpperCase();
            }
        }

        // 设备响应摘要（默认 INFO 可见）：完整报文（含 SDP/XML body）走上面的
        // DEBUG 原文留痕，需要细节时调 com.example.sip 日志级别
        log.info("[28181] 设备响应: {} | callId={} cseq={} status={} from={}",
                msg, callId, cseq, status, sender);

        switch (cseqMethod) {
            case "INVITE":
                if (voiceSignalHandler.handleInviteResponseIfOwned(msg)) {
                    return;
                }
                mediaSignalHandler.handleResponse(msg);
                break;
            case "SUBSCRIBE":
                alarmSubscribeHandler.handleSubscribeResponse(msg, status);
                break;
            case "MESSAGE":
                voiceSignalHandler.handleMessageResponse(msg);
                break;
            default:
                log.debug("[28181] 未路由的SIP响应: callId={} cseq={} status={}",
                        callId, cseq, status);
        }
    }
}
