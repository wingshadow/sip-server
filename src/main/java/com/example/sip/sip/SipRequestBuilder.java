
package com.example.sip.sip;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Device;
import com.example.sip.model.StreamSession;
import com.example.sip.model.VoiceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务器主动发起的 SIP 请求构造器（UAC 侧）。
 *
 * <p>直播：
 * INVITE → 200 OK → ACK → RTP/PS
 *
 * <p>回放：
 * INVITE → 200 OK → ACK → RTP/PS 历史录像流
 */
public final class SipRequestBuilder {
    private static final Logger log = LoggerFactory.getLogger(SipRequestBuilder.class);
    private static final String USER_AGENT = "gb28181-sip-server/1.0";
    private static final Pattern TAG_PATTERN = Pattern.compile("(?i)(?:^|;)\\s*tag=([^;\\s>]+)");
    private static final Random RANDOM = new Random();
    /** MANSCDP XML 统一按 GB18030 编码 */
    private static final Charset GB18030 = Charset.forName("GB18030");

    /**
     * SSRC 域内流水号（第 7~10 位）。
     * GB/T 28181 要求域内产生的媒体流 SSRC 后 4 位互不重复；
     * 进程内单调递增、满 10000 回绕，初始随机以减少重启后与历史流碰撞的概率。
     */
    private static final java.util.concurrent.atomic.AtomicInteger SSRC_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(1000 + RANDOM.nextInt(9000));

    private SipRequestBuilder() {
    }

