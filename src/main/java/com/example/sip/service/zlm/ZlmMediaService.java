

package com.example.sip.service.zlm;

import com.example.sip.config.MediaProperties;
import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Device;
import com.example.sip.model.StreamSession;
import com.example.sip.netty.UdpSipServer;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.sip.SipRequestBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ZLMediaKit 媒体面会话服务（与自研链路 PlayService/PlaybackService 完全隔离）。
 *
 * <p>固定单 RTP 端口 Multiplex 模式：
 * <ul>
 *     <li>ZLM 监听配置文件中指定的固定 UDP 端口（示例 30002）</li>
 *     <li>所有摄像机共用该 RTP 接收端口</li>
 *     <li>不同设备通过 RTP SSRC 区分媒体流</li>
 *     <li>RTP Server 的 stream_id 与实际 MediaSource stream 分开保存</li>
 * </ul>
 *
 * <p>例如：
 * <pre>
 * RTP Server:
 *     stream_id=gb28181
 *     port=30002
 *
 * SIP SSRC:
 *     0777716362
 *
 * ZLM MediaSource:
 *     2E5B028A
 * </pre>
 *
 * <p>停止单路会话时，只关闭对应 MediaSource，
 * 不关闭由配置文件指定端口的公共 RTP Server。
 */
@Service
public class ZlmMediaService {

    /** 统一日志器，所有运行期日志均带 [ZLM] 前缀，便于与自研链路日志区分。 */
    private static final Logger log = LoggerFactory.getLogger(ZlmMediaService.class);

    /** INVITE 等待最终响应的总超时（10s），超时后自动清理会话。 */
    private static final long INVITE_TIMEOUT_MILLIS = 10_000L;

    /**
     * INVITE 的 UDP 重传退避间隔（毫秒）。
     * 依次在第 0.5s/1s/2s/4s 各重发一次，模拟 SIP 事务的重传策略。
     */
    private static final long[] INVITE_RETRANSMIT_DELAYS = {
            500L, 1_000L, 2_000L, 4_000L
    };

    /**
     * 配置文件指定端口上的 Multiplex RTP Server 的 stream_id。
     *
     * <p>这里只是 RTP Server 的唯一 key，
     * 不是某一路实际 MediaSource 的 stream。
     */
    private static final String FIXED_RTP_SERVER_ID = "gb28181";

    /**
     * 下载会话等 ZLM 注册 MediaSource 的上限（毫秒）。
     *
     * <p>链路：INVITE → 200 OK → RTP 到达 ZLM → PS 解封装注册 MediaSource，
     * 正常 1~2s；15s 覆盖设备起推慢的抖动，超时按失败收尾（前端
     * awaitDownloadFile 会因会话 STOPPED 提前放弃）。
     */
    private static final long DOWNLOAD_MEDIA_READY_WAIT_MILLIS = 15_000L;

    /**
     * 下载会话 ffmpeg 优雅退出等待（毫秒）。
     *
     * <p>与 MediaService local 链路同值：ffmpeg 收到 EOF 后要 flush 编码
     * 队列 + 写 moov + faststart 重排整个文件（2688x1520 数分钟录像达百
     * MB 级），实测可达数十秒，2s 远远不够。
     */
    private static final long FFMPEG_DOWNLOAD_WAIT_MILLIS = 60_000L;

    /**
     * 下载 ffmpeg 硬超时下限（毫秒，30 分钟）。
     *
     * <p>与 MediaService local 模式 watchdog 同语义：等待上限取
     * max(本值, 预期时长(按倍速折算) × 10)。防止 ZLM 不断流（设备故障
     * 持续推流 / 无观看者超时策略关闭）时等待线程在无超时阻塞上永久挂起。
     */
    private static final long DOWNLOAD_HARD_TIMEOUT_MIN_MILLIS = 1_800_000L;

    /** 下载文件名时间格式（与 local 模式一致，避免两模式文件名风格割裂）。 */
    private static final DateTimeFormatter DL_NAME_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * ffmpeg 进度行 {@code time=} 字段（已转码的录像时长）。
     *
     * <p>下载/回放进度的唯一真实信号：无论设备按 1x 还是 4x 推流都准确
     * （按"录像时长/倍速"估算在设备降速到 1x 时会过早到顶假死）。
     */
    private static final Pattern FFMPEG_TIME_PATTERN =
            Pattern.compile("time=(\\d{1,3}):(\\d{2}):(\\d{2}\\.\\d+)");

    /**
     * 固定 RTP 监听端口。
     *
     * <p>端口值来自配置文件（示例 30002），并非硬编码；0 表示尚未启动，
     * 首次点播时按配置打开 RTP Server 并缓存实际端口，之后全程复用。
     * volatile 保证多线程可见性。
     */
    private volatile int fixedRtpPort = 0;

    /** SIP 服务器配置（域、IP、端口、From 用户等信令参数来源）。 */
    private final SipServerProperties props;
    /** 媒体面配置（ZLM api-base、media-ip、play-host、端口、app 等）。 */
    private final MediaProperties mediaProps;
    /** 设备注册表，用于按 deviceId 查询设备地址与在线状态。 */
    private final DeviceRegistry registry;
    /** ZLM HTTP API 客户端，负责开关 RTP Server、关流、查询流等媒体面操作。 */
    private final ZlmApiClient zlm;
    /** UDP SIP 服务器，负责向设备发送 INVITE/ACK/BYE 等信令报文。 */
    private final UdpSipServer sipServer;

    /** callId → 会话（反向索引：按 SIP Call-ID 定位会话，供信令响应/BYE 路由使用）。 */
    private final Map<String, StreamSession> sessionsByCallId =
            new ConcurrentHashMap<>();

    /** deviceId:channelId → 会话（正向索引：按设备通道定位当前活跃会话）。 */
    private final Map<String, StreamSession> sessionsByKey =
            new ConcurrentHashMap<>();

