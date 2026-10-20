package com.example.sip.model;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * 语音对讲会话：GB/T 28181-2022 多端口模式。
 *
 * <p>媒体链路：
 * 浏览器 PCM -> G.711A PCMA -> audioBuffer
 * -> 每20ms取160字节 -> RTP -> 设备。
 *
 * <p>反向媒体链路：
 * 设备 RTP -> G.711A PCMA -> PCM
 * -> incomingPcmBuffer -> 浏览器。
 *
 * <p>多端口模型：每个会话独占 localPort（本地 UDP 端口），
 * 设备直接推流到该端口；每会话独立维护 RTP seq、timestamp 和音频缓冲。
 */
public class VoiceSession {

    public enum State {
        BROADCASTING,
        AWAIT_INVITE,
        INVITED,
        TALKING,
        STOPPED
    }

    /** PCMA 8kHz，20ms = 160字节 */
    private static final int RTP_FRAME_SIZE = 160;

    /** 浏览器 -> 摄像机：最大缓存2秒 */
    private static final int MAX_AUDIO_BUFFER = 16000;

    /** 摄像机 -> 浏览器：最大缓存2秒，8kHz、16bit PCM */
    private static final int MAX_INCOMING_PCM_BUFFER = 32000;

    /** PCMA 静音码 */
    private static final byte PCMA_SILENCE = (byte) 0xD5;

    private final String deviceId;
    private final String channelId;
    private final String deviceHost;
    private final int devicePort;

    /** 本会话平台侧 RTP 端口（多端口：每会话独占，SDP m=audio 通告） */
    private final int localPort;

    /** 平台主动 INVITE 的 Via branch（ACK/BYE 回显） */
    private volatile String branch;

    private volatile String callId;
    private volatile String deviceTag;
    private volatile String platformTag;

    /** 设备收音频地址 */
    private volatile String audioIp;

    /** 设备收音频 RTP 端口 */
    private volatile int audioPort;

    /** RTP Payload Type，严格使用 SDP 协商值 */
    private volatile int payloadType = -1;

    /** RTP SSRC */
    private volatile String ssrc;

    /** RTP 序列号 */
    private int seq = randomSeq();

    /** RTP 时间戳，PCMA 8kHz，每20ms +160 */
    private int timestamp = randomTimestamp();

    /** 是否已打印过设备 RTP 首包日志（每会话一次） */
    private final java.util.concurrent.atomic.AtomicBoolean firstDeviceRtpLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 上行帧计数（诊断用：每约 1 秒输出一次电平） */
    private final java.util.concurrent.atomic.AtomicInteger upstreamFrameCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 统计周期内的上行 PCM 峰值（诊断用，0 = 数字静音） */
    private volatile int upstreamPeak;

    /** 上行电平统计帧数：浏览器约 31 块/s，31 帧 ≈ 1 秒输出一次 */
    private static final int UPSTREAM_STAT_FRAMES = 31;

    /** 已发送 RTP 包数 */
    private long packetsSent;

    /**
     * 浏览器 -> 摄像机的 PCMA 音频缓冲。
     *
     * <p>这里保存浏览器产生的真实 PCMA 音频。
     */
    private final ByteArrayOutputStream audioBuffer =
            new ByteArrayOutputStream(4096);

    /**
     * 摄像机 -> 浏览器的 PCM 音频缓冲。
     *
     * <p>数据来源：
     * 摄像机 RTP -> PCMA -> G.711A decode -> 8kHz 16bit PCM。
     */
    private final ByteArrayOutputStream incomingPcmBuffer =
            new ByteArrayOutputStream(8192);

    private volatile State state = State.BROADCASTING;

    private final long createTime = System.currentTimeMillis();

    private volatile long talkStartTime;

    private volatile boolean ackReceived;

    /** 平台主动 INVITE 的首个 200 OK 是否已协商过（设备重传 200 OK 时复用结果，不重复协商） */
    private final java.util.concurrent.atomic.AtomicBoolean inviteNegotiated =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private volatile String stopReason;

    /** Broadcast MESSAGE 的 Call-ID */
    private volatile String broadcastCallId;

    /**
     * 是否为广播会话（广播 MESSAGE 发起）。
     *
     * <p>广播时 channelId 归一化为 deviceId（与 WebSocket 注册键保持一致），
     * 通过此标记区分广播与对讲。
     */
    private volatile boolean broadcast;

    /** 对端 Contact（平台主动 INVITE 的 200 OK 中带回） */
    private volatile String peerContact;

    /**
     * 媒体传输是否为 TCP（设备主动连接平台）。
     *
     * <p>平台对讲 INVITE SDP 默认提供 RTP/AVP（UDP）；若设备 200 OK 应答
     * TCP/RTP/AVP，此处置 true，RTP 改走设备主动连入的 TCP 连接。
     */
    private volatile boolean tcp;

    public String getPeerContact() {
        return peerContact;
    }