    /** 生成 32 位十六进制 Call-ID（UUID 去横线） */
    public static String newCallId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 生成 Via branch（RFC 3261 规定的 z9hG4bK 魔数前缀 + 随机串，事务匹配必需） */
    public static String newBranch() {
        return "z9hG4bK" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 生成 8 位随机 From/To tag（dialog 标识） */
    public static String newTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 随机生成 10 位国标 SSRC。
     * 第 1 位 0 表示实时点播，最后一位为偶数。
     */
    public static String newSsrc() {
        return randomSsrc('0');
    }

    /**
     * 随机生成 10 位回放 SSRC。
     * 第 1 位 1 表示回放方向流。
     */
    public static String newPlaybackSsrc() {
        return randomSsrc('1');
    }

    /** 拼装 SSRC：首位方向（0 实时 / 1 回放）+ 8 位随机数字 + 末位偶数 */
    private static String randomSsrc(char first) {
        StringBuilder sb = new StringBuilder(10).append(first);
        for (int i = 0; i < 8; i++) {
            sb.append((char) ('0' + RANDOM.nextInt(10)));
        }
        sb.append((char) ('0' + RANDOM.nextInt(5) * 2));
        return sb.toString();
    }

    /**
     * 生成符合 GB/T 28181（含 2022 版）SDP y= 字段规范的 SSRC（10 位十进制字符串）。
     *
     * <p>协议规则（GB/T 28181 SDP 定义）：第 1 位为历史/实时媒体流标识位，0 为实时、1 为历史；
     * 第 2~6 位取 SIP 监控域 20 位国标 ID 的第 4~8 位作为域标识；第 7~10 位为域内媒体流标识，
     * 须与当前域内已产生的媒体流 SSRC 后 4 位不重复。
     *
     * <p>旧实现 {@link #newSsrc()} 第 2~6 位为纯随机数字，未携带域标识，
     * 部分设备（如海康按 y= 过滤收包）会据此判非法流而静默丢弃 RTP，导致对讲无声。
     *
     * @param first   第 1 位：'0' 实时流 / '1' 历史（回放）流
     * @param domainId SIP 监控域 ID（平台 20 位国标编号）
     */
    public static String gbSsrc(char first, String domainId) {
        String domain = (domainId != null && domainId.length() >= 8)
                ? domainId.substring(3, 8)
                : "00000";
        int seq = SSRC_SEQ.getAndUpdate(v -> (v + 1) % 10000);
        return String.format("%c%s%04d", first, domain, seq);
    }

    /**
     * 从 SIP 头（From/To）中提取 tag 参数。
     */
    public static String extractTag(String header) {
        if (header == null) {
            return null;
        }
        Matcher matcher = TAG_PATTERN.matcher(header);
        return matcher.find() ? matcher.group(1) : null;
    }

    // =========================================================
    // 直播
    // =========================================================

    /**
     * 直播 INVITE，服务器直接接收 RTP/PS。
     */
    public static byte[] invite(SipServerProperties props, Device device, String channelId,
                                String callId, String tag, String branch, int mediaPort, String ssrc) {
        return invite(props, device.getHost(), device.getPort(), channelId,
                callId, tag, branch, mediaPort, ssrc);
    }

    /**
     * 直播 INVITE（会话模式：直接指定设备 host/port，多端口媒体模型使用）。
     */
    public static byte[] invite(SipServerProperties props, String deviceHost, int devicePort,
                                String channelId, String callId, String tag, String branch,
                                int mediaPort, String ssrc) {
        String toUri = deviceUri(deviceHost, devicePort, channelId);
        String fromUri = serverUri(props);
        byte[] body = buildPlaySdp(props, mediaPort, ssrc).getBytes(StandardCharsets.US_ASCII);
        byte[] request = buildInvite(props, toUri, fromUri, callId, tag, branch, channelId, body);
        log.info("[28181] >> 直播完整 INVITE:\n{}", new String(request, StandardCharsets.ISO_8859_1));
        return request;
    }

    /**
     * 直播 INVITE，RTP/PS 直接发送到 ZLMediaKit。
     */
    public static byte[] inviteViaZlm(SipServerProperties props, Device device, String channelId,
                                      String callId, String tag, String branch,
                                      String zlmIp, int zlmPort, String ssrc) {
        String toUri = deviceUri(device, channelId);
        String fromUri = serverUri(props);
        byte[] body = buildZlmPlaySdp(props, zlmIp, zlmPort, ssrc)
                .getBytes(StandardCharsets.US_ASCII);
        return buildInvite(props, toUri, fromUri, callId, tag, branch, channelId, body);
    }

    /**
     * 直播 SDP：服务器作为 RTP 接收方。
     */
    private static String buildPlaySdp(SipServerProperties props, int mediaPort, String ssrc) {
        String serverIp = props.getIp();
        StringBuilder sdp = new StringBuilder(256);
        sdp.append("v=0\r\n");
        sdp.append("o=").append(props.getId()).append(" 0 0 IN IP4 ").append(serverIp).append("\r\n");
        sdp.append("s=Play\r\n");
        sdp.append("c=IN IP4 ").append(serverIp).append("\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("m=video ").append(mediaPort).append(" RTP/AVP 96\r\n");
        sdp.append("a=recvonly\r\n");
        sdp.append("a=rtpmap:96 PS/90000\r\n");
        sdp.append("y=").append(ssrc).append("\r\n");
        sdp.append("f=v/2/5/25/1/2/1\r\n");
        return sdp.toString();
    }

    /**
     * ZLM 直播 SDP。
     */
    private static String buildZlmPlaySdp(SipServerProperties props, String zlmIp,
                                          int zlmPort, String ssrc) {
        StringBuilder sdp = new StringBuilder(256);
        sdp.append("v=0\r\n");
        sdp.append("o=").append(props.getId()).append(" 0 0 IN IP4 ").append(zlmIp).append("\r\n");
        sdp.append("s=Play\r\n");
        sdp.append("c=IN IP4 ").append(zlmIp).append("\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("m=video ").append(zlmPort).append(" RTP/AVP 96\r\n");
        sdp.append("a=recvonly\r\n");
        sdp.append("a=rtpmap:96 PS/90000\r\n");
        sdp.append("y=").append(ssrc).append("\r\n");
        sdp.append("f=v/2/5/25/1/2/1\r\n");
        log.info("buildZlmPlaySdp:{}", sdp.toString());
        return sdp.toString();
    }

    // =========================================================
    // 回放
    // =========================================================

    /**
     * GB/T 28181 回放 INVITE。
     *
     * <p>回放 SDP：
     * s=Playback
     * u=通道ID:0
     * t=开始时间 结束时间
     * a=recvonly
     */
    public static byte[] playbackInvite(SipServerProperties props, Device device, String channelId,
                                        String callId, String tag, String branch, int mediaPort,
                                        String ssrc, long startTime, long endTime) {
        return playbackInvite(props, device.getHost(), device.getPort(), channelId,
                callId, tag, branch, mediaPort, ssrc, startTime, endTime);
    }

    /**
     * GB/T 28181 回放 INVITE（会话模式：直接指定设备 host/port，多端口媒体模型使用）。
     *
     * <p>回放 SDP：
     * s=Playback
     * u=通道ID:0
     * t=开始时间 结束时间
     * a=recvonly
     */
    public static byte[] playbackInvite(SipServerProperties props, String deviceHost, int devicePort,
                                        String channelId, String callId, String tag, String branch,
                                        int mediaPort, String ssrc, long startTime, long endTime) {
        return playbackInvite(props, deviceHost, devicePort, channelId, callId, tag, branch,
                mediaPort, ssrc, startTime, endTime, 0);
    }

    /**
     * GB/T 28181 回放/下载 INVITE，可指定下载倍速。
     *
     * <p>downloadSpeed &gt; 1 时在 SDP 中追加 {@code a=downloadspeed:<n>}
     * （GB/T 28181-2022 下载倍速属性），设备按该倍速推送历史媒体，
     * 下载耗时约为录像时长的 1/n；设备不支持时忽略该属性按 1x 推流。
     */
    public static byte[] playbackInvite(SipServerProperties props, String deviceHost, int devicePort,
                                        String channelId, String callId, String tag, String branch,
                                        int mediaPort, String ssrc, long startTime, long endTime,
                                        int downloadSpeed) {
        String toUri = deviceUri(deviceHost, devicePort, channelId);
        String fromUri = serverUri(props);
        byte[] body = buildPlaybackSdp(props, channelId, mediaPort, ssrc, startTime, endTime,
                        downloadSpeed)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] request = buildInvite(props, toUri, fromUri, callId, tag, branch, channelId, body);
        log.info("[28181] >> 回放完整 INVITE:\n{}", new String(request, StandardCharsets.ISO_8859_1));
        return request;
    }

    /**
     * GB/T 28181 回放 INVITE，RTP/PS 直接发送到 ZLMediaKit。
     */
    public static byte[] playbackInviteViaZlm(SipServerProperties props, Device device, String channelId,
                                              String callId, String tag, String branch,
                                              String zlmIp, int zlmPort, String ssrc,
                                              long startTime, long endTime) {
        String toUri = deviceUri(device, channelId);
        String fromUri = serverUri(props);
        byte[] body = buildZlmPlaybackSdp(props, channelId, zlmIp, zlmPort, ssrc, startTime, endTime)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] request = buildInvite(props, toUri, fromUri, callId, tag, branch, channelId, body);
        log.info("[28181] >> ZLM 回放完整 INVITE:\n{}", new String(request, StandardCharsets.ISO_8859_1));
        return request;
    }

    /**
     * 普通回放 SDP。
     *
     * @param downloadSpeed 下载倍速（&gt;1 时追加 a=downloadspeed，仅下载会话有意义）
     */
    private static String buildPlaybackSdp(SipServerProperties props, String channelId,
                                           int mediaPort, String ssrc,
                                           long startTime, long endTime,
                                           int downloadSpeed) {
        String serverIp = props.getIp();
        StringBuilder sdp = new StringBuilder(320);
        sdp.append("v=0\r\n");
        sdp.append("o=").append(props.getId()).append(" 0 0 IN IP4 ").append(serverIp).append("\r\n");
        sdp.append("s=Playback\r\n");
        sdp.append("u=").append(channelId).append(":0\r\n");
        sdp.append("c=IN IP4 ").append(serverIp).append("\r\n");
        sdp.append("t=").append(startTime).append(' ').append(endTime).append("\r\n");
        sdp.append("m=video ").append(mediaPort).append(" RTP/AVP 96\r\n");
        sdp.append("a=recvonly\r\n");
        sdp.append("a=rtpmap:96 PS/90000\r\n");
        sdp.append("y=").append(ssrc).append("\r\n");
        if (downloadSpeed > 1) {
            // GB/T 28181-2022：下载倍速，设备按此倍速推送历史媒体
            sdp.append("a=downloadspeed:").append(downloadSpeed).append("\r\n");
        }
        sdp.append("f=v/2/5/25/1/2/1\r\n");
        log.info("[28181] >> 回放 SDP:\n{}", sdp);
        return sdp.toString();
    }

    /**
     * ZLM 回放 SDP。
     */
    private static String buildZlmPlaybackSdp(SipServerProperties props, String channelId,
                                              String zlmIp, int zlmPort, String ssrc,
                                              long startTime, long endTime) {
        StringBuilder sdp = new StringBuilder(320);
        sdp.append("v=0\r\n");
        sdp.append("o=").append(props.getId()).append(" 0 0 IN IP4 ").append(zlmIp).append("\r\n");
        sdp.append("s=Playback\r\n");
        sdp.append("u=").append(channelId).append(":0\r\n");
        sdp.append("c=IN IP4 ").append(zlmIp).append("\r\n");
        sdp.append("t=").append(startTime).append(' ').append(endTime).append("\r\n");
        sdp.append("m=video ").append(zlmPort).append(" RTP/AVP 96\r\n");
        sdp.append("a=recvonly\r\n");
        sdp.append("a=rtpmap:96 PS/90000\r\n");
        sdp.append("y=").append(ssrc).append("\r\n");
        sdp.append("f=v/2/5/25/1/2/1\r\n");
        log.info("[28181] >> ZLM 回放 SDP:\n{}", sdp);
        return sdp.toString();
    }

    // =========================================================
    // MANSCDP 通用请求（告警订阅 / 录像查询）
    // =========================================================

    /**
     * 构造 SUBSCRIBE（告警订阅/续订/取消）。
     *
     * @param expires 订阅有效期（秒）；0 表示取消订阅
     */
    public static byte[] subscribe(SipServerProperties props,
                                   String deviceId, String deviceHost, int devicePort,
                                   String callId, String fromTag, String toTag,
                                   long cseq, int expires, String body) {
        String toUri = deviceUri(deviceHost, devicePort, deviceId);
        String fromUri = serverUri(props);

        StringBuilder sb = new StringBuilder(768);
        sb.append("SUBSCRIBE ").append(toUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(':').append(props.getPort())
                .append(";rport;branch=").append(newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=").append(fromTag).append("\r\n");
        sb.append("To: <").append(toUri).append('>');
        if (toTag != null && !toTag.isEmpty()) {
            sb.append(";tag=").append(toTag);
        }
        sb.append("\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(cseq).append(" SUBSCRIBE\r\n");
        sb.append("Contact: <").append(fromUri).append(">\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Event: presence\r\n");
        sb.append("Expires: ").append(expires).append("\r\n");
        sb.append("Content-Type: Application/MANSCDP+xml\r\n");
        sb.append("Content-Length: ").append(bodyBytes(body).length).append("\r\n\r\n");
        return merge(sb, bodyBytes(body));
    }

    /**
     * 构造 MANSCDP MESSAGE（录像查询等）。
     *
     * @param channelId 目标通道（Request-URI 使用通道编号，国标规范）
     */
    public static byte[] message(SipServerProperties props,
                                 String deviceHost, int devicePort, String channelId,
                                 String callId, String cseq, String body) {
        String toUri = deviceUri(deviceHost, devicePort, channelId);
        String fromUri = serverUri(props);

        StringBuilder sb = new StringBuilder(768);
        sb.append("MESSAGE ").append(toUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(':').append(props.getPort())
                .append(";rport;branch=").append(newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=").append(newTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: ").append(cseq).append(" MESSAGE\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Content-Type: Application/MANSCDP+xml\r\n");
        sb.append("Content-Length: ").append(bodyBytes(body).length).append("\r\n\r\n");
        return merge(sb, bodyBytes(body));
    }

    /** MANSCDP 体统一按 GB18030 编码（国标 XML 声明编码） */
    private static byte[] bodyBytes(String body) {
        return body.getBytes(GB18030);
    }

    // =========================================================
    // SIP 公共请求
    // =========================================================

    /**
     * 公共 INVITE 构造。
     */
    private static byte[] buildInvite(SipServerProperties props, String toUri, String fromUri,
                                      String callId, String tag, String branch,
                                      String channelId, byte[] body) {
        StringBuilder sb = new StringBuilder(640);
        sb.append("INVITE ").append(toUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(':').append(props.getPort())
                .append(";rport;branch=").append(branch).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=").append(tag).append("\r\n");
        sb.append("To: <").append(toUri).append(">\r\n");
        sb.append("Call-ID: ").append(callId).append("\r\n");
        sb.append("CSeq: 1 INVITE\r\n");
        sb.append("Contact: <").append(fromUri).append(">\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        // GB/T 28181 Subject：平台ID:0,通道ID:序号。
        // 旧版误写为 通道ID:平台ID,通道ID:0，海康/大华等严格设备直接回 403 Forbidden。
        sb.append("Subject: ").append(props.getId()).append(":0,")
                .append(channelId).append(':').append(subjectSeq()).append("\r\n");
        sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        return merge(sb, body);
    }

    /**
     * 构造 ACK。
     *
     * <p>ACK 必须保持当前 Dialog 的 Call-ID、From-tag、To-tag，
     * 但使用新的 Via branch。
     */
    public static byte[] ack(SipServerProperties props, StreamSession s) {
        StringBuilder sb = transactionHeader(props, "ACK", s);
        sb.append("Content-Length: 0\r\n\r\n");
        byte[] packet = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        log.info("[28181] >> ACK:\n{}", new String(packet, StandardCharsets.ISO_8859_1));
        return packet;
    }

    /**
     * 构造 BYE。
     */
    public static byte[] bye(SipServerProperties props, StreamSession s) {
        String toUri = deviceUri(s.getDeviceHost(), s.getDevicePort(), s.getChannelId());
        String fromUri = serverUri(props);

        StringBuilder sb = new StringBuilder(512);
        sb.append("BYE ").append(toUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(':').append(props.getPort())
                .append(";rport;branch=").append(newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=").append(s.getFromTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">");
        if (s.getToTag() != null && !s.getToTag().isEmpty()) {
            sb.append(";tag=").append(s.getToTag());
        }
        sb.append("\r\n");
        sb.append("Call-ID: ").append(s.getCallId()).append("\r\n");
        sb.append("CSeq: 2 BYE\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        sb.append("Content-Length: 0\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 构造会话内 INFO（直播保活用，空消息体）。
     *
     * <p>RFC 3261 Dialog 内请求：沿用会话的 Call-ID / From-tag / To-tag，
     * 新 Via branch 构成独立事务；设备侧会话超时前收到即可刷新 Dialog 计时。
     *
     * <p>{@code cseq} 必须由调用方按会话单调递增（INVITE=1、ACK=2 之后从 3 起）：
     * 重复使用同一 CSeq 发送同方法请求，严格 UAS 会判为同一请求的重传/序列错误。
     * 与 {@link #bye} 固定 {@code CSeq: 2 BYE} 不冲突——设备侧对 BYE 的
     * CSeq 宽容是既有实测行为。
     */
    public static byte[] info(SipServerProperties props, StreamSession s, long cseq) {
        String fromUri = serverUri(props);
        String toUri = deviceUri(s.getDeviceHost(), s.getDevicePort(), s.getChannelId());

        StringBuilder sb = new StringBuilder(512);
        sb.append("INFO ").append(toUri(s)).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(':').append(props.getPort())
                .append(";rport;branch=").append(newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=").append(s.getFromTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">");
        if (s.getToTag() != null && !s.getToTag().isEmpty()) {
            sb.append(";tag=").append(s.getToTag());
        }
        sb.append("\r\n");
        sb.append("Call-ID: ").append(s.getCallId()).append("\r\n");
        sb.append("CSeq: ").append(cseq).append(" INFO\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        sb.append("Content-Length: 0\r\n\r\n");
        byte[] packet = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        log.info("[28181] >> INFO(保活):\n{}", new String(packet, StandardCharsets.ISO_8859_1));
        return packet;
    }

    // =========================================================
    // MANSRTSP 媒体控制（GB/T 28181-2022，回放/下载会话内 SIP INFO）
    // =========================================================

    /**
     * 构造 SIP INFO：MANSRTSP 媒体控制（回放/下载会话内下发）。
     *
     * <p>Content-Type: {@code Application/MANSRTSP}，消息体为单行 MANSRTSP 指令：
     * <ul>
     *   <li>{@code PLAY range:npt="<start>-<end>"} 播放/定位（start/end 为秒级 epoch，与 SDP t= 一致）</li>
     *   <li>{@code SCALE <speed>} 倍速（如 {@code SCALE 4.0} 即 4 倍速）</li>
     *   <li>{@code PAUSE} / {@code TEARDOWN} 暂停 / 停止</li>
     * </ul>
     * 必须在 Dialog 内发送：沿用会话的 Call-ID / From-tag / To-tag，
     * CSeq 由 {@link StreamSession#nextInfoCseq()} 按会话逐次递增
     * （INVITE=1、ACK=2 之后从 3 起）——同一 Dialog 内重复使用同一
     * CSeq 发送同方法请求，严格 UAS 会判为重传/序列错误。
     */
    public static byte[] mansrtspInfo(SipServerProperties props, StreamSession s,
                                      String mansrtspBody) {
        String toUri = deviceUri(s.getDeviceHost(), s.getDevicePort(), s.getChannelId());
        String fromUri = serverUri(props);

        StringBuilder sb = new StringBuilder(512);
        sb.append("INFO ").append(toUri(s)).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(':').append(props.getPort())
                .append(";rport;branch=").append(newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=").append(s.getFromTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">");
        if (s.getToTag() != null && !s.getToTag().isEmpty()) {
            sb.append(";tag=").append(s.getToTag());
        }
        sb.append("\r\n");
        sb.append("Call-ID: ").append(s.getCallId()).append("\r\n");
        sb.append("CSeq: ").append(s.nextInfoCseq()).append(" INFO\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Content-Type: Application/MANSRTSP\r\n");
        sb.append("Content-Length: ").append(mansrtspBody.length()).append("\r\n\r\n");
        byte[] packet = merge(sb, mansrtspBody.getBytes(StandardCharsets.US_ASCII));
        log.info("[28181] >> INFO(MANSRTSP):\n{}", new String(packet, StandardCharsets.ISO_8859_1));
        return packet;
    }

    /** MANSRTSP PLAY：播放/定位到指定时间范围（秒级 epoch，与 SDP t= 一致）。 */
    public static String mansrtspPlay(long startTime, long endTime) {
        return "PLAY range:npt=\"" + startTime + '-' + endTime + '"';
    }

    /** MANSRTSP SCALE：倍速控制（4.0 = 4 倍速）。 */
    public static String mansrtspScale(double speed) {
        return String.format("SCALE %.1f", speed);
    }

    /** MANSRTSP PAUSE：暂停推流（设备暂停发送 RTP，会话保持）。 */
    public static String mansrtspPause() {
        return "PAUSE";
    }

    // =========================================================
    // 语音对讲 VOICE
    // =========================================================

    /**
     * 语音对讲 INVITE（平台主动，双向音频）。
     *
     * <p>SDP：m=audio &lt;localPort&gt; RTP/AVP 8（PCMA） + a=sendrecv，
     * localPort 为平台为本次对讲独占分配的本地 UDP 端口（多端口模式）。
     *
     * @param announceIp SDP 通告 IP（设备把 RTP 推到该地址）。zlm 模式下由
     *                   调用方传 media.zlm.media-ip，与点播/回放 SDP 同口径；
     *                   local 模式传 sip.server.ip。
     */
    public static byte[] voiceInvite(SipServerProperties props,
                                    VoiceSession session, String announceIp) {
        String toUri = deviceUri(
                session.getDeviceHost(), session.getDevicePort(), session.getChannelId());
        String fromUri = serverUri(props);

        String sdp = buildTalkSdp(props, announceIp, session.getLocalPort(), session.getSsrc());

        StringBuilder sb = new StringBuilder(900);
        sb.append("INVITE ").append(toUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(":")
                .append(props.getPort())
                .append(";rport;branch=").append(session.getBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=")
                .append(session.getPlatformTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">\r\n");
        sb.append("Call-ID: ").append(session.getCallId()).append("\r\n");
        sb.append("CSeq: 1 INVITE\r\n");
        sb.append("Contact: <").append(fromUri).append(">\r\n");
        sb.append("Max-Forwards: 70\r\n");
        // 与 WVP-GB28181-pro SIPCommander.talkStreamCmd/createInviteRequest 对齐：
        // Subject = 通道ID:SSRC,平台ID:0。SSRC 是设备把 RTP 流绑定到本次会话的依据；
        // 旧格式 通道ID:平台ID,1,0 不含 SSRC，部分设备据此把请求按"监听"处理并回 a=sendonly。
        sb.append("Subject: ").append(session.getChannelId())
                .append(":").append(session.getSsrc())
                .append(",").append(props.getId()).append(":0\r\n");
        sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ")
                .append(sdp.getBytes(StandardCharsets.US_ASCII).length).append("\r\n\r\n");
        sb.append(sdp);

        byte[] raw = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        log.info("[28181] >> 语音 INVITE:\n{}", new String(raw, StandardCharsets.ISO_8859_1));
        return raw;
    }

    /**
     * 平台主动语音广播 INVITE：SDP 为 s=Broadcast + a=sendonly（平台只发不收）。
     *
     * <p>与对讲（s=PlayTalk + a=sendrecv）是设备侧两条独立代码路径。
     * 部分设备（含海康 IPC）把对讲 INVITE 误判为点播——200 OK 回 s=Play、
     * a=filesize:0，并向平台端口发 PS 视频流，既不播平台音频也不上行麦克风；
     * 广播语义（平台单向喊话、设备播放）明确，设备侧实现独立，可绕开该误判。
     * 调用方需先发 {@link #broadcastMessage} 通知设备进入广播状态。
     *
     * @param announceIp SDP 通告 IP，口径同 {@link #voiceInvite}。
     */
    public static byte[] broadcastInvite(SipServerProperties props,
                                        VoiceSession session, String announceIp) {
        String toUri = deviceUri(
                session.getDeviceHost(), session.getDevicePort(), session.getChannelId());
        String fromUri = serverUri(props);

        String sdp = buildBroadcastInviteSdp(
                props, announceIp, session.getLocalPort(), session.getSsrc());

        StringBuilder sb = new StringBuilder(900);
        sb.append("INVITE ").append(toUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(":")
                .append(props.getPort())
                .append(";rport;branch=").append(session.getBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=")
                .append(session.getPlatformTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">\r\n");
        sb.append("Call-ID: ").append(session.getCallId()).append("\r\n");
        sb.append("CSeq: 1 INVITE\r\n");
        sb.append("Contact: <").append(fromUri).append(">\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Subject: ").append(session.getChannelId())
                .append(":").append(session.getSsrc())
                .append(",").append(props.getId()).append(":0\r\n");
        sb.append("Content-Type: application/sdp\r\n");
        sb.append("Content-Length: ")
                .append(sdp.getBytes(StandardCharsets.US_ASCII).length).append("\r\n\r\n");
        sb.append(sdp);

        byte[] raw = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        log.info("[28181] >> 语音广播 INVITE:\n{}", new String(raw, StandardCharsets.ISO_8859_1));
        return raw;
    }

    /**
     * 平台主动广播 INVITE 的 SDP：s=Broadcast、a=sendonly、f=v/////a/1/8/1。
     *
     * <p>a=sendonly 明确「平台是发送方、设备只收播」，设备据此打开音频输出。
     * 传输与对讲一致：TCP/RTP/AVP + a=setup:passive + a=connection:new
     * （设备主动向平台发起 TCP 媒体连接，海康/大华 IPC 对讲/广播均如此）。
     * 若设备 200 OK 应答 UDP（RTP/AVP），平台自动回退 UDP 发送。
     *
     * @param announceIp SDP 通告 IP（设备把 RTP 推到该地址），口径同
     *                   {@link #voiceInvite}。
     */
    public static String buildBroadcastInviteSdp(SipServerProperties props,
                                                 String announceIp,
                                                 int localPort, String ssrc) {
        StringBuilder sdp = new StringBuilder(256);
        sdp.append("v=0\r\n");
        sdp.append("o=").append(props.getId()).append(" 0 0 IN IP4 ")
                .append(announceIp).append("\r\n");
        sdp.append("s=Broadcast\r\n");
        sdp.append("c=IN IP4 ").append(announceIp).append("\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("m=audio ").append(localPort).append(" TCP/RTP/AVP 8\r\n");
        sdp.append("a=setup:passive\r\n");
        sdp.append("a=connection:new\r\n");
        sdp.append("a=sendonly\r\n");
        sdp.append("a=rtpmap:8 PCMA/8000\r\n");
        sdp.append("y=").append(ssrc).append("\r\n");
        sdp.append("f=v/////a/1/8/1\r\n");
        return sdp.toString();
    }

    /**
     * 语音对讲 SDP（平台侧）：
     *
     * <pre>
     * v=0
     * o=平台ID 0 0 IN IP4 通告IP
     * s=Talk
     * c=IN IP4 通告IP
     * t=0 0
     * m=audio 本地端口 RTP/AVP 8
     * a=sendrecv
     * a=rtpmap:8 PCMA/8000
     * y=ssrc
     * f=v/////a/1/8/1
     * </pre>
     *
     * <p>UDP 传输（RTP/AVP）：RTP 走设备 200 OK 应答中的 audioIp:audioPort，
     * 设备麦克风音频上行到本会话 UDP 端口。设备应答若为 TCP/RTP/AVP，
     * 平台会自动回退 TCP（见 {@code VoiceService.handleInviteResponse}）。
     *
     * <p>f=v/////a/1/8/1 是纯音频流的媒体参数行（视频段留空），WVP 对讲同样携带；
     * s=Talk 为对讲标准会话名（WVP 同值）。
     *
     * @param announceIp SDP 通告 IP（设备把 RTP 推到该地址），口径同
     *                   {@link #voiceInvite}。
     */
    public static String buildTalkSdp(SipServerProperties props, String announceIp,
                                      int localPort, String ssrc) {
        StringBuilder sdp = new StringBuilder(256);
        sdp.append("v=0\r\n");
        sdp.append("o=").append(props.getId()).append(" 0 0 IN IP4 ")
                .append(announceIp).append("\r\n");
        sdp.append("s=Talk\r\n");
        sdp.append("c=IN IP4 ").append(announceIp).append("\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("m=audio ").append(localPort).append(" RTP/AVP 8\r\n");
        sdp.append("a=sendrecv\r\n");
        sdp.append("a=rtpmap:8 PCMA/8000\r\n");
        sdp.append("y=").append(ssrc).append("\r\n");
        sdp.append("f=v/////a/1/8/1\r\n");
        return sdp.toString();
    }

    /**
     * 语音 ACK（平台主动 INVITE 的三次握手第三步）。
     */
    public static byte[] voiceAck(SipServerProperties props,
                                  VoiceSession session) {
        String toUri = deviceUri(
                session.getDeviceHost(), session.getDevicePort(), session.getChannelId());
        String fromUri = serverUri(props);

        // RFC 3261 §7.1：请求行的 Request-URI 只接受裸 SIP-URI，尖括号（name-addr
        // 形式）仅限 To/From/Contact 等头字段。200 OK 的 Contact 原样是
        // "<sip:xxx@host:port>"，直接拼进请求行会产生 "ACK <sip:...> SIP/2.0"，
        // 严格 UAS 解析请求行失败即静默丢弃 ACK——表现为设备每 ~5s 重传 200 OK、
        // 媒体会话不启动（对讲收包恒 0）。必须剥除尖括号。
        String contact = bareUri(session.getPeerContact());
        String requestUri = contact != null ? contact : toUri;

        StringBuilder sb = new StringBuilder(600);
        sb.append("ACK ").append(requestUri).append(" SIP/2.0\r\n");
        // RFC 3261 §17.1.1.2/§13.2.2.4：2xx 的 ACK 是独立的新事务（INVITE 事务
        // 已被 200 OK 终止，ACK 端到端发送），Via branch 必须新生成唯一值。
        // 若沿用 INVITE 的 branch，UAS 事务层会把 ACK 匹配到已终止的 INVITE
        // 服务器事务而丢弃，表现为设备周期性重传 200 OK 且媒体不启动。
        // （沿用 branch 仅适用于非 2xx 的跳数 ACK，那属于 INVITE 事务本身。）
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(":")
                .append(props.getPort()).append(";rport;branch=")
                .append(SipRequestBuilder.newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=")
                .append(session.getPlatformTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">;tag=")
                .append(session.getDeviceTag()).append("\r\n");
        sb.append("Call-ID: ").append(session.getCallId()).append("\r\n");
        sb.append("CSeq: 1 ACK\r\n");
        sb.append("Contact: <").append(fromUri).append(">\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 语音 BYE（结束对讲）。
     */
    public static byte[] voiceBye(SipServerProperties props,
                                  VoiceSession session) {
        String toUri = deviceUri(
                session.getDeviceHost(), session.getDevicePort(), session.getChannelId());
        String fromUri = serverUri(props);

        // 同 voiceAck：请求行不接受 name-addr 尖括号（RFC 3261 §7.1），
        // BYE 的 Request-URI 也要用裸 URI，否则同样会被设备静默丢弃。
        String contact = bareUri(session.getPeerContact());
        String requestUri = contact != null ? contact : toUri;

        StringBuilder sb = new StringBuilder(600);
        sb.append("BYE ").append(requestUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(":")
                .append(props.getPort()).append(";rport;branch=")
                .append(newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=")
                .append(session.getPlatformTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">;tag=")
                .append(session.getDeviceTag()).append("\r\n");
        sb.append("Call-ID: ").append(session.getCallId()).append("\r\n");
        sb.append("CSeq: 2 BYE\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 语音广播 MESSAGE（MANSCDP：通知设备反向 INVITE 平台）。
     *
     * <p>设备收到后向平台发送不带 SDP 的 INVITE，平台以 200 OK（answer SDP
     * 通告本会话独占端口）应答，之后设备向该端口推流 RTP。
     */
    public static byte[] broadcastMessage(SipServerProperties props,
                                          VoiceSession session,
                                          String targetId) {
        String toUri = deviceUri(
                session.getDeviceHost(), session.getDevicePort(), session.getChannelId());
        String fromUri = serverUri(props);

        String sn = java.util.UUID.randomUUID().toString();
        String body =
                "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n"
                        + "<Notify>\r\n"
                        + "<CmdType>Broadcast</CmdType>\r\n"
                        + "<SN>" + sn + "</SN>\r\n"
                        + "<SourceID>" + props.getId() + "</SourceID>\r\n"
                        + "<TargetID>" + targetId + "</TargetID>\r\n"
                        + "</Notify>\r\n";

        byte[] bodyBytes = body.getBytes(java.nio.charset.Charset.forName("GB18030"));

        StringBuilder sb = new StringBuilder(600);
        sb.append("MESSAGE ").append(toUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(":")
                .append(props.getPort())
                .append(";rport;branch=").append(newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=")
                .append(newTag()).append("\r\n");
        sb.append("To: <").append(toUri).append("\r\n");
        sb.append("Call-ID: ").append(newCallId()).append("\r\n");
        sb.append("CSeq: 20 MESSAGE\r\n");
        sb.append("Content-Type: Application/MANSCDP+xml\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[head.length + bodyBytes.length];
        System.arraycopy(head, 0, raw, 0, head.length);
        System.arraycopy(bodyBytes, 0, raw, head.length, bodyBytes.length);
        log.info("[28181] >> 语音广播 MESSAGE:\n{}", new String(head, StandardCharsets.ISO_8859_1));
        return raw;
    }

    /**
     * 语音广播 answer SDP（设备反向 INVITE 的 200 OK 应答体）。
     *
     * <p>m=audio 通告平台为本会话独占分配的本地端口，a=sendonly（平台只发音频）。
     * 传输协议必须与设备 INVITE 的 offer 一致：设备给 TCP/RTP/AVP 就答
     * TCP/RTP/AVP + a=setup:passive（设备会主动连入平台该端口），
     * 否则答 RTP/AVP（UDP）。
     */
    public static String buildAudioAnswerSdp(SipServerProperties props,
                                             String announceIp,
                                             int localPort, String ssrc,
                                             int payloadType, boolean tcp) {
        StringBuilder sdp = new StringBuilder(256);
        sdp.append("v=0\r\n");
        sdp.append("o=").append(props.getId()).append(" 0 0 IN IP4 ")
                .append(announceIp).append("\r\n");
        sdp.append("s=Play\r\n");
        sdp.append("c=IN IP4 ").append(announceIp).append("\r\n");
        sdp.append("t=0 0\r\n");
        if (tcp) {
            sdp.append("m=audio ").append(localPort).append(" TCP/RTP/AVP ")
                    .append(payloadType).append("\r\n");
            sdp.append("a=setup:passive\r\n");
            sdp.append("a=connection:new\r\n");
        } else {
            sdp.append("m=audio ").append(localPort).append(" RTP/AVP ")
                    .append(payloadType).append("\r\n");
        }
        sdp.append("a=sendonly\r\n");
        sdp.append("a=rtpmap:").append(payloadType).append(" PCMA/8000\r\n");
        sdp.append("y=").append(ssrc).append("\r\n");
        sdp.append("f=v/////a/1/8/1\r\n");
        return sdp.toString();
    }

    /**
     * 构造 ACK 公共头。
     */
    private static StringBuilder transactionHeader(SipServerProperties props, String method,
                                                   StreamSession s) {
        String requestUri = toUri(s);
        String toUri = deviceUri(s.getDeviceHost(), s.getDevicePort(), s.getChannelId());
        String fromUri = serverUri(props);

        StringBuilder sb = new StringBuilder(512);
        sb.append(method).append(' ').append(requestUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(':').append(props.getPort())
                .append(";rport;branch=").append(newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=").append(s.getFromTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">");
        if (s.getToTag() != null && !s.getToTag().isEmpty()) {
            sb.append(";tag=").append(s.getToTag());
        }
        sb.append("\r\n");
        sb.append("Call-ID: ").append(s.getCallId()).append("\r\n");
        sb.append("CSeq: 1 ACK\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        return sb;
    }

    /**
     * 会话目标 URI：优先使用摄像机 200 OK 的 Contact URI。
     */
    private static String toUri(StreamSession s) {
        if (s.getPeerContact() != null && !s.getPeerContact().trim().isEmpty()) {
            String contact = s.getPeerContact().trim();
            if (contact.startsWith("<")) {
                int end = contact.indexOf('>');
                contact = end > 0 ? contact.substring(1, end) : contact.substring(1);
            }
            int semi = contact.indexOf(';');
            if (semi > 0) {
                contact = contact.substring(0, semi);
            }
            return contact;
        }
        return deviceUri(s.getDeviceHost(), s.getDevicePort(), s.getChannelId());
    }

    /** 服务器 SIP URI（sip:服务器ID@IP:端口） */
    private static String serverUri(SipServerProperties props) {
        return "sip:" + props.getId() + "@" + props.getIp() + ":" + props.getPort();
    }

    /**
     * 剥除 name-addr 尖括号与 URI 参数，得到裸 SIP-URI（请求行 Request-URI 用）。
     *
     * <p>头字段中的 URI 是 name-addr 形式：{@code <sip:user@host:port>;tag=...}；
     * 请求行（RFC 3261 §7.1）只接受裸 URI。200 OK 的 Contact 头原样带入
     * voiceAck/voiceBye 会产生 {@code ACK <sip:...> SIP/2.0}，严格 UAS
     * 解析请求行失败即丢弃该报文（设备周期性重传 200 OK 的根因之一）。
     *
     * @param uri 原始 URI 文本（可为 null / 空）
     * @return 裸 URI；输入为空返回 null（调用方回退到默认 toUri）
     */
    private static String bareUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return null;
        }
        String u = uri.trim();
        if (u.startsWith("<")) {
            int end = u.indexOf('>');
            u = end > 0 ? u.substring(1, end) : u.substring(1);
        }
        int semi = u.indexOf(';');
        if (semi > 0) {
            u = u.substring(0, semi);
        }
        return u.trim();
    }

    /**
     * Subject 媒体流序号：秒级时间戳，设备响应时原样带回，保证递增即可。
     */
    private static String subjectSeq() {
        return Long.toString(System.currentTimeMillis() / 1000);
    }

    /** 设备通道 SIP URI（重载入口） */
    private static String deviceUri(Device device, String channelId) {
        return deviceUri(device.getHost(), device.getPort(), channelId);
    }

    /** 拼装通道 SIP URI（sip:通道ID@host:port） */
    private static String deviceUri(String host, int port, String channelId) {
        return "sip:" + channelId + "@" + host + ":" + port;
    }

    /** 头部字节（ISO-8859-1）与 body 字节合并为完整 SIP 报文 */
    private static byte[] merge(StringBuilder head, byte[] body) {
        byte[] header = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }
}