    /** deviceId:channelId → 操作锁（通道级串行化，保证同一通道点播/停止互斥）。 */
    private final Map<String, Object> locks =
            new ConcurrentHashMap<>();

    /**
     * 单线程守护调度器：承担 INVITE 定时重传与总超时清理，不阻塞业务线程。
     *
     * <p>显式 {@link ScheduledThreadPoolExecutor}（core=1 即单线程语义）：
     * 一次性任务取消即出队，会话频繁创建/取消时不在队列残留到期条目。
     */
    private final ScheduledThreadPoolExecutor scheduler =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "zlm-invite-retransmit");
                t.setDaemon(true);
                return t;
            });

    /**
     * ffmpeg 拉流转码线程池（ZLM 模式录像下载专用）。
     *
     * <p>承担三件短生命周期任务：等 MediaSource 就绪后启动 ffmpeg、
     * 消费 ffmpeg stderr 解析进度、等待进程退出并收尾 MP4。
     * 与会话数量等量的小任务，缓存线程池即可，全部守护线程不阻塞停机。
     */
    private final ExecutorService ffmpegExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "zlm-ffmpeg-download");
                t.setDaemon(true);
                return t;
            });

    /**
     * 构造注入各依赖。
     *
     * @param props      SIP 服务器配置
     * @param mediaProps 媒体面配置
     * @param registry   设备注册表
     * @param zlm        ZLM HTTP API 客户端
     * @param sipServer  UDP SIP 服务器；用 @Lazy 延迟注入以打破与 SIP 层的循环依赖
     */
    public ZlmMediaService(SipServerProperties props,
                           MediaProperties mediaProps,
                           DeviceRegistry registry,
                           ZlmApiClient zlm,
                           @Lazy UdpSipServer sipServer) {
        this.props = props;
        this.mediaProps = mediaProps;
        this.registry = registry;
        this.zlm = zlm;
        this.sipServer = sipServer;
        // 一次性任务取消即出队：见 scheduler 字段注释
        scheduler.setRemoveOnCancelPolicy(true);
    }

    /** 会话键 deviceId:channelId */
    private static String key(String deviceId, String channelId) {
        return deviceId + ":" + channelId;
    }

    /** 通道级操作锁 */
    private Object lockOf(String k) {
        return locks.computeIfAbsent(k, x -> new Object());
    }

    // =========================================================
    // 点播
    // =========================================================

    /**
     * 发起 ZLM 模式点播。
     *
     * <p>先停掉该通道已有会话，再以实时（非回放）方式重建一路会话。
     *
     * @param deviceId  设备国标编码
     * @param channelId 通道国标编码
     * @return 新建的点播会话
     */
    public StreamSession startPlay(String deviceId, String channelId) {
        Device d = requireOnline(deviceId);
        synchronized (lockOf(key(deviceId, channelId))) {
            stopInternal(deviceId, channelId);
            return startInternal(
                    d,
                    deviceId,
                    channelId,
                    false,
                    0,
                    0
            );
        }
    }

    /** 已有 PLAYING 会话直接复用，否则重建。 */
    public StreamSession getOrStartSession(String deviceId, String channelId) {
        String k = key(deviceId, channelId);
        synchronized (lockOf(k)) {
            StreamSession s = sessionsByKey.get(k);
            // 已是实时点播且处于播放中：直接复用，避免重复 INVITE
            if (s != null
                    && s.getState() == StreamSession.State.PLAYING
                    && !s.isPlayback()) {
                return s;
            }
            // 存在但不是可复用状态：先停掉旧的再重建
            if (s != null) {
                stopInternal(deviceId, channelId);
            }
            return startInternal(
                    requireOnline(deviceId),
                    deviceId,
                    channelId,
                    false,
                    0,
                    0
            );
        }
    }

    // =========================================================
    // 回放
    // =========================================================

    /**
     * 发起 ZLM 模式回放。
     *
     * @param deviceId  设备国标编码
     * @param channelId 通道国标编码
     * @param startTime 回放开始时间（epoch 毫秒）
     * @param endTime   回放结束时间（epoch 毫秒），必须晚于开始时间
     * @return 新建的回放会话
     * @throws IllegalArgumentException 结束时间不晚于开始时间时抛出
     */
    public StreamSession startPlayback(String deviceId,
                                       String channelId,
                                       long startTime,
                                       long endTime) {
        if (endTime <= startTime) {
            throw new IllegalArgumentException("回放结束时间必须晚于开始时间");
        }
        Device d = requireOnline(deviceId);
        synchronized (lockOf(key(deviceId, channelId))) {
            stopInternal(deviceId, channelId);
            return startInternal(
                    d,
                    deviceId,
                    channelId,
                    true,
                    startTime,
                    endTime
            );
        }
    }

    /**
     * 发起 ZLM 模式回放或录像下载（幂等：同通道同类型同时间范围则复用）。
     *
     * <p>回放与下载的媒体链路完全相同（INVITE → ZLM 收流 → rtp/&lt;ssrc&gt;
     * MediaSource），区别只在末端：
     * <ul>
     *     <li>回放 PLAYBACK：前端经 WebRTC/FLV 实时观看；</li>
     *     <li>下载 DOWNLOAD：本服务另起 ffmpeg 从 ZLM HTTP-FLV 拉流，离线
     *         转码 MP4 落盘到 {@code local.download-dir}，前端从
     *         {@code /api/download/file} 获取，进度经 {@code /api/download/status}
     *         轮询——与 local 模式前端契约完全一致。</li>
     * </ul>
     *
     * @param download true=下载（类型 DOWNLOAD，落盘 MP4）；false=回放（实时观看）
     */
    public StreamSession startPlaybackOrDownload(String deviceId,
                                                 String channelId,
                                                 long startTime,
                                                 long endTime,
                                                 boolean download) {
        if (endTime <= startTime) {
            throw new IllegalArgumentException("回放结束时间必须晚于开始时间");
        }
        Device d = requireOnline(deviceId);
        synchronized (lockOf(key(deviceId, channelId))) {
            // 幂等复用：会话存活 + 时间范围一致 + 同类型才复用。
            // 类型必须一致：下载（MP4 落盘）与回放（实时分发）末端形态不同，
            // DOWNLOAD 不能被 PLAYBACK 误复用（前端会拿到空播放地址），
            // 反之亦然（回放页不会读到别人的下载文件）。
            // 不拆建还有设备侧考虑：重复 BYE+INVITE 是摄像机宕机的典型诱因。
            StreamSession exist = sessionsByKey.get(key(deviceId, channelId));
            if (exist != null
                    && exist.getState() != StreamSession.State.STOPPED
                    && exist.getPlaybackStart() == startTime
                    && exist.getPlaybackEnd() == endTime
                    && (exist.getType() == StreamSession.Type.DOWNLOAD) == download) {
                return exist;
            }
            stopInternal(deviceId, channelId);
            StreamSession s = startInternal(d, deviceId, channelId, true, startTime, endTime);
            if (download) {
                s.setType(StreamSession.Type.DOWNLOAD);
                s.setDownloadFileName(
                        buildDownloadFileName(channelId, startTime, endTime));
                s.setDownloadUrl("/api/download/file?deviceId=" + deviceId
                        + "&channelId=" + channelId);
                startZlmDownload(s);
            }
            return s;
        }
    }

    // =========================================================
    // 停止
    // =========================================================

    /** 按通道停止 ZLM 会话。 */
    public void stop(String deviceId, String channelId) {
        synchronized (lockOf(key(deviceId, channelId))) {
            stopInternal(deviceId, channelId);
        }
    }

    // =========================================================
    // 内部实现
    // =========================================================

    /** 设备在线校验。 */
    private Device requireOnline(String deviceId) {
        Device d = registry.get(deviceId);
        if (d == null) {
            throw new IllegalStateException("设备未注册: " + deviceId);
        }
        if (!d.isOnline()) {
            throw new IllegalStateException("设备离线: " + deviceId);
        }
        return d;
    }

    /**
     * 确保固定端口的 Multiplex RTP Server 已启动。
     *
     * <p>端口来自配置文件（示例 30002），整个服务只创建一次。
     */
    private synchronized int ensureFixedRtpServer() {
        if (fixedRtpPort > 0) {
            return fixedRtpPort;
        }
        int port = zlm.openRtpServerMultiplex(
                FIXED_RTP_SERVER_ID
        );
        if (port <= 0) {
            throw new IllegalStateException(
                    "ZLM固定RTP复用服务器启动失败: 30002"
            );
        }
        fixedRtpPort = port;
        log.info(
                "[ZLM] 固定RTP复用服务器已启动: streamId={} port={}",
                FIXED_RTP_SERVER_ID,
                fixedRtpPort
        );
        return fixedRtpPort;
    }

    /**
     * 将 GB28181 十进制 SSRC 转换成 ZLM 实际 MediaSource 的
     * 8 位大写十六进制 stream。
     *
     * <p>例如：
     * 0777716362 -> 2E5B028A
     */
    private String toZlmStreamId(String ssrc) {
        long value;
        try {
            value = Long.parseLong(ssrc);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "无效SSRC: " + ssrc,
                    e
            );
        }
        // SSRC 必须落在无符号 32 位范围内
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(
                    "SSRC超出32位范围: " + ssrc
            );
        }
        return String.format("%08X", value);
    }

    /**
     * 创建 ZLM 会话并发送 INVITE。
     *
     * <p>完整流程：生成 callId/SSRC → 确保固定 RTP Server → 计算实际媒体流 ID
     * → 组装会话并登记双索引 → 构造 INVITE 报文并发送 → 挂载重传/超时任务。
     *
     * @param d         目标设备
     * @param deviceId  设备国标编码
     * @param channelId 通道国标编码
     * @param playback  是否为回放（true 走回放 SSRC 与回放 SDP）
     * @param startTime 回放开始时间（点播时忽略）
     * @param endTime   回放结束时间（点播时忽略）
     * @return 处于 INVITING 状态的会话
     */
    private StreamSession startInternal(Device d,
                                        String deviceId,
                                        String channelId,
                                        boolean playback,
                                        long startTime,
                                        long endTime) {
        // 之前这里漏掉了 callId，导致编译错误。
        String callId = UUID.randomUUID()
                .toString()
                .replace("-", "");
        // 回放与实时使用不同的 SSRC 生成规则
        String ssrc = playback
                ? SipRequestBuilder.newPlaybackSsrc()
                : SipRequestBuilder.newSsrc();
        // 打开配置文件指定端口的 Multiplex RTP Server
        int rtpPort = ensureFixedRtpServer();
        // 实际 MediaSource stream
        String zlmStreamId = toZlmStreamId(ssrc);
        log.info(
                "[ZLM] 点播RTP参数: callId={} port={} ssrc={} zlmStreamId={}",
                callId,
                rtpPort,
                ssrc,
                zlmStreamId
        );
        StreamSession s = new StreamSession(
                callId,
                deviceId,
                channelId,
                d.getHost(),
                d.getPort(),
                rtpPort
        );
        s.setSsrc(ssrc);
        // From-tag 是 dialog 标识（RFC 3261）：缺失时 INVITE/ACK/BYE/INFO
        // 的 From 头全是字面量 tag=null，严格设备可能判 dialog 非法而终话
        s.setFromTag(SipRequestBuilder.newTag());
        s.setType(playback ? StreamSession.Type.PLAYBACK : StreamSession.Type.LIVE);
        // 回放/下载区间存入会话（此前漏存导致三处问题）：
        // ① startPlaybackOrDownload 幂等复用比较 getPlaybackStart()==startTime
        //    永远不命中（恒 0），同区间重复请求每次都 BYE+INVITE 拆建——
        //    恰是该方法注释声明的"摄像机宕机典型诱因"；
        // ② 回放控制 resume/seek 的 MANSRTSP PLAY 区间无数据（PLAY 0-0）；
        // ③ 下载硬超时的预期时长推算恒走 30 分钟兜底
        if (playback) {
            s.setPlaybackStart(startTime);
            s.setPlaybackEnd(endTime);
        }
        s.setZlm(true);
        // 这是公共 Multiplex RTP Server 的 ID
        s.setZlmRequestStreamId(FIXED_RTP_SERVER_ID);
        // 这是实际 MediaSource 的 stream
        s.setZlmStreamId(zlmStreamId);
        s.setState(StreamSession.State.INVITING);
        // 先注册，防止设备快速回复 200 OK 时找不到会话
        sessionsByCallId.put(callId, s);
        sessionsByKey.put(
                key(deviceId, channelId),
                s
        );
        // 按回放/实时分别构造经 ZLM 收流的 INVITE 报文
        byte[] invite = playback
                ? SipRequestBuilder.playbackInviteViaZlm(
                props,
                d,
                channelId,
                callId,
                s.getFromTag(),
                s.getBranch(),
                mediaProps.getZlm().getMediaIp(),
                rtpPort,
                ssrc,
                startTime,
                endTime)
                : SipRequestBuilder.inviteViaZlm(
                props,
                d,
                channelId,
                callId,
                s.getFromTag(),
                s.getBranch(),
                mediaProps.getZlm().getMediaIp(),
                rtpPort,
                ssrc);
        try {
            sipServer.send(
                    new InetSocketAddress(
                            d.getHost(),
                            d.getPort()
                    ),
                    invite
            );
        } catch (Exception e) {
            // 发送失败：回收双索引，但不能关闭配置端口上的公共 RTP Server
            sessionsByCallId.remove(callId, s);
            sessionsByKey.remove(
                    key(deviceId, channelId),
                    s
            );
            // 这里不能关闭公共 RTP Server，
            // 它由配置文件指定端口、所有会话共用。
            s.setState(StreamSession.State.STOPPED);
            throw new IllegalStateException(
                    "发送 INVITE 失败: " + e.getMessage(),
                    e
            );
        }
        s.setInvitePacket(invite);
        scheduleRetransmit(
                s,
                invite,
                d
        );
        log.info(
                "[ZLM] {} INVITE 已发送: device={} channel={} callId={} " +
                        "zlmPort={} requestStreamId={} zlmStreamId={} ssrc={}",
                playback ? "回放" : "点播",
                deviceId,
                channelId,
                callId,
                rtpPort,
                s.getZlmRequestStreamId(),
                s.getZlmStreamId(),
                ssrc
        );
        return s;
    }

    /** 移除双索引 → BYE → 清理 ZLM。 */
    private void stopInternal(String deviceId,
                              String channelId) {
        StreamSession s = sessionsByKey.remove(
                key(deviceId, channelId)
        );
        if (s == null) {
            return;
        }
        sessionsByCallId.remove(
                s.getCallId(),
                s
        );
        sendByeQuietly(s);
        cleanup(s);
    }

    /** 发 SIP BYE 给设备。 */
    private void sendByeQuietly(StreamSession s) {
        try {
            sipServer.send(
                    new InetSocketAddress(
                            s.getDeviceHost(),
                            s.getDevicePort()
                    ),
                    SipRequestBuilder.bye(
                            props,
                            s
                    )
            );
        } catch (Exception e) {
            log.warn(
                    "[ZLM] BYE 发送失败: callId={}",
                    s.getCallId(),
                    e
            );
        }
    }

    /**
     * 释放 ZLM MediaSource。
     *
     * <p>注意：配置文件指定端口（示例 30002）上的公共 Multiplex RTP Server，
     * 单路停止时绝对不能 closeRtpServer。
     */
    private void cleanup(StreamSession s) {
        String actualStreamId = s.getZlmStreamId();
        try {
            // 仅关闭该会话对应的实际媒体流，公共 RTP Server 保持存活
            if (actualStreamId != null
                    && !actualStreamId.isEmpty()) {
                boolean closed = zlm.closeStreams(
                        mediaProps.getZlm().getRtpApp(),
                        actualStreamId
                );
                log.info(
                        "[ZLM] 关闭实际媒体流: stream={} result={}",
                        actualStreamId,
                        closed
                );
            }
        } catch (Exception e) {
            log.warn(
                    "[ZLM] 释放实际媒体流失败: stream={}",
                    actualStreamId,
                    e
            );
        }
        // 下载会话：等 ffmpeg 优雅退出（ZLM 已断流 → HTTP-FLV EOF）并收尾 MP4。
        // 优雅等待(最长60s)绝不能在会话锁内同步执行——否则同通道的点播/回放/
        // 重新下载会被阻塞最多 60s，故异步收尾（finalize 幂等且有锁保护）
        if (s.getType() == StreamSession.Type.DOWNLOAD) {
            stopZlmDownloadFfmpegAsync(s);
        }
        s.setState(
                StreamSession.State.STOPPED
        );
        log.info(
                "[ZLM] 会话清理完成: callId={} channel={} " +
                        "requestStreamId={} actualStream={}",
                s.getCallId(),
                s.getChannelId(),
                s.getZlmRequestStreamId(),
                actualStreamId
        );
    }

    // =========================================================
    // 下载（ZLM 收流 + 本服务 ffmpeg 拉流转 MP4）
    // =========================================================

    /**
     * 启动下载管道（异步，不阻塞 INVITE 返回）。
     *
     * <p>设备把回放 PS 推给 ZLM 后，ZLM 注册 rtp/&lt;ssrc&gt; MediaSource；
     * 本服务 ffmpeg 再以 HTTP-FLV 从 ZLM 拉流转码 MP4。倍速由 INVITE SDP
     * 协商（设备不支持则按 1x，下载仍可完成，仅耗时更长——前端遮罩文案
     * 已按此语义描述）。
     */
    private void startZlmDownload(StreamSession s) {
        ffmpegExecutor.submit(() -> runZlmDownload(s));
    }

    private void runZlmDownload(StreamSession s) {
        // 等 MediaSource 就绪（INVITE → 200 OK → RTP 到达 + PS 解封装）
        if (!waitMediaReady(s, DOWNLOAD_MEDIA_READY_WAIT_MILLIS)) {
            log.warn("[ZLM] 下载媒体流建立超时，收尾会话: callId={} channel={}",
                    s.getCallId(), s.getChannelId());
            stop(s.getDeviceId(), s.getChannelId());
            return;
        }

        Process p;
        try {
            p = buildZlmDownloadFfmpeg(s);
        } catch (Exception e) {
            log.error("[ZLM] 下载 ffmpeg 启动失败: callId={} channel={}",
                    s.getCallId(), s.getChannelId(), e);
            stop(s.getDeviceId(), s.getChannelId());
            return;
        }

        s.setFfmpeg(p);
        log.info("[ZLM] 下载管道已启动: callId={} stream={} file={}",
                s.getCallId(), s.getZlmStreamId(), s.getDownloadFileName());

        // stderr：进度 time= → outTimeSec（/api/download/status 的真实进度），
        // 诊断行打印（输入解析失败/编码错误等的唯一定位依据）
        ffmpegExecutor.submit(
                () -> drainZlmFfmpegLog(s.getCallId(), p.getErrorStream()));

        // 进程退出即设备推流结束（ZLM 断开 HTTP-FLV → ffmpeg EOF）→ 收尾改名
        ffmpegExecutor.submit(() -> awaitZlmFfmpegExit(s, p));
    }

    /** 轮询等待 ZLM 注册 MediaSource（与 MediaService.waitZlmReady 同语义）。 */
    private boolean waitMediaReady(StreamSession s, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (s.getState() == StreamSession.State.STOPPED) {
                return false;
            }
            if (isMediaReady(s)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isMediaReady(s);
    }

    /**
     * 构造下载 ffmpeg：HTTP-FLV 拉流 → x264 离线转码 → MP4 .part。
     *
     * <p>转码参数与 local 模式下载完全一致（preset/crf/g/profile/faststart），
     * 仅输入源不同：local 是 RTP PS 管道，ZLM 是 HTTP-FLV（PS 已由 ZLM
     * 解封装，PCMA 透传，此处统一重编码为 AAC 保兼容）。
     */
    private Process buildZlmDownloadFfmpeg(StreamSession s) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(mediaProps.getLocal().getFfmpegPath());
        // HTTP 输入连接/读超时 10s（微秒）：防 ZLM 侧异常时 ffmpeg 永久挂起。
        // 不加 reconnect：流 EOF 即设备推流结束，重连只会拖住收尾
        cmd.add("-timeout");
        cmd.add("10000000");
        cmd.add("-i");
        cmd.add(localFlvUrl(s));
        cmd.add("-map");
        cmd.add("0:v:0");
        cmd.add("-map");
        cmd.add("0:a:0?");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add(mediaProps.getLocal().getDownloadPreset());
        cmd.add("-crf");
        cmd.add(String.valueOf(mediaProps.getLocal().getDownloadCrf()));
        cmd.add("-profile:v");
        cmd.add("high");
        cmd.add("-g");
        cmd.add("50");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("32k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add("-f");
        cmd.add("mp4");
        Path dir = Paths.get(mediaProps.getLocal().getDownloadDir())
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);
        cmd.add(dir.resolve(s.getDownloadFileName() + ".part").toString());

        log.info("[ZLM] ffmpeg下载命令: callId={} cmd={}",
                s.getCallId(), String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    /**
     * sip-server 本机访问 ZLM 的 HTTP-FLV 拉流地址。
     *
     * <p>与给浏览器的 {@link #flvUrl} 区别在 host：ffmpeg 跑在本服务进程，
     * 必须用 api-base（服务端实际地址）；play-host 是浏览器可达地址
     * （可能是 NAT 映射后的外部 IP），本机反而不可达。
     */
    private String localFlvUrl(StreamSession s) {
        return mediaProps.getZlm().getApiBase()
                + "/"
                + mediaProps.getZlm().getRtpApp()
                + "/"
                + s.getZlmStreamId()
                + ".live.flv";
    }

    /** 消费 ffmpeg stderr：进度行解析 time=，其余按行打印（与 local 链路同策略）。 */
    private void drainZlmFfmpegLog(String callId, InputStream in) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (isFfmpegProgressLine(line)) {
                    trackZlmFfmpegOutTime(callId, line);
                    continue;
                }
                if (isFfmpegErrorLine(line)) {
                    log.warn("[ZLM] ffmpeg[{}] {}", callId, line);
                } else {
                    log.info("[ZLM] ffmpeg[{}] {}", callId, line);
                }
            }
            log.warn("[ZLM] ffmpeg[{}] stderr 已关闭（进程退出）", callId);
        } catch (Exception e) {
            log.debug("[ZLM] ffmpeg[{}] stderr 读取结束: {}", callId, e.getMessage());
        }
    }

    /** ffmpeg 周期性进度行（无诊断价值，避免刷屏）。 */
    private static boolean isFfmpegProgressLine(String line) {
        return line.startsWith("frame=")
                || line.startsWith("size=")
                || (line.contains("fps=") && line.contains("bitrate="));
    }

    /** ffmpeg 错误特征行（WARN 级别突出显示）。 */
    private static boolean isFfmpegErrorLine(String line) {
        String lower = line.toLowerCase();
        return lower.contains("error")
                || lower.contains("invalid")
                || lower.contains("unable")
                || lower.contains("could not")
                || lower.contains("cannot")
                || lower.contains("failed")
                || lower.contains("no such")
                || lower.contains("broken pipe")
                || lower.contains("trailing");
    }

    /** 从进度行解析输出时间（秒）写入会话，供 /api/download/status 查询。 */
    private void trackZlmFfmpegOutTime(String callId, String line) {
        Matcher m = FFMPEG_TIME_PATTERN.matcher(line);
        if (!m.find()) {
            return;
        }
        double sec = Long.parseLong(m.group(1)) * 3600.0
                + Long.parseLong(m.group(2)) * 60.0
                + Double.parseDouble(m.group(3));
        StreamSession s = sessionsByCallId.get(callId);
        if (s != null) {
            s.setFfmpegOutTimeSec(sec);
        }
    }

    /** 等待 ffmpeg 自然退出（设备推流结束）并收尾 MP4。 */
    private void awaitZlmFfmpegExit(StreamSession s, Process p) {
        // 硬上限：预期时长(按倍速折算)×10，最短 30 分钟。
        // 无上限 waitFor 在 ZLM 不断流时（设备故障持续推流）会永久阻塞本线程
        long expected = s.getPlaybackEnd() > s.getPlaybackStart()
                ? (long) ((s.getPlaybackEnd() - s.getPlaybackStart())
                        / Math.max(s.getPlaybackScale(), 0.1) * 1000.0)
                : 0L;
        long maxWait = Math.max(expected * 10, DOWNLOAD_HARD_TIMEOUT_MIN_MILLIS);
        try {
            if (!p.waitFor(maxWait, TimeUnit.MILLISECONDS)) {
                log.warn("[ZLM] 下载 ffmpeg 硬超时({}ms)未退出，强制结束(文件可能不完整): "
                                + "callId={} file={}",
                        maxWait, s.getCallId(), s.getDownloadFileName());
                p.destroyForcibly();
                p.waitFor(3, TimeUnit.SECONDS);
            } else {
                log.info("[ZLM] 下载 ffmpeg 退出: callId={} exit={} file={}",
                        s.getCallId(), p.exitValue(), s.getDownloadFileName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        } finally {
            finalizeZlmDownload(s);
            // 设备推流结束（ZLM 断开 HTTP-FLV → ffmpeg EOF）即下载完成：
            // 收尾会话（BYE + 关闭 ZLM 媒体流 + STOPPED），避免会话表残留
            // 影响下次同时间段请求的复用判断。cleanup 内的异步收尾对已退出
            // 进程会立即返回（finalize 幂等且有锁保护，无二次收尾风险）
            stop(s.getDeviceId(), s.getChannelId());
        }
    }

    /**
     * 停止下载会话的 ffmpeg 并收尾 MP4（平台主动停止/拆建时由 cleanup 调用）。
     *
     * <p>输入是 HTTP-FLV（无 stdin 句柄），不能像 local 的 pipe 管道那样
     * 关 stdin 触发 EOF；ZLM 侧 MediaSource 已由上层 closeStreams 注销，
     * ffmpeg 的 HTTP 连接随之 EOF，通常已自行退出或即将退出。
     *
     * <p>本方法在会话锁内被调用，优雅退出等待（含 moov/faststart 重排，
     * 最长 60s）提交到 ffmpegExecutor 异步执行，避免阻塞同通道后续操作；
     * 超时才强杀兜底（此时文件已损坏，仅防线程/进程泄漏）。
     */
    private void stopZlmDownloadFfmpegAsync(StreamSession s) {
        Process p = s.getFfmpeg();
        s.setFfmpeg(null);
        if (p == null) {
            // 无进程可等（如 awaitZlmFfmpegExit 已收尾）：仅做幂等收尾
            finalizeZlmDownload(s);
            return;
        }
        ffmpegExecutor.submit(() -> {
            try {
                if (!p.waitFor(FFMPEG_DOWNLOAD_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    log.warn("[ZLM] 下载 ffmpeg 优雅退出超时({}ms)，强制结束: callId={}",
                            FFMPEG_DOWNLOAD_WAIT_MILLIS, s.getCallId());
                    p.destroyForcibly();
                    p.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            finalizeZlmDownload(s);
        });
    }

    /**
     * 下载收尾：.part → 正式 MP4（改名失败时退化为复制）。
     *
     * <p>与 MediaService local 链路同策略：Windows 上浏览器"边下边传"
     * 持有 .part 句柄时 Files.move 会 sharing violation，退化为复制
     * （读 .part 不受锁影响）。幂等：重复调用/最终文件已存在时安全。
     */
    /** 收尾互斥锁：ffmpeg 自然退出与主动停止两条路径并发收尾时串行化。 */
    private final Object downloadFinalizeLock = new Object();

    private void finalizeZlmDownload(StreamSession s) {
        // awaitZlmFfmpegExit（自然退出）与异步主动收尾可能并发到达：
        // 加锁串行化，避免对同一 .part 双重 move/copy 竞态
        synchronized (downloadFinalizeLock) {
            try {
                Path dir = Paths.get(mediaProps.getLocal().getDownloadDir())
                        .toAbsolutePath().normalize();
                Path part = dir.resolve(s.getDownloadFileName() + ".part");
                Path done = dir.resolve(s.getDownloadFileName());
                if (Files.exists(part) && !Files.exists(done)) {
                    try {
                        Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception moveErr) {
                        Files.copy(part, done, StandardCopyOption.REPLACE_EXISTING);
                        log.info("[ZLM] 下载完成(复制收尾，.part由下载端关闭后清理): {}",
                                s.getDownloadFileName());
                        return;
                    }
                    log.info("[ZLM] 下载完成: {}", s.getDownloadFileName());
                } else if (Files.exists(part)) {
                    // 最终文件已存在（重复下载同一时间段）：清理本轮 .part 残留，
                    // 避免下载列表出现重复条目。ffmpeg 已退出，删除不会因句柄占用失败
                    try {
                        Files.deleteIfExists(part);
                    } catch (Exception ignore) {
                        // 删除失败不影响主流程
                    }
                }
            } catch (Exception e) {
                log.warn("[ZLM] 下载文件收尾失败: callId={} err={}",
                        s.getCallId(), e.getMessage());
            }
        }
    }

    /** 下载文件名（与 local 模式同格式：rec_通道_起_止.mp4）。 */
    private static String buildDownloadFileName(String channelId, long start, long end) {
        String st = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(start), ZoneId.of("+8"))
                .format(DL_NAME_TIME);
        String et = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(end), ZoneId.of("+8"))
                .format(DL_NAME_TIME);
        return "rec_" + channelId + "_" + st + "_" + et + ".mp4";
    }

    // =========================================================
    // INVITE 重传 / 超时
    // =========================================================

    /** INVITE UDP 重传 + 超时清理。 */
    private void scheduleRetransmit(StreamSession s,
                                    byte[] invite,
                                    Device d) {
        // 按退避表逐次投递重传任务
        for (int i = 0;
             i < INVITE_RETRANSMIT_DELAYS.length;
             i++) {
            long delay =
                    INVITE_RETRANSMIT_DELAYS[i];
            int attempt = i + 1;
            scheduler.schedule(() -> {
                // 会话已非 INVITING 或已被新会话替换：放弃重传
                if (s.getState()
                        != StreamSession.State.INVITING
                        || sessionsByCallId.get(
                        s.getCallId()) != s) {
                    return;
                }
                try {
                    sipServer.send(
                            new InetSocketAddress(
                                    d.getHost(),
                                    d.getPort()
                            ),
                            invite
                    );
                    s.setInviteRetries(attempt);
                    log.warn(
                            "[ZLM] INVITE 重传第 {} 次: callId={}",
                            attempt,
                            s.getCallId()
                    );
                } catch (Exception e) {
                    log.warn(
                            "[ZLM] INVITE 重传失败: callId={}",
                            s.getCallId(),
                            e
                    );
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
        // 总超时兜底：仍无最终响应则自动清理会话
        scheduler.schedule(() -> {
            if (s.getState()
                    != StreamSession.State.INVITING
                    || sessionsByCallId.get(
                    s.getCallId()) != s) {
                return;
            }
            log.warn(
                    "[ZLM] INVITE 响应超时，自动清理: callId={}",
                    s.getCallId()
            );
            synchronized (lockOf(s.getKey())) {
                if (sessionsByCallId.get(
                        s.getCallId()) == s) {
                    sessionsByKey.remove(
                            s.getKey(),
                            s
                    );
                    sessionsByCallId.remove(
                            s.getCallId(),
                            s
                    );
                    cleanup(s);
                }
            }
        }, INVITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    // =========================================================
    // SIP 响应 / BYE 路由
    // =========================================================

    /** callId 是否属于 ZLM 会话。 */
    public boolean hasSession(String callId) {
        return callId != null
                && sessionsByCallId.containsKey(callId);
    }

    /** 按 Call-ID 查会话。 */
    public StreamSession getSession(String callId) {
        return callId == null
                ? null
                : sessionsByCallId.get(callId);
    }

    /**
     * 处理 INVITE 响应：
     * 200 OK → ACK。
     *
     * @param s          目标会话
     * @param statusCode SIP 状态码
     * @param toTag      对端 To-tag（200 OK 携带）
     * @param contact    对端 Contact 地址
     * @param sdpBody    对端应答 SDP（写入会话前将换行替换为可读分隔符）
     */
    public void handleResponse(StreamSession s,
                               int statusCode,
                               String toTag,
                               String contact,
                               String sdpBody) {
        if (s == null) {
            return;
        }
        synchronized (lockOf(s.getKey())) {
            // 会话已被替换：忽略过期响应
            if (sessionsByCallId.get(
                    s.getCallId()) != s) {
                return;
            }
            if (statusCode == 200
                    && s.getState()
                    == StreamSession.State.INVITING) {
                // 记录对端 To-tag
                if (toTag != null
                        && !toTag.isEmpty()) {
                    s.setToTag(toTag);
                }
                // 记录对端 Contact
                if (contact != null
                        && !contact.isEmpty()) {
                    s.setPeerContact(contact);
                }
                // 保存应答 SDP（换行折叠为 " | " 便于日志与存储）
                s.setPeerSdp(
                        sdpBody == null
                                ? ""
                                : sdpBody.replace(
                                "\r\n",
                                " | "
                        )
                );
                try {
                    // 回 ACK 完成三次握手，进入 PLAYING
                    sipServer.send(
                            new InetSocketAddress(
                                    s.getDeviceHost(),
                                    s.getDevicePort()
                            ),
                            SipRequestBuilder.ack(
                                    props,
                                    s
                            )
                    );
                    s.setState(
                            StreamSession.State.PLAYING
                    );
                    s.setStartTime(
                            System.currentTimeMillis()
                    );
                    log.info(
                            "[ZLM] {}协商成功: callId={} channel={} " +
                                    "zlmPort={} requestStreamId={} actualStream={}",
                            s.isPlayback()
                                    ? "回放"
                                    : "点播",
                            s.getCallId(),
                            s.getChannelId(),
                            s.getLocalPort(),
                            s.getZlmRequestStreamId(),
                            s.getZlmStreamId()
                    );
                } catch (Exception e) {
                    // ACK 失败：回收会话并清理媒体流
                    log.error(
                            "[ZLM] ACK 发送失败: callId={}",
                            s.getCallId(),
                            e
                    );
                    sessionsByKey.remove(
                            s.getKey(),
                            s
                    );
                    sessionsByCallId.remove(
                            s.getCallId(),
                            s
                    );
                    cleanup(s);
                }
            } else if (statusCode >= 400) {
                // 设备明确拒绝：回收会话并清理
                log.warn(
                        "[ZLM] 设备拒绝: status={} callId={}",
                        statusCode,
                        s.getCallId()
                );
                sessionsByKey.remove(
                        s.getKey(),
                        s
                );
                sessionsByCallId.remove(
                        s.getCallId(),
                        s
                );
                cleanup(s);
            }
        }
    }

    /** 设备主动 BYE。 */
    public void handleDeviceBye(String callId) {
        StreamSession s =
                sessionsByCallId.get(callId);
        if (s == null) {
            return;
        }
        synchronized (lockOf(s.getKey())) {
            if (sessionsByCallId.get(callId) != s) {
                return;
            }
            log.info(
                    "[ZLM] 设备主动 BYE: callId={} channel={}",
                    callId,
                    s.getChannelId()
            );
            sessionsByKey.remove(
                    s.getKey(),
                    s
            );
            sessionsByCallId.remove(
                    s.getCallId(),
                    s
            );
            cleanup(s);
        }
    }

    /** 全部 ZLM 会话快照。 */
    public List<StreamSession> sessions() {
        return new ArrayList<>(
                sessionsByKey.values()
        );
    }

    // =========================================================
    // 平台关闭
    // =========================================================

    /**
     * 平台关闭：
     * 1. 停止所有会话
     * 2. 关闭配置端口上的公共 Multiplex RTP Server
     */
    @EventListener
    public void onShutdown(
            org.springframework.context.event.ContextClosedEvent event) {
        // 先逐路发 BYE 并清理会话
        for (StreamSession s :
                new ArrayList<>(sessionsByKey.values())) {
            try {
                sendByeQuietly(s);
            } catch (Exception ignore) {
            }
            sessionsByKey.remove(
                    s.getKey(),
                    s
            );
            sessionsByCallId.remove(
                    s.getCallId(),
                    s
            );
            cleanup(s);
        }
        // 最后关闭公共 RTP Server（与单路停止相反，此处需要关闭）
        try {
            if (fixedRtpPort > 0) {
                boolean closed =
                        zlm.closeRtpServer(
                                FIXED_RTP_SERVER_ID
                        );
                log.info(
                        "[ZLM] 关闭固定RTP复用服务器: streamId={} port={} result={}",
                        FIXED_RTP_SERVER_ID,
                        fixedRtpPort,
                        closed
                );
                fixedRtpPort = 0;
            }
        } catch (Exception e) {
            log.warn(
                    "[ZLM] 关闭固定RTP复用服务器失败",
                    e
            );
        }
        scheduler.shutdownNow();
        ffmpegExecutor.shutdownNow();
    }

    // =========================================================
    // ZLM 播放地址
    // =========================================================

    /** ZLM HTTP-FLV 播放地址。 */
    public String flvUrl(StreamSession s) {
        return zlmHost()
                + "/"
                + mediaProps.getZlm().getRtpApp()
                + "/"
                + s.getZlmStreamId()
                + ".live.flv";
    }

    /** ZLM WS-FLV 播放地址。 */
    public String wsFlvUrl(StreamSession s) {
        return "ws://"
                + playHost()
                + ":"
                + mediaProps.getZlm().getHttpPort()
                + "/"
                + mediaProps.getZlm().getRtpApp()
                + "/"
                + s.getZlmStreamId()
                + ".live.flv";
    }

    /** ZLM HLS 播放地址。 */
    public String hlsUrl(StreamSession s) {
        return zlmHost()
                + "/"
                + mediaProps.getZlm().getRtpApp()
                + "/"
                + s.getZlmStreamId()
                + "/hls.m3u8";
    }

    /**
     * ZLM WebRTC 信令地址。
     *
     * <p>必须使用实际 MediaSource stream。
     */
    public String webrtcUrl(StreamSession s) {
        String stream = s.getZlmStreamId();
        if (stream == null
                || stream.isBlank()) {
            return null;
        }
        return zlmHost()
                + "/index/api/webrtc?app="
                + mediaProps.getZlm().getRtpApp()
                + "&stream="
                + stream
                + "&type=play";
    }

    /** 浏览器可达的 ZLM HTTP 基址。 */
    private String zlmHost() {
        return "http://"
                + playHost()
                + ":"
                + mediaProps.getZlm().getHttpPort();
    }

    /** play-host 未配置时取 api-base 的 host。 */
    private String playHost() {
        String ph =
                mediaProps.getZlm().getPlayHost();
        if (ph != null
                && !ph.isBlank()) {
            return ph.trim();
        }
        String base =
                mediaProps.getZlm().getApiBase();
        // 从 api-base 中截出 host：定位 scheme 与端口冒号
        int scheme =
                base.indexOf("://");
        int colon =
                base.lastIndexOf(':');
        if (scheme > 0
                && colon > scheme) {
            return base.substring(
                    scheme + 3,
                    colon
            );
        }
        return scheme > 0
                ? base.substring(scheme + 3)
                : base;
    }

    /**
     * 判断 ZLM 是否已经注册实际 MediaSource。
     */
    public boolean isMediaReady(StreamSession s) {
        if (s == null || !s.isZlm()) {
            return false;
        }
        String actualStreamId =
                s.getZlmStreamId();
        if (actualStreamId == null
                || actualStreamId.isEmpty()) {
            return false;
        }
        return zlm.hasStream(
                mediaProps.getZlm().getRtpApp(),
                actualStreamId
        );
    }

    /** 按设备+通道获取当前 ZLM 会话。 */
    public StreamSession getSessionByKey(
            String deviceId,
            String channelId) {
        return sessionsByKey.get(
                key(deviceId, channelId)
        );
    }
}