    public void setPeerContact(String peerContact) {
        this.peerContact = peerContact;
    }

    public boolean isTcp() {
        return tcp;
    }

    public void setTcp(boolean tcp) {
        this.tcp = tcp;
    }

    public VoiceSession(String deviceId,
                        String channelId,
                        String deviceHost,
                        int devicePort,
                        int localPort) {
        this.deviceId = deviceId;
        this.channelId = channelId;
        this.deviceHost = deviceHost;
        this.devicePort = devicePort;
        this.localPort = localPort;
    }

    public int getLocalPort() {
        return localPort;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getKey() {
        return deviceId + ":" + channelId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getDeviceHost() {
        return deviceHost;
    }

    public int getDevicePort() {
        return devicePort;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getDeviceTag() {
        return deviceTag;
    }

    public void setDeviceTag(String deviceTag) {
        this.deviceTag = deviceTag;
    }

    public String getPlatformTag() {
        return platformTag;
    }

    public void setPlatformTag(String platformTag) {
        this.platformTag = platformTag;
    }

    public String getAudioIp() {
        return audioIp;
    }

    public void setAudioIp(String audioIp) {
        this.audioIp = audioIp;
    }

    public int getAudioPort() {
        return audioPort;
    }

    public void setAudioPort(int audioPort) {
        this.audioPort = audioPort;
    }

    /**
     * 设备 RTP 接收目标（audioIp:audioPort）是否有效。
     *
     * <p>有效性规则（防止把 SIP 地址当 RTP 目标盲发）：
     * <ul>
     *   <li>IP 非空、非通配地址 {@code 0.0.0.0}；</li>
     *   <li>端口在 {@code 1..65535}（设备 SIP 端口如 5060 也必须经 SDP 协商才可用，
     *       未协商时一律视为无效）。</li>
     * </ul>
     */
    public boolean isRtpTargetValid() {
        return audioIp != null && !audioIp.isBlank()
                && !"0.0.0.0".equals(audioIp)
                && audioPort > 0 && audioPort <= 65535;
    }

    public int getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(int payloadType) {
        this.payloadType = payloadType;
    }

    public String getSsrc() {
        return ssrc;
    }

    public void setSsrc(String ssrc) {
        this.ssrc = ssrc;
    }

    /**
     * 获取下一个 RTP 序列号。
     *
     * <p>16-bit 自然溢出。
     */
    public synchronized int nextSeq() {
        return seq++ & 0xFFFF;
    }

    /**
     * 获取下一个 RTP 时间戳。
     *
     * <p>PCMA 8000Hz，20ms = 160 samples。
     */
    public synchronized int nextTimestamp() {
        int current = timestamp;
        timestamp += RTP_FRAME_SIZE;
        return current;
    }

    /**
     * 标记并返回是否应打印设备 RTP 首包日志（每会话仅一次）。
     */
    public boolean markFirstDeviceRtp() {
        return firstDeviceRtpLogged.compareAndSet(false, true);
    }

    /**
     * 记录一帧浏览器上行 PCM 的峰值幅度。
     *
     * @return true=本统计周期已满约 1 秒，调用方应输出一次电平日志（随后取 takeUpstreamPeak）
     */
    public boolean noteUpstreamFrame(int peak) {
        if (peak > upstreamPeak) {
            upstreamPeak = peak;
        }
        if (upstreamFrameCount.incrementAndGet() >= UPSTREAM_STAT_FRAMES) {
            upstreamFrameCount.set(0);
            return true;
        }
        return false;
    }

    /** 取出并重置本统计周期内的上行峰值。 */
    public int takeUpstreamPeak() {
        int peak = upstreamPeak;
        upstreamPeak = 0;
        return peak;
    }

    /** 下行（设备→浏览器）解码帧计数（诊断用） */
    private final java.util.concurrent.atomic.AtomicInteger downstreamFrameCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 统计周期内的下行 PCM 峰值（诊断用，0 = 数字静音） */
    private volatile int downstreamPeak;

    /** 下行统计上次输出时刻（诊断用，按时间触发，因为设备包率可能极低） */
    private volatile long downstreamStatAt;

    /** 记录一帧设备下行 PCM 的峰值幅度（诊断用）。 */
    public void noteDownstreamFrame(int peak) {
        if (peak > downstreamPeak) {
            downstreamPeak = peak;
        }
        downstreamFrameCount.incrementAndGet();
    }

    /** 下行统计是否到点（每约 2 秒一次）。 */
    public boolean dueDownstreamStat(long now, long intervalMs) {
        return now - downstreamStatAt >= intervalMs;
    }

    /** 标记下行统计输出时刻。 */
    public void markDownstreamStat(long now) {
        downstreamStatAt = now;
    }

    /** 取出并重置下行统计：int[0]=帧数, int[1]=峰值。 */
    public int[] takeDownstreamStat() {
        int frames = downstreamFrameCount.getAndSet(0);
        int peak = downstreamPeak;
        downstreamPeak = 0;
        return new int[]{frames, peak};
    }

    /**
     * 写入浏览器产生的 PCMA 音频。
     *
     * <p>这里只保存真实音频，不做 PLC、不重复上一帧。
     */
    public synchronized void offerAlaw(byte[] alaw) {
        if (alaw == null || alaw.length == 0) {
            return;
        }

        if (audioBuffer.size() + alaw.length > MAX_AUDIO_BUFFER) {
            audioBuffer.reset();
        }

        audioBuffer.write(alaw, 0, alaw.length);
    }

    /**
     * 每次取一帧 PCMA。
     *
     * <p>正常情况下返回160字节。
     * 缓冲不足160字节时返回null。
     *
     * <p>这里不补静音，不重播上一帧。
     * RTP 定时发送和静音补帧由发送层负责。
     */
    public synchronized byte[] pollAlawFrame(int size) {
        if (size <= 0 || audioBuffer.size() < size) {
            return null;
        }

        byte[] all = audioBuffer.toByteArray();

        byte[] frame = new byte[size];
        System.arraycopy(all, 0, frame, 0, size);

        audioBuffer.reset();

        if (all.length > size) {
            audioBuffer.write(all, size, all.length - size);
        }

        return frame;
    }

    /**
     * 获取浏览器 -> 摄像机当前真实音频缓冲大小。
     */
    public synchronized int getAudioBufferSize() {
        return audioBuffer.size();
    }

    /**
     * 清空浏览器 -> 摄像机真实音频缓冲。
     */
    public synchronized void clearAudioBuffer() {
        audioBuffer.reset();
    }

    /**
     * 写入摄像机 -> 浏览器的 PCM 音频。
     *
     * <p>数据来源：
     * 摄像机 RTP -> PCMA -> G.711A decode -> PCM。
     *
     * <p>PCM 格式为8kHz、16bit、Little-Endian。
     */
    public synchronized void offerIncomingPcm(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return;
        }

        if (incomingPcmBuffer.size() + pcm.length > MAX_INCOMING_PCM_BUFFER) {
            incomingPcmBuffer.reset();
        }

        incomingPcmBuffer.write(pcm, 0, pcm.length);
    }

    /**
     * 从摄像机 -> 浏览器缓冲中取出指定大小的 PCM。
     *
     * <p>数据不足时返回null。
     */
    public synchronized byte[] pollIncomingPcm(int size) {
        if (size <= 0 || incomingPcmBuffer.size() < size) {
            return null;
        }

        byte[] all = incomingPcmBuffer.toByteArray();

        byte[] pcm = new byte[size];
        System.arraycopy(all, 0, pcm, 0, size);

        incomingPcmBuffer.reset();

        if (all.length > size) {
            incomingPcmBuffer.write(all, size, all.length - size);
        }

        return pcm;
    }

    /**
     * 获取摄像机 -> 浏览器当前 PCM 缓冲大小。
     */
    public synchronized int getIncomingPcmBufferSize() {
        return incomingPcmBuffer.size();
    }

    /**
     * 清空摄像机 -> 浏览器 PCM 缓冲。
     */
    public synchronized void clearIncomingPcmBuffer() {
        incomingPcmBuffer.reset();
    }

    /**
     * 返回160字节 PCMA 静音帧。
     *
     * <p>发送层在没有浏览器音频时使用。
     */
    public static byte[] createSilenceFrame() {
        byte[] frame = new byte[RTP_FRAME_SIZE];
        Arrays.fill(frame, PCMA_SILENCE);
        return frame;
    }

    /** 发送计数 +1 */
    public synchronized long incPacketsSent() {
        return ++packetsSent;
    }

    /** 已发送 RTP 包数 */
    public synchronized long getPacketsSent() {
        return packetsSent;
    }

    private static int randomSeq() {
        return new Random().nextInt(0x10000);
    }

    private static int randomTimestamp() {
        return new Random().nextInt(0x7FFFFF00);
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getTalkStartTime() {
        return talkStartTime;
    }

    public void setTalkStartTime(long talkStartTime) {
        this.talkStartTime = talkStartTime;
    }

    public boolean isAckReceived() {
        return ackReceived;
    }

    /**
     * 标记 INVITE 200 OK 协商是否已完成。
     *
     * @return true=首次（调用方应执行 SDP 解析与目标协商）；false=设备重传的 200 OK
     */
    public boolean markInviteNegotiated() {
        return inviteNegotiated.compareAndSet(false, true);
    }

    public void setAckReceived(boolean ackReceived) {
        this.ackReceived = ackReceived;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public String getBroadcastCallId() {
        return broadcastCallId;
    }

    public void setBroadcastCallId(String broadcastCallId) {
        this.broadcastCallId = broadcastCallId;
    }

    public boolean isBroadcast() {
        return broadcast;
    }

    public void setBroadcast(boolean broadcast) {
        this.broadcast = broadcast;
    }
}