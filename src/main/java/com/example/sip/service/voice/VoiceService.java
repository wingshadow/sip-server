package com.example.sip.service.voice;

import com.example.sip.config.MediaProperties;
import com.example.sip.config.SipServerProperties;
import com.example.sip.config.VoiceProperties;
import com.example.sip.model.Device;
import com.example.sip.model.SipMessage;
import com.example.sip.model.VoiceSession;
import com.example.sip.netty.UdpSipServer;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.service.audio.G711a;
import com.example.sip.sip.SdpParser.SdpInfo;
import com.example.sip.sip.SdpParser;
import com.example.sip.sip.SipRequestBuilder;
import com.example.sip.sip.SipResponseBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 统一语音对讲服务（多端口模型）：语音广播 + 双向对讲。
 *
 * <p>合并原 VoiceBroadcastService（广播）与 VoiceTalkService（对讲）：
 * <ul>
 *   <li><b>广播（默认）</b>：平台发 Broadcast MESSAGE → 设备反向 INVITE
 *       → 平台 200 OK（answer SDP 通告本会话独占端口）→ 设备 ACK → TALKING；</li>
 *   <li><b>对讲（startTalk）</b>：平台主动 INVITE（SDP 通告本会话独占端口）
 *       → 设备 200 OK → 平台 ACK → TALKING；</li>
 *   <li><b>设备直呼</b>：设备主动 INVITE（无前置会话）→ 平台 200 OK → TALKING。</li>
 * </ul>
 *
 * <p>媒体链路（双向 PCMA/8kHz）：
 * <ul>
 *   <li>上行：浏览器 PCM → G.711A → 160字节/20ms → RTP → 设备；</li>
 *   <li>下行：设备 RTP → G.711A → PCM → WebSocket 二进制帧 → 浏览器。</li>
 * </ul>
 *
 * <p>多端口：每个会话独占一个本地 UDP 端口（VoiceRtpEndpoint 绑定独立 Socket），
 * 支持多台设备同时对讲。
 *
 * <p><b>与媒体模式（local/zlm）的关系</b>：对讲媒体不经过 MediaService /
 * ZLMediaKit，始终是「浏览器 ⇄ 平台 ⇄ 设备」的平台直连 RTP，因此 zlm 模式下
 * 开箱即用、无需任何 ZLM 参与（ZLM 的 RTP Server 是收流方，无法向设备主动
 * 发起 GB28181 对讲 RTP）。模式差异仅在 SDP 通告 IP：zlm 模式取
 * {@code media.zlm.media-ip}（与点播/回放同一设备可达口径），local 模式取
 * {@code sip.server.ip}，见 {@link #announceIp()}。
 */
@Slf4j
@Service
public class VoiceService {

    /** 广播 MESSAGE 后等待设备 INVITE 的超时（毫秒） */
    private static final long BROADCAST_WAIT_MILLIS = 60_000;

    /** INVITE 等待 200 OK 超时（毫秒） */
    private static final long INVITE_TIMEOUT_MILLIS = 10_000;

    /** RTP 发送帧间隔（毫秒）：20ms/160 字节 PCMA */
    private static final long RTP_INTERVAL_MILLIS = 20;

    /**
     * RTP 双向统计日志间隔（毫秒）。
     *
     * <p>无抓包环境下判定媒体链路的唯一日志证据：
     * <ul>
     *   <li>{@code 已发送} 持续增长 → 平台→设备 RTP 持续存在（目标即 200 OK 协商地址）；</li>
     *   <li>{@code 收包} 持续为 0 → 设备未往本会话端口发任何 UDP（设备麦克风/SDP 问题）；</li>
     *   <li>{@code 收包} 增长但无"收到设备RTP首包"日志 → 收到的是非 RTP 杂包。</li>
     * </ul>
     */
    private static final long RTP_STAT_INTERVAL_MILLIS = 5_000;

    private final SipServerProperties props;
    private final MediaProperties mediaProps;
    private final VoiceProperties voiceProps;
    private final DeviceRegistry registry;
    private final UdpSipServer sipServer;
    private final VoiceRtpEndpoint rtpEndpoint;

    /** 会话键（deviceId:channelId）→ 会话 */
    private final Map<String, VoiceSession> sessions = new ConcurrentHashMap<>();

    /** 会话键 → WebSocket 集合（浏览器下行通道） */
    private final Map<String, Map<String, WebSocketSession>> webSockets = new ConcurrentHashMap<>();

    /** 超时任务：会话键 → future */
    private final Map<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    /** 发送线程：会话键 → thread */
    private final Map<String, Thread> senderThreads = new ConcurrentHashMap<>();

    /**
     * 超时清理调度池（2 线程）。
     *
     * <p>显式 {@link ScheduledThreadPoolExecutor}：一次性任务取消即出队，
     * 会话频繁创建/取消时不在 DelayedWorkQueue 残留到期条目。
     */
    private final ScheduledThreadPoolExecutor timeoutScheduler =
            new ScheduledThreadPoolExecutor(2, r -> {
                Thread t = new Thread(r, "voice-timeout");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean shutdown = false;

    public VoiceService(SipServerProperties props,
                        MediaProperties mediaProps,
                        VoiceProperties voiceProps,
                        DeviceRegistry registry,
                        UdpSipServer sipServer,
                        VoiceRtpEndpoint rtpEndpoint) {
        this.props = props;
        this.mediaProps = mediaProps;
        this.voiceProps = voiceProps;
        this.registry = registry;
        this.sipServer = sipServer;
        this.rtpEndpoint = rtpEndpoint;

        rtpEndpoint.setListener(this::onDeviceAudio);
        checkPortOverlap();
        // 一次性任务取消即出队：见 timeoutScheduler 字段注释
        timeoutScheduler.setRemoveOnCancelPolicy(true);
        log.info("[VOICE] 统一语音服务初始化: enabled={} 媒体模式={} 通告IP={} 端口池=[{}..{}]",
                voiceProps.isEnabled(),
                mediaProps.isZlm() ? "zlm（ZLMediaKit，对讲不经过 ZLM，平台直连 RTP）" : "local（多端口 RTP）",
                announceIp(),
                voiceProps.getPortPoolStart(), voiceProps.getPortPoolEnd());
    }

    /**
     * 语音 SDP 的通告 IP：设备把 RTP 推到的地址。
     *
     * <p>zlm 模式优先取 {@code media.zlm.media-ip}——与点播/回放/下载 SDP
     * 同一口径，是设备侧已验证可达的媒体地址；未配置（为空）时回退
     * {@code sip.server.ip}。local 模式始终用 {@code sip.server.ip}。
     *
     * <p>两者分离的意义：平台可能存在多网卡/NAT 场景（SIP 走内网地址、
     * 媒体走映射地址），zlm 部署下 media-ip 才是设备可达的那个。
     */
    private String announceIp() {
        if (mediaProps.isZlm()) {
            String mediaIp = mediaProps.getZlm().getMediaIp();
            if (mediaIp != null && !mediaIp.isBlank()) {
                return mediaIp;
            }
        }
        return props.getIp();
    }

    /**
     * 端口防重叠校验（启动时一次）。
     *
     * <p>语音池（sip.voice.port-pool-*，默认 30100–30150）不得与当前
     * 媒体模式占用的端口重叠：
     * <ul>
     *   <li>zlm 模式：ZLM 固定 RTP 端口（media.zlm.rtp-port，默认 30002）；</li>
     *   <li>local 模式：媒体端口池（media.local.port-pool-*，默认 30000–30099）。</li>
     * </ul>
     * 重叠时语音会话绑定/设备推流必失败其一，此处显式报错暴露配置问题。
     */
    private void checkPortOverlap() {
        int vs = voiceProps.getPortPoolStart();
        int ve = voiceProps.getPortPoolEnd();
        if (ve < vs) {
            log.error("[VOICE] 端口池配置非法: [{}-{}]（start > end）", vs, ve);
            return;
        }
        if (mediaProps.isZlm()) {
            int rtpPort = mediaProps.getZlm().getRtpPort();
            if (rtpPort >= vs && rtpPort <= ve) {
                log.error("[VOICE] 端口配置冲突: ZLM固定RTP端口 {} 落在语音端口池 [{}-{}] 内，"
                                + "设备对讲推流将与 ZLM 收流互窜，请调整 sip.voice.port-pool-*",
                        rtpPort, vs, ve);
            }
        } else {
            int ms = mediaProps.getLocal().getPortPoolStart();
            int me = mediaProps.getLocal().getPortPoolEnd();
            if (!(ve < ms || vs > me)) {
                log.error("[VOICE] 端口配置冲突: 语音端口池 [{}-{}] 与 local 媒体端口池 [{}-{}] 重叠，"
                                + "请调整任一侧端口范围",
                        vs, ve, ms, me);
            }
        }
    }

    // =========================================================
    // 广播（默认入口）
    // =========================================================

    /**
     * 发起语音广播：通知设备反向 INVITE 平台。
     *
     * <p>若已有会话则先停止旧会话。
     */
    public VoiceSession start(String deviceId, String channelId) {
        if (!voiceProps.isEnabled()) {
            throw new IllegalStateException("语音对讲未启用");
        }

        // 广播时 channelId 归一化为 deviceId：
        // 与 WebSocket 注册键（deviceId:deviceId）及设备反向 INVITE 的通道匹配保持一致
        String broadcastChannelId =
                (channelId == null || channelId.isBlank()) ? deviceId : channelId;

        synchronized (lockOf(key(deviceId, broadcastChannelId))) {
            VoiceSession old = sessions.get(key(deviceId, broadcastChannelId));
            if (old != null) {
                doStop(old, "重新开始");
            }

            Device d = registry.get(deviceId);
            if (d == null) {
                throw new IllegalStateException("设备未注册: " + deviceId);
            }

            int port = rtpEndpoint.acquirePort();
            if (port < 0) {
                throw new IllegalStateException("语音端口池耗尽，无法发起对讲");
            }

            VoiceSession s = new VoiceSession(
                    deviceId, broadcastChannelId, d.getHost(), d.getPort(), port);
            s.setCallId(SipRequestBuilder.newCallId());
            s.setSsrc(SipRequestBuilder.gbSsrc('0', props.getId()));
            s.setPlatformTag(SipRequestBuilder.newTag());
            s.setState(VoiceSession.State.BROADCASTING);
            s.setBroadcast(true);

            sessions.put(s.getKey(), s);

            if (!rtpEndpoint.bind(s)) {
                sessions.remove(s.getKey(), s);
                throw new IllegalStateException("语音 RTP 端口绑定失败: " + port);
            }

            try {
                String msgCallId = SipRequestBuilder.newCallId();
                s.setBroadcastMessageCallId(msgCallId);
                sipServer.send(
                        new InetSocketAddress(d.getHost(), d.getPort()),
                        SipRequestBuilder.broadcastMessage(props, s, broadcastChannelId, msgCallId));
                scheduleTimeout(s, BROADCAST_WAIT_MILLIS, "等待设备INVITE超时");
                log.info("[VOICE] 语音广播已发起: key={} port={}", s.getKey(), port);
            } catch (Exception e) {
                log.error("[VOICE] 广播 MESSAGE 发送失败: key={}", s.getKey(), e);
                doStop(s, "广播发送失败");
                throw new IllegalStateException("广播 MESSAGE 发送失败", e);
            }

            return s;
        }
    }

    // =========================================================
    // 对讲（平台主动 INVITE）
    // =========================================================

    /**
     * 发起双向对讲：平台主动 INVITE 设备。
     */
    public VoiceSession startTalk(String deviceId, String channelId) {
        if (!voiceProps.isEnabled()) {
            throw new IllegalStateException("语音对讲未启用");
        }

        synchronized (lockOf(key(deviceId, channelId))) {
            VoiceSession old = sessions.get(key(deviceId, channelId));
            if (old != null) {
                doStop(old, "重新开始");
            }

            Device d = registry.get(deviceId);
            if (d == null) {
                throw new IllegalStateException("设备未注册: " + deviceId);
            }

            int port = rtpEndpoint.acquirePort();
            if (port < 0) {
                throw new IllegalStateException("语音端口池耗尽，无法发起对讲");
            }

            VoiceSession s = new VoiceSession(
                    deviceId, channelId, d.getHost(), d.getPort(), port);
            s.setCallId(SipRequestBuilder.newCallId());
            s.setSsrc(SipRequestBuilder.gbSsrc('0', props.getId()));
            s.setPlatformTag(SipRequestBuilder.newTag());
            s.setBranch(SipRequestBuilder.newBranch());
            s.setState(VoiceSession.State.INVITED);
            s.setBroadcast(false);

            sessions.put(s.getKey(), s);

            if (!rtpEndpoint.bind(s)) {
                sessions.remove(s.getKey(), s);
                throw new IllegalStateException("语音 RTP 端口绑定失败: " + port);
            }

            try {
                sipServer.send(
                        new InetSocketAddress(d.getHost(), d.getPort()),
                        SipRequestBuilder.voiceInvite(props, s, announceIp()));
                scheduleTimeout(s, INVITE_TIMEOUT_MILLIS, "INVITE无应答");
                log.info("[VOICE] 对讲INVITE已发送: key={} port={} announceIp={}",
                        s.getKey(), port, announceIp());
            } catch (Exception e) {
                log.error("[VOICE] 对讲 INVITE 发送失败: key={}", s.getKey(), e);
                doStop(s, "INVITE发送失败");
                throw new IllegalStateException("对讲 INVITE 发送失败", e);
            }

            return s;
        }
    }

    // =========================================================
    // 平台主动广播（MESSAGE 通知 + s=Broadcast INVITE，单向喊话）
    // =========================================================

    /**
     * 发起平台主动语音广播：先发 Broadcast MESSAGE 通知设备进入广播状态，
     * 再发 s=Broadcast/a=sendonly 的 INVITE。设备 200 OK → 平台 ACK →
     * 复用对讲的 RTP 发送循环把 WebSocket 上行音频播到设备。
     *
     * <p>与 {@link #startTalk}（s=Talk 双向对讲）是设备侧两条路径：
     * 部分设备把对讲 INVITE 误判为点播（200 OK 回 s=Play、不发上行、不播音频），
     * 广播语义明确，可绕开该误判。适用于「平台喊话、设备播放」场景。
     * 两者 SDP 均提供 TCP/RTP/AVP + a=setup:passive（设备主动连入平台），
     * 设备应答 UDP 时自动回退 UDP。
     */
    public VoiceSession startBroadcastTalk(String deviceId, String channelId) {
        if (!voiceProps.isEnabled()) {
            throw new IllegalStateException("语音对讲未启用");
        }

        synchronized (lockOf(key(deviceId, channelId))) {
            VoiceSession old = sessions.get(key(deviceId, channelId));
            if (old != null) {
                doStop(old, "重新开始");
            }

            Device d = registry.get(deviceId);
            if (d == null) {
                throw new IllegalStateException("设备未注册: " + deviceId);
            }

            int port = rtpEndpoint.acquirePort();
            if (port < 0) {
                throw new IllegalStateException("语音端口池耗尽，无法发起广播");
            }

            VoiceSession s = new VoiceSession(
                    deviceId, channelId, d.getHost(), d.getPort(), port);
            s.setCallId(SipRequestBuilder.newCallId());
            s.setSsrc(SipRequestBuilder.gbSsrc('0', props.getId()));
            s.setPlatformTag(SipRequestBuilder.newTag());
            s.setBranch(SipRequestBuilder.newBranch());
            s.setState(VoiceSession.State.INVITED);
            // 注意：保持 broadcast=false——本路径是平台主动 INVITE，
            // 停止时走 voiceBye 分支（与对讲一致），而非设备反向 INVITE 的广播分支。
            s.setBroadcast(false);

            sessions.put(s.getKey(), s);

            if (!rtpEndpoint.bind(s)) {
                sessions.remove(s.getKey(), s);
                throw new IllegalStateException("语音 RTP 端口绑定失败: " + port);
            }

            try {
                // GB/T 28181 广播前置通知：设备据此进入广播接收状态
                String msgCallId = SipRequestBuilder.newCallId();
                s.setBroadcastMessageCallId(msgCallId);
                sipServer.send(
                        new InetSocketAddress(d.getHost(), d.getPort()),
                        SipRequestBuilder.broadcastMessage(props, s, channelId, msgCallId));
                log.info("[VOICE] 广播MESSAGE已发送: key={} msgCallId={}", s.getKey(), msgCallId);

                sipServer.send(
                        new InetSocketAddress(d.getHost(), d.getPort()),
                        SipRequestBuilder.broadcastInvite(props, s, announceIp()));
                scheduleTimeout(s, INVITE_TIMEOUT_MILLIS, "INVITE无应答");
                log.info("[VOICE] 广播INVITE已发送: key={} port={}", s.getKey(), port);
            } catch (Exception e) {
                log.error("[VOICE] 广播 INVITE 发送失败: key={}", s.getKey(), e);
                doStop(s, "INVITE发送失败");
                throw new IllegalStateException("广播 INVITE 发送失败", e);
            }

            return s;
        }
    }

    // =========================================================
    // 停止
    // =========================================================

    /** 停止对讲会话（发 BYE + 释放端口 + 关闭 WebSocket）。 */
    public void stop(String deviceId, String channelId) {
        VoiceSession s = sessions.get(key(deviceId, channelId));
        if (s != null) {
            doStop(s, "用户停止");
        }
    }

    private void doStop(VoiceSession s, String reason) {
        doStop(s, reason, true);
    }

    /**
     * 停止语音会话并释放资源。
     *
     * @param sendBye 是否回发 BYE。设备已主动 BYE 时不得再回发（与 1.0
     *                stopInternal 的 {@code reason != DEVICE_BYE} 判定一致）。
     */
    private void doStop(VoiceSession s, String reason, boolean sendBye) {
        if (s == null) {
            return;
        }

        sessions.remove(s.getKey(), s);
        cancelTimeout(s.getKey());

        if (sendBye) {
            if (s.getState() == VoiceSession.State.TALKING
                    || s.getState() == VoiceSession.State.INVITED) {
                // 平台主动 INVITE 的会话：直接 BYE
                try {
                    sipServer.send(
                            new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                            SipRequestBuilder.voiceBye(props, s));
                } catch (Exception e) {
                    log.warn("[VOICE] BYE 发送失败: key={} err={}", s.getKey(), e.getMessage());
                }
            } else if (s.getState() == VoiceSession.State.BROADCASTING
                    && s.getBroadcastCallId() != null) {
                // 广播流程已建立 dialog：BYE 使用设备侧 Call-ID
                try {
                    sipServer.send(
                            new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                            buildBroadcastBye(s));
                } catch (Exception e) {
                    log.warn("[VOICE] 广播BYE发送失败: key={} err={}", s.getKey(), e.getMessage());
                }
            }
        }

        s.setState(VoiceSession.State.STOPPED);
        s.setStopReason(reason);
        rtpEndpoint.release(s);
        closeWebSockets(s, reason);
        log.info("[VOICE] 会话已停止: key={} reason={} packetsSent={}",
                s.getKey(), reason, s.getPacketsSent());
    }

    /** 广播流程 BYE：dialog 为设备发起（Call-ID 来自设备 INVITE）。 */
    private byte[] buildBroadcastBye(VoiceSession s) {
        String toUri = "sip:" + s.getDeviceId() + "@" + s.getDeviceHost()
                + ":" + s.getDevicePort();
        String fromUri = "sip:" + props.getId() + "@" + props.getIp()
                + ":" + props.getPort();

        StringBuilder sb = new StringBuilder(600);
        sb.append("BYE ").append(toUri).append(" SIP/2.0\r\n");
        sb.append("Via: SIP/2.0/UDP ").append(props.getIp()).append(":")
                .append(props.getPort()).append(";rport;branch=")
                .append(SipRequestBuilder.newBranch()).append("\r\n");
        sb.append("From: <").append(fromUri).append(">;tag=")
                .append(s.getPlatformTag()).append("\r\n");
        sb.append("To: <").append(toUri).append(">;tag=")
                .append(s.getDeviceTag()).append("\r\n");
        sb.append("Call-ID: ").append(s.getBroadcastCallId()).append("\r\n");
        sb.append("CSeq: 2 BYE\r\n");
        sb.append("Max-Forwards: 70\r\n");
        sb.append("Content-Length: 0\r\n\r\n");

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // =========================================================
    // 上行音频（浏览器 → 设备）
    // =========================================================

    /**
     * 接收浏览器 PCM（8kHz/16bit/LE），G.711A 编码后进入发送缓冲。
     */
    public void pushAudio(String deviceId, String channelId, byte[] pcm) {
        VoiceSession s = sessions.get(key(deviceId, channelId));
        if (s == null || s.getState() != VoiceSession.State.TALKING) {
            return;
        }
        if (pcm == null || pcm.length == 0) {
            return;
        }

        // 诊断：周期性统计上行 PCM 峰值，验证浏览器 mic 是否真的采集到声音
        // （peak≈0 说明麦克风静音/无权限，设备收到的将是指令正确的"数字静音"）
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (v < 0) {
                v = -v;
            }
            if (v > peak) {
                peak = v;
            }
        }
        if (s.noteUpstreamFrame(peak)) {
            log.info("[VOICE] 上行音频电平: key={} peak={} (0=数字静音)",
                    s.getKey(), s.takeUpstreamPeak());
        }

        byte[] alaw = G711a.encode(pcm);
        s.offerAlaw(alaw);
    }

    /** 启动发送线程（TALKING 时调用，每 20ms 发一帧 PCMA）。 */
    private void startSender(VoiceSession s) {
        Thread t = new Thread(() -> sendLoop(s), "voice-send-" + s.getKey());
        t.setDaemon(true);
        senderThreads.put(s.getKey(), t);
        t.start();
    }

    private void sendLoop(VoiceSession s) {
        log.info("[VOICE] RTP发送线程启动: key={} port={}", s.getKey(), s.getLocalPort());

        long next = System.currentTimeMillis();
        long lastStat = next;
        long realFrames = 0;
        long silenceFrames = 0;
        while (!shutdown && s.getState() == VoiceSession.State.TALKING) {
            try {
                byte[] frame = s.pollAlawFrame(160);
                if (frame == null) {
                    frame = VoiceSession.createSilenceFrame();
                    silenceFrames++;
                } else {
                    realFrames++;
                }

                byte[] rtp = buildRtpPacket(s, frame);
                if (rtpEndpoint.send(s, rtp)) {
                    s.incPacketsSent();
                }

                // 周期统计：平台→设备持续发送证据 + 设备→平台收包探针
                long now = System.currentTimeMillis();
                if (now - lastStat >= RTP_STAT_INTERVAL_MILLIS) {
                    lastStat = now;
                    long total = realFrames + silenceFrames;
                    int realPct = total > 0 ? (int) (realFrames * 100 / total) : 0;
                    log.info("[VOICE] RTP统计: key={} 已发送={} 上行缓冲={}B 目标={}:{} 收包={} 真帧={} 静音帧={} 真帧占比={}% tcp={}",
                            s.getKey(), s.getPacketsSent(), s.getAudioBufferSize(),
                            s.getAudioIp(), s.getAudioPort(),
                            rtpEndpoint.receivedCount(s),
                            realFrames, silenceFrames, realPct, s.isTcp());
                    realFrames = 0;
                    silenceFrames = 0;
                }

                next += RTP_INTERVAL_MILLIS;
                long sleep = next - System.currentTimeMillis();
                if (sleep > 0) {
                    Thread.sleep(sleep);
                } else {
                    next = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (s.getState() == VoiceSession.State.TALKING) {
                    log.warn("[VOICE] RTP发送异常: key={} err={}",
                            s.getKey(), e.getMessage());
                }
                break;
            }
        }

        senderThreads.remove(s.getKey());
        log.info("[VOICE] RTP发送线程退出: key={} packetsSent={}",
                s.getKey(), s.getPacketsSent());
    }

    /** 构造 RTP 包：12 字节固定头 + PCMA 载荷。 */
    private byte[] buildRtpPacket(VoiceSession s, byte[] payload) {
        int pt = s.getPayloadType() > 0 ? s.getPayloadType() : 8;
        byte[] pkt = new byte[12 + payload.length];

        pkt[0] = (byte) 0x80;                       // V=2, P=0, X=0, CC=0
        pkt[1] = (byte) (pt & 0x7F);                // M=0, PT
        int seq = s.nextSeq();
        pkt[2] = (byte) ((seq >> 8) & 0xFF);
        pkt[3] = (byte) (seq & 0xFF);
        int ts = s.nextTimestamp();
        pkt[4] = (byte) ((ts >> 24) & 0xFF);
        pkt[5] = (byte) ((ts >> 16) & 0xFF);
        pkt[6] = (byte) ((ts >> 8) & 0xFF);
        pkt[7] = (byte) (ts & 0xFF);

        try {
            long ssrc = Long.parseLong(s.getSsrc());
            pkt[8] = (byte) ((ssrc >> 24) & 0xFF);
            pkt[9] = (byte) ((ssrc >> 16) & 0xFF);
            pkt[10] = (byte) ((ssrc >> 8) & 0xFF);
            pkt[11] = (byte) (ssrc & 0xFF);
        } catch (Exception ignore) {
            // SSRC 非数字时置 0（罕见）
        }

        System.arraycopy(payload, 0, pkt, 12, payload.length);
        return pkt;
    }

    // =========================================================
    // 下行音频（设备 → 浏览器）
    // =========================================================

    /** 设备 RTP → G.711A 解码 → WebSocket 二进制帧。 */
    private void onDeviceAudio(VoiceSession s, String remoteIp, int remotePort, byte[] rtp) {
        if (s.getState() != VoiceSession.State.TALKING || rtp.length <= 12) {
            return;
        }

        // 首包日志：验证设备是否在往回发 RTP、源端口与 RTP 头 SSRC
        if (s.markFirstDeviceRtp()) {
            long ssrc = ((long) (rtp[8] & 0xFF) << 24)
                    | ((rtp[9] & 0xFF) << 16)
                    | ((rtp[10] & 0xFF) << 8)
                    | (rtp[11] & 0xFF);
            log.info("[VOICE] 收到设备RTP首包: key={} src={}:{} len={} ssrc={}",
                    s.getKey(), remoteIp, remotePort, rtp.length,
                    String.format("%08x", ssrc));
        }

        if (s.getAudioIp() == null && remotePort > 0) {
            // SDP 未协商到有效地址时的兜底：从设备 RTP 源地址学习
            // （设备音频发送端口通常即其 RTP 接收端口）
            s.setAudioIp(remoteIp);
            s.setAudioPort(remotePort);
            log.info("[VOICE] 设备音频源已登记(RTP学习): key={} src={}:{}",
                    s.getKey(), remoteIp, remotePort);
        }

        byte[] alaw = new byte[rtp.length - 12];
        System.arraycopy(rtp, 12, alaw, 0, alaw.length);

        byte[] pcm = G711a.decode(alaw);

        // 下行设备音频诊断：按时间统计（设备 TCP 上行包率可能极低，按帧计数不触发）
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)));
            if (v > peak) {
                peak = v;
            }
        }
        s.noteDownstreamFrame(peak);
        long now = System.currentTimeMillis();
        if (s.dueDownstreamStat(now, 2000)) {
            s.markDownstreamStat(now);
            int[] st = s.takeDownstreamStat();
            log.info("[VOICE] 下行设备音频统计: key={} 解码包={} 解码peak={} (0=数字静音)",
                    s.getKey(), st[0], st[1]);
        }

        s.offerIncomingPcm(pcm);
        broadcastPcm(s, pcm);
    }

    /** 向会话的全部 WebSocket 连接推送 PCM。 */
    private void broadcastPcm(VoiceSession s, byte[] pcm) {
        Map<String, WebSocketSession> wsMap = webSockets.get(s.getKey());
        if (wsMap == null || wsMap.isEmpty()) {
            return;
        }

        BinaryMessage msg = new BinaryMessage(ByteBuffer.wrap(pcm));
        for (WebSocketSession ws : new ArrayList<>(wsMap.values())) {
            if (!ws.isOpen()) {
                continue;
            }
            try {
                synchronized (ws) {
                    ws.sendMessage(msg);
                }
            } catch (IOException e) {
                log.warn("[VOICE] WebSocket推送失败: key={} ws={} err={}",
                        s.getKey(), ws.getId(), e.getMessage());
            }
        }
    }

    // =========================================================
    // 设备侧 SIP 消息
    // =========================================================

    /**
     * 设备反向 INVITE（广播流程或设备直呼）。
     *
     * @return true=已处理（会话存在或已新建）；false=无匹配会话且无法处理
     */
    public boolean handleDeviceInvite(SipMessage msg, SdpInfo sdpInfo,
                                      InetSocketAddress sender) {
        String toHeader = msg.getHeader("To");
        String channelId = extractChannelId(toHeader);
        if (channelId == null || channelId.isEmpty()) {
            log.warn("[VOICE] 设备INVITE无法解析通道ID: to={}", toHeader);
            return false;
        }

        // 广播会话按 deviceId:channelId 存储；设备直呼时 deviceId 未知，用 sender:channelId
        VoiceSession s = findSessionForInvite(channelId, sender);

        if (s == null) {
            // 设备直呼：新建会话
            log.info("[VOICE] 设备直呼INVITE: channel={} src={}", channelId, sender);
            int port = rtpEndpoint.acquirePort();
            if (port < 0) {
                log.error("[VOICE] 语音端口池耗尽，拒绝设备直呼");
                return false;
            }
            s = new VoiceSession(channelId, channelId,
                    sender.getHostString(), sender.getPort(), port);
            s.setSsrc(SipRequestBuilder.gbSsrc('0', props.getId()));
            s.setPlatformTag(SipRequestBuilder.newTag());
            sessions.put(s.getKey(), s);
            if (!rtpEndpoint.bind(s)) {
                sessions.remove(s.getKey(), s);
                return false;
            }
        } else {
            log.info("[VOICE] 广播设备INVITE到达: key={}", s.getKey());
            cancelTimeout(s.getKey());
        }

        // 记录 dialog 信息
        s.setBroadcastCallId(msg.getHeader("Call-ID"));
        String fromTag = extractTag(msg.getHeader("From"));
        s.setDeviceTag(fromTag);

        if (sdpInfo != null) {
            s.setPayloadType(sdpInfo.pcmaPayloadType > 0 ? sdpInfo.pcmaPayloadType : 8);
            // 设备 INVITE 的传输以 offer 为准：设备给 TCP/RTP/AVP 就答 TCP
            // （设备随后主动连入平台该端口），否则答 UDP。
            s.setTcp(sdpInfo.isTcp());
            // 与 1.0 一致：SSRC 严格取 SDP y= 解析值（audioFormat 是 "PCMA" 格式串，不是 SSRC）
            if (sdpInfo.ssrc != null && !sdpInfo.ssrc.isBlank()) {
                s.setSsrc(sdpInfo.ssrc);
            }
            // 设备主动 INVITE：SDP 协商平台→设备的 RTP 目标地址。
            // 规则：优先 m=audio 媒体级 c=（audioIp），缺失回退会话级 c=（sessionIp）；
            // 端口取 m=audio 行。0.0.0.0 / 端口 0 视为未协商，留给 RTP 源地址学习兜底。
            String peerIp = (sdpInfo.audioIp != null && !sdpInfo.audioIp.isBlank())
                    ? sdpInfo.audioIp : sdpInfo.sessionIp;
            int peerPort = sdpInfo.audioPort;
            if (isValidRtpTarget(peerIp, peerPort)) {
                s.setAudioIp(peerIp);
                s.setAudioPort(peerPort);
                log.info("[VOICE] 设备INVITE协商RTP目标: key={} audioIp={} audioPort={} peerSsrc={} peerDir={}",
                        s.getKey(), peerIp, peerPort, sdpInfo.ssrc, sdpInfo.audioDir);
            } else {
                log.warn("[VOICE] 设备INVITE SDP无有效音频地址/端口，暂不发送，等待设备RTP到达后学习: key={} sdp={}",
                        s.getKey(), sdpInfo.toSummary());
            }
        } else {
            s.setPayloadType(8);
            log.warn("[VOICE] 设备INVITE无SDP，暂不发送，等待设备RTP到达后学习: key={}", s.getKey());
        }

        // 200 OK answer（通告本会话独占端口）
        int pt = s.getPayloadType() > 0 ? s.getPayloadType() : 8;
        String answerSdp = SipRequestBuilder.buildAudioAnswerSdp(
                props, announceIp(), s.getLocalPort(), s.getSsrc(), pt, s.isTcp());

        try {
            sipServer.send(sender, SipResponseBuilder.buildWithTag(
                    msg, 200, "OK", "application/sdp", answerSdp,
                    props, s.getPlatformTag()));
        } catch (Exception e) {
            log.error("[VOICE] 200 OK 发送失败: key={}", s.getKey(), e);
            return false;
        }

        enterTalking(s, "设备INVITE");
        return true;
    }

    /** 平台主动 INVITE 的 200 OK 响应（对讲流程）。 */
    public boolean handleInviteResponse(SipMessage msg) {
        String callId = msg.getHeader("Call-ID");
        VoiceSession s = findByCallId(callId);
        if (s == null) {
            return false;
        }

        int status = msg.statusCode();
        if (status == 200) {
            cancelTimeout(s.getKey());

            // 重复 200 OK 幂等处理：设备在未收到（有效）ACK 前会周期性重传 200 OK，
            // 仅首个 200 OK 执行 SDP 解析与 RTP 目标协商，重传的直接复用结果；
            // 但每次仍重发 ACK——重传本身即设备未认可 ACK 的信号，重发是对抗手段。
            boolean first = s.markInviteNegotiated();
            if (!first) {
                log.info("[VOICE] 设备重传200OK(复用协商结果并重发ACK): key={}", s.getKey());
            }

            if (first) {
                // 首个 200 OK 打印设备应答全文：这是核对 To tag / Contact /
                // SDP 传输层协议（RTP/AVP=UDP 还是 TCP/RTP/AVP）、方向属性、
                // y=SSRC、f= 行的唯一途径，不依赖抓包即可完成协商合规性判定
                log.info("[VOICE] 设备200OK原文: key={}\n{}", s.getKey(), msg.toRaw());
                try {
                    SdpInfo peer = SdpParser.parse(msg.getBodyText(
                            java.nio.charset.StandardCharsets.UTF_8));
                    if (peer != null) {
                        // 传输协商（RFC 3264：以应答为准）：设备应答 TCP/RTP/AVP →
                        // 设备按 a=setup:active 主动连入平台 TCP 媒体端口，RTP 走 TCP；
                        // 应答 RTP/AVP → 回退 UDP 发送。
                        s.setTcp(peer.isTcp());
                        log.info("[VOICE] 200OK传输协商: key={} tcp={} peerSetup={} peerDir={} peerFmt={}",
                                s.getKey(), peer.isTcp(), peer.setup, peer.audioDir, peer.audioFormat);
                        // 平台主动 INVITE 的 200 OK：协商平台→设备的 RTP 目标地址。
                        // 规则：优先 m=audio 媒体级 c=（audioIp），缺失回退会话级 c=（sessionIp）；
                        // 端口取 m=audio 行。0.0.0.0 / 端口 0 视为未协商，留给 RTP 源地址学习兜底。
                        String peerIp = (peer.audioIp != null && !peer.audioIp.isBlank())
                                ? peer.audioIp : peer.sessionIp;
                        int peerPort = peer.audioPort;
                        if (isValidRtpTarget(peerIp, peerPort)) {
                            s.setAudioIp(peerIp);
                            s.setAudioPort(peerPort);
                            log.info("[VOICE] 200OK协商RTP目标: key={} audioIp={} audioPort={} peerSsrc={} peerDir={}",
                                    s.getKey(), peerIp, peerPort, peer.ssrc, peer.audioDir);
                        } else {
                            log.warn("[VOICE] 200OK SDP无有效音频地址/端口，暂不发送，等待设备RTP到达后学习: key={} sdp={}",
                                    s.getKey(), peer.toSummary());
                        }
                        if (peer.pcmaPayloadType > 0) {
                            s.setPayloadType(peer.pcmaPayloadType);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[VOICE] 200 OK SDP解析失败: key={} err={}",
                            s.getKey(), e.getMessage());
                }
            }

            String toTag = extractTag(msg.getHeader("To"));
            if (first) {
                s.setDeviceTag(toTag);
                s.setPeerContact(msg.getHeader("Contact"));
                if (toTag == null) {
                    // RFC 3261 要求 200 OK 必须带 To tag；设备漏带时其 ACK 匹配会失败，
                    // 多数表现即为设备周期性重传 200 OK。此处显式暴露该事实。
                    log.warn("[VOICE] 设备200OK的To头未携带tag(非RFC3261)，ACK可能被设备丢弃: key={}",
                            s.getKey());
                }
            }

            try {
                byte[] ack = SipRequestBuilder.voiceAck(props, s);
                log.info("[VOICE] 发送ACK: key={} toTag={} contact={}\n{}",
                        s.getKey(), toTag, s.getPeerContact(),
                        new String(ack, java.nio.charset.StandardCharsets.ISO_8859_1));
                sipServer.send(
                        new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                        ack);
            } catch (Exception e) {
                log.error("[VOICE] ACK 发送失败: key={}", s.getKey(), e);
                doStop(s, "ACK发送失败");
                return false;
            }

            enterTalking(s, "平台INVITE");
            return true;
        } else if (status >= 400) {
            // 打印完整失败响应：部分设备（如海康）在 4xx 的 Warning 头或正文里
            // 给出拒绝原因（如 SDP 非法、SSRC 冲突、会话已存在），据此精准修复
            log.warn("[VOICE] 设备拒绝对讲: key={} status={}\n{}",
                    s.getKey(), status, msg.toRaw());
            doStop(s, "设备拒绝: " + status);
            return true;
        }
        return false;
    }

    /** 设备 ACK（应答平台的 200 OK answer）。 */
    public void handleDeviceAck(String callId) {
        VoiceSession s = findByCallId(callId);
        if (s == null) {
            s = findByBroadcastCallId(callId);
        }
        if (s != null && !s.isAckReceived()) {
            s.setAckReceived(true);
            log.info("[VOICE] 设备ACK已确认: key={}", s.getKey());
        }
    }

    /** 设备 BYE：结束会话。 */
    public void handleDeviceBye(String callId) {
        VoiceSession s = findByCallId(callId);
        if (s == null) {
            s = findByBroadcastCallId(callId);
        }
        if (s != null) {
            log.info("[VOICE] 设备主动BYE: key={}", s.getKey());
            // 设备已发 BYE，平台不再回发（与 1.0 stopInternal 一致）
            doStop(s, "设备BYE", false);
        }
    }

    /** 广播 MESSAGE 的响应（设备拒绝广播时）。 */
    public void handleBroadcastResponse(String callId, int statusCode, String reason) {
        // 响应的 Call-ID 必然等于 MESSAGE 的 Call-ID（broadcastMessageCallId）；
        // broadcastCallId（设备反向 INVITE）仅作兜底兼容
        VoiceSession s = sessions.values().stream()
                .filter(v -> callId.equals(v.getBroadcastMessageCallId())
                        || callId.equals(v.getBroadcastCallId()))
                .findFirst()
                .orElse(null);
        if (s == null) {
            // 匹配不到仅记录：MESSAGE 响应到达早于会话登记等时序场景
            log.debug("[VOICE] 广播响应无匹配会话: callId={} status={}", callId, statusCode);
            return;
        }
        if (statusCode >= 400) {
            log.warn("[VOICE] 设备拒绝广播: key={} status={} reason={}",
                    s.getKey(), statusCode, reason);
            doStop(s, "设备拒绝广播: " + statusCode);
        }
    }

    /** 进入 TALKING：启动发送线程、记录开始时间。 */
    private void enterTalking(VoiceSession s, String scene) {
        if (s.getState() == VoiceSession.State.TALKING) {
            return;
        }
        s.setState(VoiceSession.State.TALKING);
        s.setTalkStartTime(System.currentTimeMillis());
        startSender(s);
        log.info("[VOICE] 对讲已建立({}): key={} localPort={} pt={} ssrc={} audioIp={} audioPort={} tcp={}",
                scene, s.getKey(), s.getLocalPort(), s.getPayloadType(), s.getSsrc(),
                (s.getAudioIp() == null ? "待学习" : s.getAudioIp()), s.getAudioPort(), s.isTcp());
    }

    // =========================================================
    // WebSocket 管理
    // =========================================================

    public void registerWebSocket(String key, WebSocketSession ws) {
        webSockets.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(ws.getId(), ws);
        log.info("[VOICE] WebSocket已注册: key={} ws={}", key, ws.getId());
    }

    public void unregisterWebSocket(String key, WebSocketSession ws) {
        Map<String, WebSocketSession> m = webSockets.get(key);
        if (m != null) {
            m.remove(ws.getId());
            if (m.isEmpty()) {
                webSockets.remove(key, m);
            }
        }
        log.info("[VOICE] WebSocket已注销: key={} ws={}", key, ws.getId());
    }

    private void closeWebSockets(VoiceSession s, String reason) {
        Map<String, WebSocketSession> m = webSockets.remove(s.getKey());
        if (m == null) {
            return;
        }
        for (WebSocketSession ws : m.values()) {
            try {
                if (ws.isOpen()) {
                    ws.close(new CloseStatus(4500, reason));
                }
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }

    // =========================================================
    // 查询
    // =========================================================

    public List<VoiceSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public VoiceSession getSession(String deviceId, String channelId) {
        return sessions.get(key(deviceId, channelId));
    }

    /**
     * 按 Call-ID 判断是否为语音会话（路由器分流用）。
     *
     * <p>同时匹配平台主动 INVITE 的 callId 与广播流程设备侧 Call-ID。
     */
    public boolean hasSession(String callId) {
        if (callId == null) {
            return false;
        }
        return findByCallId(callId) != null || findByBroadcastCallId(callId) != null;
    }

    /** 端口池剩余端口数。 */
    public int availablePorts() {
        return rtpEndpoint.availablePorts();
    }

    // =========================================================
    // 内部工具
    // =========================================================

    private static String key(String deviceId, String channelId) {
        return deviceId + ":" + channelId;
    }

    /**
     * RTP 目标地址有效性校验（与 {@link VoiceSession#isRtpTargetValid()} 同规则）。
     *
     * <p>过滤 {@code 0.0.0.0}、空 IP、端口 0 等未协商/通配值，
     * 防止把设备 SIP 地址（如 192.168.8.65:5060）当作 RTP 目标盲发。
     */
    private static boolean isValidRtpTarget(String ip, int port) {
        return ip != null && !ip.isBlank() && !"0.0.0.0".equals(ip)
                && port > 0 && port <= 65535;
    }

    private Object lockOf(String key) {
        return key.intern();
    }

    private VoiceSession findSessionForInvite(String channelId, InetSocketAddress sender) {
        // 优先精确匹配 BROADCASTING 会话（广播流程）
        for (VoiceSession s : sessions.values()) {
            if (s.getState() == VoiceSession.State.BROADCASTING
                    && s.getChannelId().equals(channelId)) {
                return s;
            }
        }
        // 其次匹配同源地址的会话
        for (VoiceSession s : sessions.values()) {
            if (s.getDeviceHost().equals(sender.getHostString())
                    && s.getChannelId().equals(channelId)) {
                return s;
            }
        }
        return null;
    }

    private VoiceSession findByCallId(String callId) {
        for (VoiceSession s : sessions.values()) {
            if (callId.equals(s.getCallId())) {
                return s;
            }
        }
        return null;
    }

    private VoiceSession findByBroadcastCallId(String callId) {
        for (VoiceSession s : sessions.values()) {
            if (callId.equals(s.getBroadcastCallId())) {
                return s;
            }
        }
        return null;
    }

    /** 从 To/From 头提取 tag 参数。 */
    private static String extractTag(String header) {
        if (header == null) {
            return null;
        }
        int i = header.indexOf("tag=");
        if (i < 0) {
            return null;
        }
        String rest = header.substring(i + 4);
        int end = rest.indexOf(';');
        return (end < 0 ? rest : rest.substring(0, end)).trim();
    }

    /** 从 To 头 URI 提取通道 ID。 */
    private static String extractChannelId(String toHeader) {
        if (toHeader == null) {
            return null;
        }
        int lt = toHeader.indexOf('<');
        int gt = toHeader.indexOf('>');
        if (lt < 0 || gt < 0) {
            return null;
        }
        String uri = toHeader.substring(lt + 1, gt);
        int at = uri.indexOf('@');
        if (at < 0) {
            return null;
        }
        String user = uri.substring(4, at); // 去掉 sip:
        int colon = user.indexOf(':');
        return colon < 0 ? user : user.substring(0, colon);
    }

    private void scheduleTimeout(VoiceSession s, long millis, String reason) {
        ScheduledFuture<?> f = timeoutScheduler.schedule(() -> {
            try {
                if (s.getState() != VoiceSession.State.TALKING
                        && s.getState() != VoiceSession.State.STOPPED) {
                    log.warn("[VOICE] {}: key={}", reason, s.getKey());
                    doStop(s, reason);
                }
            } catch (Exception e) {
                // 异常绝不逃逸到池 worker：否则线程死亡、后续超时任务无人执行
                log.warn("[VOICE] 超时清理执行异常(不影响池): key={} error={}",
                        s.getKey(), e.getMessage());
            }
        }, millis, TimeUnit.MILLISECONDS);
        timeoutTasks.put(s.getKey(), f);
    }

    private void cancelTimeout(String key) {
        ScheduledFuture<?> f = timeoutTasks.remove(key);
        if (f != null) {
            f.cancel(false);
        }
    }

    // =========================================================
    // 停机
    // =========================================================

    @PreDestroy
    public void shutdown() {
        shutdown = true;
        log.info("[VOICE] 语音服务停机：停止全部会话");
        for (VoiceSession s : new ArrayList<>(sessions.values())) {
            doStop(s, "服务停机");
        }
        timeoutScheduler.shutdownNow();
    }
}
