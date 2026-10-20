package com.example.sip.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 媒体会话（MediaSession）：管理一次"平台主动 INVITE 摄像机"的完整生命周期。
 *
 * <p>覆盖三类业务：实时直播（LIVE）、录像回放（PLAYBACK）、录像下载（DOWNLOAD）。
 *
 * <p>流程：start 发送 INVITE（INVITING）→ 收到 200 OK 发送 ACK（PLAYING）
 * → 摄像机 RTP/PS 推流 → stop 发送 BYE 释放资源（STOPPED）。
 *
 * <p>多端口模型：每个会话独占 localPort（本地 UDP 端口），设备直接推流到该端口。
 */
@Getter
@Setter
public class StreamSession {

    /** 会话类型 */
    public enum Type {
        /** 实时直播 */
        LIVE,
        /** 录像回放 */
        PLAYBACK,
        /** 录像下载 */
        DOWNLOAD
    }

    /** 会话状态机 */
    public enum State {
        /** 已发送 INVITE，等待摄像机 200 OK */
        INVITING,
        /** 协商成功（已 ACK），摄像机正在推流 */
        PLAYING,
        /** 已停止 / 失败 */
        STOPPED
    }

    private final String callId;
    private final String deviceId;
    private final String channelId;
    private final String deviceHost;
    private final int devicePort;

    /** 本地 RTP 接收端口（多端口：每会话独占） */
    private final int localPort;

    /**
     * 构造媒体会话（多端口模型）。
     *
     * <p>localPort 为 final，必须先由 RtpPortPool 分配端口再构造会话，
     * 构造完成后由 RtpReceiver 绑定该端口等待设备推流。
     *
     * @param localPort 本会话独占的本地 UDP 端口（ZLM 模式传 0，媒体面由 ZLM 承载）
     */
    public StreamSession(String callId,
                         String deviceId,
                         String channelId,
                         String deviceHost,
                         int devicePort,
                         int localPort) {
        this.callId = callId;
        this.deviceId = deviceId;
        this.channelId = channelId;
        this.deviceHost = deviceHost;
        this.devicePort = devicePort;
        this.localPort = localPort;
    }

    /** 会话类型（直播 / 回放 / 下载） */
    private Type type = Type.LIVE;

    /** 会话状态 */
    private volatile State state = State.INVITING;

    /** INVITE 使用的 branch（ACK 必须回显） */
    private volatile String branch;

    /** 服务器侧 tag（From 头） */
    private volatile String fromTag;

    /** 摄像机侧 tag（200 OK 的 To 头，ACK/BYE 回显） */
    private volatile String toTag;

    /** 摄像机 200 OK 返回的 SDP 摘要 */
    private volatile String peerSdp;

    /** 摄像机 answer 选择的媒体载荷类型，如 "96" */
    private volatile String peerPayload;

    /** 摄像机 200 OK 返回的 Contact URI */
    private volatile String peerContact;

    /** 本次点播的 SSRC，SDP y= 字段 */
    private volatile String ssrc;

    /** 媒体面是否由 ZLMediaKit 承载 */
    private volatile boolean zlm;

    /**
     * 调用 ZLM openRtpServer 时使用的 stream_id。
     *
     * <p>注意：这个值不一定等于 ZLM 最终注册的 MediaSource stream。
     */
    private volatile String zlmRequestStreamId;

    /**
     * ZLM 实际注册的 MediaSource stream。
     *
     * <p>浏览器 WebRTC / HTTP-FLV / HLS 播放必须使用这个值。
     */
    private volatile String zlmStreamId;

    /** 回放 / 下载起始时间（秒级 epoch） */
    private volatile long playbackStart;

    /** 回放 / 下载结束时间（秒级 epoch） */
    private volatile long playbackEnd;

    /** INVITE 已重传次数 */
    private volatile int inviteRetries;

    /** 原始 INVITE 报文缓存（UDP 重传用） */
    private volatile byte[] invitePacket;

    /** 会话创建时间（epoch millis） */
    private final long createTime = System.currentTimeMillis();

    /** 200 OK 收到 / 媒体开始时间（epoch millis） */
    private volatile long startTime;

    /** ffmpeg 转码进程（PS → FLV/HLS/MP4），local 模式使用 */
    private volatile Process ffmpeg;

    /**
     * ffmpeg 已输出的媒体时间（秒，解析自 stderr 进度行 {@code time=} 字段）。
     *
     * <p>下载/回放会话的真实进度信号：无论设备按 1x 还是 4x 推流，该值都
     * 反映"已转码的录像时长"，前端据此计算进度；早期按"录像时长/倍速"
     * 估算在设备不按倍速推流时会过早到顶，长时间停在 95% 假死。
     */
    private volatile double ffmpegOutTimeSec = 0;

    /**
     * 当前回放倍速（MANSRTSP SCALE 协商值）。
     *
     * <p>INVITE 时 SDP {@code a=downloadspeed} 与下载侧 SCALE INFO 可能
     * 取到不同值，以此字段为准向前端展示。
     */
    private volatile double playbackScale = 1.0;

    /**
     * 会话内 INFO 事务 CSeq：从 3 起逐次递增
     * （INVITE=1、ACK=2 之后，RFC 3261 同一 Dialog 内 CSeq 必须单调递增）。
     *
     * <p>早期 MANSRTSP 控制写死 {@code CSeq: 3}，单次会话下发第二条
     * 控制指令（如先 SCALE 再 PAUSE）时重复使用同一 CSeq，严格 UAS
     * 会判为重传/序列错误；直播保活 INFO 与 MANSRTSP 控制共用本计数器。
     */
    private final java.util.concurrent.atomic.AtomicInteger infoCseq =
            new java.util.concurrent.atomic.AtomicInteger(3);

    /** 浏览器 HLS 播放地址，如 /hls/<callId>/play.m3u8 */
    private volatile String hlsUrl;

    /** 录像下载文件名（DOWNLOAD 类型） */
    private volatile String downloadFileName;

    /** 录像下载地址（DOWNLOAD 类型），如 /download/<fileName> */
    private volatile String downloadUrl;

    /** 浏览器 FLV 播放地址（local 模式，由 type 推导） */
    public String getFlvUrl() {
        return isPlayback() ? getPlaybackFlvUrl() : getLiveFlvUrl();
    }

    /** 直播 FLV 播放地址（FlvController 拉流端点） */
    public String getLiveFlvUrl() {
        return "/live/" + deviceId + "/" + channelId + ".flv";
    }

    /** 回放/下载 FLV 播放地址（按起止时间定位会话） */
    public String getPlaybackFlvUrl() {
        return "/playback/" + deviceId + "/" + channelId + "/"
                + playbackStart + "/" + playbackEnd + ".flv";
    }

    /** 会话唯一键：deviceId:channelId */
    public String getKey() {
        return deviceId + ":" + channelId;
    }

    /** 是否回放/下载类会话（SDP 带时间范围） */
    public boolean isPlayback() {
        return type == Type.PLAYBACK || type == Type.DOWNLOAD;
    }

    /** 取下一个会话内 INFO 的 CSeq（逐次递增，勿缓存复用） */
    public int nextInfoCseq() {
        return infoCseq.getAndIncrement();
    }
}
