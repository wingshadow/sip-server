package com.example.sip.service.media;

import com.example.sip.config.MediaProperties;
import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Device;
import com.example.sip.model.SipMessage;
import com.example.sip.model.StreamSession;
import com.example.sip.netty.UdpSipServer;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.service.local.FlvBroadcast;
import com.example.sip.service.zlm.ZlmMediaService;
import com.example.sip.sip.SdpParser;
import com.example.sip.sip.SdpParser.SdpInfo;
import com.example.sip.sip.SipRequestBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.*;
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
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一媒体服务（多端口模型）：直播 / 回放 / 下载三合一。
 *
 * <ul>
 *   <li>直播 LIVE：INVITE → 200 OK → ACK → ffmpeg PS 转 FLV → FlvBroadcast 实时分发；</li>
 *   <li>回放 PLAYBACK：带时间范围的 INVITE → 200 OK → ACK → ffmpeg 转 FLV 实时分发；</li>
 *   <li>下载 DOWNLOAD：带时间范围的 INVITE → 200 OK → ACK → ffmpeg 转 MP4 落盘。</li>
 * </ul>
 *
 * <p>多端口：每会话先从 {@link RtpPortPool} 预分配端口，再构造 StreamSession，
 * INVITE SDP 通告该端口，RtpReceiver 绑定独立 DatagramSocket 接收设备推流。
 *
 * <p>ZLM 模式（media.mode=zlm）：媒体面托管给 {@link ZlmMediaService}，本服务退化为门面。
 */
@Slf4j
@Service
public class MediaService {

    private static final long INVITE_TIMEOUT_MILLIS = 10_000;
    private static final long FFMPEG_WAIT_MILLIS = 2_000;

    /**
     * DOWNLOAD 会话等 ffmpeg 优雅退出的上限（毫秒）。
     *
     * <p>ffmpeg 收到 SIGTERM 后要 flush 编码队列 + 写 moov + faststart
     * 重排整个文件（2688x1520 数分钟录像达百 MB 级），2s 远远不够；
     * 超时会被 destroyForcibly 强杀，.part 缺失 moov → 最终文件损坏。
     */
    private static final long FFMPEG_DOWNLOAD_WAIT_MILLIS = 60_000;
    private static final long FLV_GRACE_MILLIS = 5_000;
    private static final long[] INVITE_RETRY_DELAYS = {500, 1000, 2000, 4000};

    /** 回放/下载会话媒体流看门狗的执行周期（秒） */
    private static final long STREAM_IDLE_CHECK_SECONDS = 3;

    /** 监控调度池核心线程数：看门狗 tick / 保活 INFO / 无观看者检查共用 */
    private static final int MONITOR_POOL_SIZE = 2;
    /** 收尾执行器线程数：doStop/stop 含 BYE 发送与 ffmpeg 优雅等待，需并行收尾能力 */
    private static final int MONITOR_ACTION_POOL_SIZE = 4;
    /** 收尾执行器有界队列容量：多会话同时触发看门狗时缓冲，满则 CallerRuns 兜底 */
    private static final int MONITOR_ACTION_QUEUE_SIZE = 512;
    /**
     * 直播保活 INFO 的起始 CSeq。
     * 会话内请求序列：INVITE=1、ACK=2，保活 INFO 从 3 起逐次递增；
     * BYE 沿用固定 {@code CSeq: 2}（设备侧实测宽容，见 SipRequestBuilder.bye）。
     */
    private static final int SESSION_KEEPALIVE_START_CSEQ = 3;
    private static final DateTimeFormatter DL_NAME_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final SipServerProperties props;
    private final MediaProperties mediaProps;
    private final DeviceRegistry registry;
    private final RtpReceiver rtpReceiver;
    private final UdpSipServer sipServer;
    private final ZlmMediaService zlmMediaService;

    private final Map<String, StreamSession> sessionsByCallId = new ConcurrentHashMap<>();
    private final Map<String, StreamSession> sessionsByKey = new ConcurrentHashMap<>();
    private final Map<String, FlvBroadcast> flvBroadcasts = new ConcurrentHashMap<>();
    private final Map<String, Object> playLocks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> monitorTasks = new ConcurrentHashMap<>();

    /**
     * INVITE 重传/超时调度池（4 线程）。
     *
     * <p>显式 {@link ScheduledThreadPoolExecutor}：一次性任务取消即出队，
     * 点播会话频繁创建/取消时不在 DelayedWorkQueue 残留到期条目。
     */
    private final ScheduledThreadPoolExecutor inviteScheduler =
            new ScheduledThreadPoolExecutor(4, r -> {
                Thread t = new Thread(r, "media-invite");
                t.setDaemon(true);
                return t;
            });

    /**
     * 监控调度池（看门狗 tick / 保活 INFO / 无观看者检查）。
     *
     * <p>显式 {@link ScheduledThreadPoolExecutor} 而非 {@code Executors} 工厂，
     * 核心收益 {@code setRemoveOnCancelPolicy(true)}：本场景每会话注册一个
     * 周期任务、随会话频繁创建/取消，开启后取消的任务立即从
     * {@code DelayedWorkQueue} 出队，不会残留到期条目（百路回放时
     * 队列内可少压数百个已取消 FutureTask）。
     */
    private final ScheduledThreadPoolExecutor monitorScheduler =
            new ScheduledThreadPoolExecutor(MONITOR_POOL_SIZE,
                    monitorThreadFactory("media-monitor"));

    /**
     * 监控命中后的重收尾执行器。
     *
     * <p>{@code doStop/stop} 是同步重操作：发 BYE、销毁 ffmpeg（DOWNLOAD 最多
     * 等 60s 优雅退出）、{@code finalizeDownload} 文件改名。若在 media-monitor
     * 线程内直接执行，多会话同时触发看门狗（或集中收尾）会占满仅有的监控
     * 线程，导致其他会话 tick 延迟、保活 INFO 延误。
     *
     * <p>定长线程 + 有界队列：常规并行收尾；突发积压进队列缓冲；
     * 队列满时 {@link ThreadPoolExecutor.CallerRunsPolicy} 退回调用线程
     * 同步执行（退化为改造前行为，保证任务不丢失）。
     */
    private final ExecutorService monitorActionExecutor = new ThreadPoolExecutor(
            MONITOR_ACTION_POOL_SIZE, MONITOR_ACTION_POOL_SIZE,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MONITOR_ACTION_QUEUE_SIZE),
            monitorThreadFactory("media-monitor-act"),
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 监控类线程工厂：命名 + daemon + 未捕获异常兜底（防异常静默杀死工作线程） */
    private static ThreadFactory monitorThreadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, e) ->
                    log.warn("[28181] 监控线程未捕获异常(线程存活): thread={} error={}",
                            th.getName(), e.toString()));
            return t;
        };
    }

    private final ExecutorService ffmpegExecutor = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r, "media-ffmpeg");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean shutdown = false;

    public MediaService(SipServerProperties props,
                        MediaProperties mediaProps,
                        DeviceRegistry registry,
                        RtpReceiver rtpReceiver,
                        UdpSipServer sipServer,
                        @Qualifier("zlmMediaService") ZlmMediaService zlmMediaService) {
        this.props = props;
        this.mediaProps = mediaProps;
        this.registry = registry;
        this.rtpReceiver = rtpReceiver;
        this.sipServer = sipServer;
        this.zlmMediaService = zlmMediaService;
        // 周期任务取消即出队：见 monitorScheduler 字段注释
        monitorScheduler.setRemoveOnCancelPolicy(true);
        inviteScheduler.setRemoveOnCancelPolicy(true);
        log.info("[28181] 统一媒体服务初始化: mode={} rtpPorts={}/{}",
                mediaProps.getMode(), rtpReceiver.availablePorts(), rtpReceiver.portCapacity());
    }

    // ==================== 直播 ====================

    /** 强制重新点播：停止旧会话并新建。 */
    public StreamSession startLive(String deviceId, String channelId) {
        synchronized (lockOf(key(deviceId, channelId))) {
            StreamSession old = sessionsByKey.get(key(deviceId, channelId));
            if (old != null) {
                doStop(old);
            }
            return doStartLive(deviceId, channelId);
        }
    }

    /** 幂等点播：已有可用会话直接复用（FLV 拉流入口共用）。 */
    public StreamSession getOrStartLive(String deviceId, String channelId) {
        synchronized (lockOf(key(deviceId, channelId))) {
            StreamSession s = sessionsByKey.get(key(deviceId, channelId));
            if (s != null && s.getState() != StreamSession.State.STOPPED) {
                return s;
            }
            return doStartLive(deviceId, channelId);
        }
    }

    private StreamSession doStartLive(String deviceId, String channelId) {
        Device d = registry.get(deviceId);
        if (d == null) {
            throw new IllegalStateException("设备未注册: " + deviceId);
        }

        // ZLM 模式：完全委托 ZlmMediaService（会话由其自行维护）
        if (mediaProps.isZlm()) {
            return zlmMediaService.getOrStartSession(deviceId, channelId);
        }

        String callId = SipRequestBuilder.newCallId();
        String ssrc = SipRequestBuilder.newSsrc();

        StreamSession s = new StreamSession(
                callId, deviceId, channelId, d.getHost(), d.getPort(),
                rtpReceiver.acquirePort());
        s.setType(StreamSession.Type.LIVE);
        s.setFromTag(SipRequestBuilder.newTag());
        s.setBranch(SipRequestBuilder.newBranch());
        s.setSsrc(ssrc);

        sessionsByCallId.put(callId, s);
        sessionsByKey.put(key(deviceId, channelId), s);

        sendInvite(s);
        return s;
    }

    // ==================== 回放 / 下载 ====================

    /** 发起回放（带时间范围）。 */
    public StreamSession startPlayback(String deviceId, String channelId,
                                       long startTime, long endTime) {
        return startPlaybackOrDownload(deviceId, channelId, startTime, endTime,
                StreamSession.Type.PLAYBACK, true);
    }

    /** 发起录像下载（带时间范围，MP4 落盘）。 */
    public StreamSession startDownload(String deviceId, String channelId,
                                       long startTime, long endTime) {
        return startPlaybackOrDownload(deviceId, channelId, startTime, endTime,
                StreamSession.Type.DOWNLOAD, true);
    }

    /** 幂等回放：已有会话且时间范围一致则复用。 */
    public StreamSession getOrStartPlayback(String deviceId, String channelId,
                                            long startTime, long endTime) {
        return startPlaybackOrDownload(deviceId, channelId, startTime, endTime,
                StreamSession.Type.PLAYBACK, false);
    }

    /** 幂等下载：已有同类型会话且时间范围一致则复用（避免重复拆建压设备）。 */
    public StreamSession getOrStartDownload(String deviceId, String channelId,
                                            long startTime, long endTime) {
        return startPlaybackOrDownload(deviceId, channelId, startTime, endTime,
                StreamSession.Type.DOWNLOAD, false);
    }

    private StreamSession startPlaybackOrDownload(String deviceId, String channelId,
                                                  long startTime, long endTime,
                                                  StreamSession.Type type,
                                                  boolean force) {
        synchronized (lockOf(key(deviceId, channelId))) {
            if (!force) {
                StreamSession s = sessionsByKey.get(key(deviceId, channelId));
                // 复用条件：会话存活 + 时间范围一致 + 同类型。
                // 类型必须一致：回放（FLV 实时分发）与下载（MP4 落盘）媒体形态不同，
                // DOWNLOAD 会话不能被 PLAYBACK 请求误复用（前端会拿到空播放地址）。
                if (s != null && s.getState() != StreamSession.State.STOPPED
                        && s.getPlaybackStart() == startTime
                        && s.getPlaybackEnd() == endTime
                        && s.getType() == type) {
                    return s;
                }
            }

            StreamSession old = sessionsByKey.get(key(deviceId, channelId));
            if (old != null) {
                doStop(old);
            }

            Device d = registry.get(deviceId);
            if (d == null) {
                throw new IllegalStateException("设备未注册: " + deviceId);
            }

            // ZLM 模式：回放/下载均由 ZlmMediaService 管理。
            // 下载链路 = ZLM 收流 + 本服务 ffmpeg 从 ZLM HTTP-FLV 拉流离线
            // 转码 MP4 落盘，前端下载端点/进度轮询与 local 完全同构
            if (mediaProps.isZlm()) {
                return zlmMediaService.startPlaybackOrDownload(
                        deviceId,
                        channelId,
                        startTime,
                        endTime,
                        type == StreamSession.Type.DOWNLOAD
                );
            }

            String callId = SipRequestBuilder.newCallId();

            StreamSession s = new StreamSession(
                    callId, deviceId, channelId, d.getHost(), d.getPort(),
                    rtpReceiver.acquirePort());
            s.setType(type);
            s.setFromTag(SipRequestBuilder.newTag());
            s.setBranch(SipRequestBuilder.newBranch());
            s.setSsrc(SipRequestBuilder.newPlaybackSsrc());
            s.setPlaybackStart(startTime);
            s.setPlaybackEnd(endTime);

            if (type == StreamSession.Type.DOWNLOAD) {
                s.setDownloadFileName(buildDownloadFileName(channelId, startTime, endTime));
                s.setDownloadUrl("/api/download/file?deviceId=" + deviceId
                        + "&channelId=" + channelId);
            }

            sessionsByCallId.put(callId, s);
            sessionsByKey.put(key(deviceId, channelId), s);

            sendInvite(s);
            return s;
        }
    }

    // ==================== 停止 ====================

    /** 停止指定设备/通道的媒体会话（发 BYE 并释放全部资源）。 */
    public void stop(String deviceId, String channelId) {
        if (mediaProps.isZlm()) {
            zlmMediaService.stop(deviceId, channelId);
            return;
        }
        synchronized (lockOf(key(deviceId, channelId))) {
            StreamSession s = sessionsByKey.get(key(deviceId, channelId));
            if (s != null) {
                doStop(s);
            }
        }
    }

    /** 活跃会话数（点播/回放/下载，供监控指标采集，只读） */
    public int activeSessionCount() {
        return sessionsByKey.size();
    }

    /** 存活的 ffmpeg 子进程数（local 媒体链路；zlm 模式恒为 0，供监控指标采集，只读） */
    public int activeFfmpegCount() {
        int n = 0;
        for (StreamSession s : sessionsByKey.values()) {
            Process p = s.getFfmpeg();
            if (p != null && p.isAlive()) {
                n++;
            }
        }
        return n;
    }

    /** FLV 最后观看者断开：仍有观众则保留；直播立即停止；回放/下载给 5 秒宽限。 */
    public void stopIfNoClients(StreamSession s) {
        if (s == null) {
            return;
        }
        // 与 1.0 一致：仍有观看者时保留会话（FLV 断连重连不拆流）
        FlvBroadcast bc = flvBroadcasts.get(s.getCallId());
        if (bc != null && bc.getSubscriberCount() > 0) {
            return;
        }
        if (bc == null) {
            return;
        }
        if (s.isPlayback()) {
            monitorScheduler.schedule(() -> {
                FlvBroadcast b = flvBroadcasts.get(s.getCallId());
                if (b == null || b.getSubscriberCount() == 0) {
                    log.info("[28181] 回放/下载会话无观看者，停止: callId={}", s.getCallId());
                    // stop 内含 synchronized(lockOf) 阻塞等待，同样从监控线程剥离
                    submitCleanup(() -> stop(s.getDeviceId(), s.getChannelId()));
                }
            }, FLV_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            return;
        }
        log.info("[28181] 直播会话无观看者，停止: callId={}", s.getCallId());
        stop(s.getDeviceId(), s.getChannelId());
    }

    private void doStop(StreamSession s) {
        doStop(s, true);
    }

    /**
     * 停止会话并释放资源。
     *
     * @param sendBye 是否向设备回发 BYE。平台主动停止时回发；
     *                 设备已主动发 BYE 时不能再回发（否则 dialog 错乱）。
     */
    private void doStop(StreamSession s, boolean sendBye) {
        if (s == null) {
            return;
        }
        sessionsByCallId.remove(s.getCallId(), s);
        sessionsByKey.remove(s.getKey(), s);

        cancelTask(timeoutTasks, s.getCallId());
        cancelTask(monitorTasks, s.getCallId());

        if (sendBye) {
            try {
                sipServer.send(
                        new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                        SipRequestBuilder.bye(props, s));
            } catch (Exception e) {
                log.warn("[28181] BYE 发送失败: callId={} error={}", s.getCallId(), e.getMessage());
            }
        }

        cleanup(s);
    }

    // ==================== 设备侧消息 ====================

    /** SIP 响应统一处理（200 OK / 4xx-6xx）。 */
    public void handleResponse(SipMessage msg) {
        String callId = msg.getHeader("Call-ID");
        int status = msg.statusCode();

        // ZLM 模式：会话由 ZlmMediaService 维护，按 Call-ID 转发
        if (mediaProps.isZlm()) {
            StreamSession z = zlmMediaService.getSession(callId);
            if (z != null) {
                zlmMediaService.handleResponse(
                        z,
                        status,
                        SipRequestBuilder.extractTag(msg.getHeader("To")),
                        msg.getHeader("Contact"),
                        msg.getBodyText(StandardCharsets.UTF_8));
            }
            return;
        }

        StreamSession s = sessionsByCallId.get(callId);
        if (s == null) {
            return;
        }

        log.info("[28181] 媒体INVITE响应: callId={} status={} state={}",
                callId, status, s.getState());

        if (s.getState() != StreamSession.State.INVITING) {
            return;
        }

        if (status == 200) {
            onInviteOk(s, msg);
        } else if (status >= 400) {
            log.warn("[28181] 设备拒绝点播/回放: callId={} status={} reason={}",
                    callId, status, msg.reasonPhrase());
            doStop(s);
        }
    }

    /** 200 OK：登记媒体参数 → 启动媒体管道 → 发 ACK。 */
    private void onInviteOk(StreamSession s, SipMessage msg) {
        try {
            SdpInfo peerSdp = SdpParser.parse(msg.getBodyText(StandardCharsets.UTF_8));
            s.setPeerSdp(peerSdp.toSummary());
            s.setPeerPayload(peerSdp.mediaPayloads);
            // 与 1.0 一致：SSRC 在 INVITE 时生成并通告（SDP y=），200 OK 不覆盖。
            // RtpReceiver 按端口归属（每会话专属 socket），不按 SSRC 匹配；真正的
            // 风险是覆盖后 ACK/BYE/INFO 回显、zlm streamId、接口返回三方与
            // INVITE 通告值不一致
        } catch (Exception e) {
            log.warn("[28181] 200 OK SDP 解析失败: callId={} error={}",
                    s.getCallId(), e.getMessage());
        }

        cancelTask(timeoutTasks, s.getCallId());

        try {
            startMediaPipeline(s);
            sipServer.send(
                    new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                    SipRequestBuilder.ack(props, s));
            s.setState(StreamSession.State.PLAYING);
            s.setStartTime(System.currentTimeMillis());
            // 记录 200 OK 的 To-tag：ACK 已按原样发出，后续 INFO/BYE 等
            // 会话内请求必须携带该 tag，设备才能匹配到同一 Dialog
            s.setToTag(SipRequestBuilder.extractTag(msg.getHeader("To")));
            log.info("[28181] {}会话就绪: callId={} channel={} port={}",
                    s.isPlayback() ? "回放" : "直播",
                    s.getCallId(), s.getChannelId(), s.getLocalPort());
            sendDownloadControl(s);
            scheduleStreamWatchdog(s);
            scheduleSessionKeepalive(s);
        } catch (Exception e) {
            log.error("[28181] 媒体启动失败: callId={}", s.getCallId(), e);
            doStop(s);
        }
    }

    /**
     * 回放/下载会话媒体流看门狗。
     *
     * <p>设备推流结束应发 BYE 触发 {@link #handleDeviceBye} 收尾，但部分设备
     * 静默停止推流（日志表现为 ffmpeg 大量 h264 concealing / corrupt decoded
     * frame 后长时间无新数据）：会话不结束 → ffmpeg 不退出 → .part 不落定 →
     * 前端进度停在估算上限不动（实测 2688x1520 下载即此现象）。
     *
     * <p>按 {@link #STREAM_IDLE_CHECK_SECONDS} 周期检查 RTP 收包时间兜底：
     * <ul>
     *   <li>首包超过 {@code max(60, 4×空闲阈值)} 秒未到 → 设备未推流，收尾</li>
     *   <li>推流中超过 {@code download-idle-seconds} 秒无新包 → 判定推流
     *       结束/中断，主动 BYE 收尾：ffmpeg 优雅退出写 moov →
     *       {@link #finalizeDownload} 改名，已传部分仍可播放</li>
     *   <li>超过 10 倍预期时长（下限 30 分钟）仍在推 → 强制收尾，防设备无限灌流</li>
     * </ul>
     * 直播会话无预期结束时间，不启用（依赖无观看者停止）。
     */
    private void scheduleStreamWatchdog(StreamSession s) {
        if (!s.isPlayback()) {
            return;
        }

        int idleSeconds = Math.max(1, mediaProps.getLocal().getDownloadIdleSeconds());
        int firstPacketSeconds = Math.max(60, idleSeconds * 4);
        // 兜底上限按 10 倍预期预留：设备忽略倍速协商按 1x 重推长录像时
        // （1 小时录像实测 3600s > 3 倍预估 2700s）不会误杀；仅防设备无限灌流
        long maxPushSeconds = Math.max(1800, expectedPushSeconds(s) * 10);

        monitorTasks.put(s.getCallId(), monitorScheduler.scheduleWithFixedDelay(
                () -> watchdogTick(s, idleSeconds, firstPacketSeconds, maxPushSeconds),
                STREAM_IDLE_CHECK_SECONDS, STREAM_IDLE_CHECK_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * 直播会话保活（可选，{@code sip.server.session-keepalive-enabled} 默认关闭）。
     *
     * <p>部分设备侧会话超时（常见 300s+）会清理长时间无信令交互的 INVITE Dialog，
     * 7×24 长直播表现为媒体莫名中断、设备侧会话已注销。按配置间隔发送
     * <b>会话内空 INFO</b> 刷新 Dialog 计时——不改 SDP 协商、不触发媒体重指，
     * 是 re-INVITE 之外最安全的保活手段。
     *
     * <p>边界：
     * <ul>
     *   <li>仅 LIVE 会话：回放/下载有看门狗三段兜底，不叠加；</li>
     *   <li>仅 local 媒体模式：zlm 模式会话由 {@link ZlmMediaService} 托管，
     *       流保活由 ZLM 负责（本方法不会执行到）；</li>
     *   <li>发送失败仅记日志，<b>绝不主动 BYE</b>：避免把一次信令失败
     *       放大成误杀正常会话；设备真的下线时心跳超时会走离线清理；</li>
     *   <li>任务注册在 {@code monitorTasks}（直播无看门狗，键位不冲突），
     *       {@code doStop} 的 {@code cancelTask} 统一取消，无泄漏。</li>
     * </ul>
     */
    private void scheduleSessionKeepalive(StreamSession s) {
        if (!props.isSessionKeepaliveEnabled() || s.getType() != StreamSession.Type.LIVE) {
            return;
        }
        // 下限保护：配置过小会显著放大设备信令负载
        long interval = Math.max(5, props.getSessionKeepaliveSeconds());
        // 每会话独立计数：仅本任务线程访问，无需额外同步
        AtomicInteger cseq = new AtomicInteger(SESSION_KEEPALIVE_START_CSEQ);

        monitorTasks.put(s.getCallId(), monitorScheduler.scheduleWithFixedDelay(() -> {
            // 会话已被停止/替换（含重连替换）：双保险，cancelTask 之外再判一次
            if (shutdown || sessionsByCallId.get(s.getCallId()) != s
                    || s.getState() != StreamSession.State.PLAYING) {
                return;
            }
            try {
                int c = cseq.getAndIncrement();
                sipServer.send(
                        new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                        SipRequestBuilder.info(props, s, c));
                log.debug("[28181] 直播保活 INFO 已发送: callId={} cseq={}",
                        s.getCallId(), c);
            } catch (Exception e) {
                // 保活失败不拆会话：仅记日志，等下一轮重试
                log.warn("[28181] 直播保活 INFO 发送失败(不影响会话): callId={} error={}",
                        s.getCallId(), e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS));

        log.info("[28181] 直播保活已启用: callId={} interval={}s", s.getCallId(), interval);
    }

    /** 看门狗单次检查：推流中断/超限即主动收尾会话 */
    private void watchdogTick(StreamSession s, int idleSeconds,
                              int firstPacketSeconds, long maxPushSeconds) {
        try {
            // 会话已被停止/替换：看门狗随 doStop 的 cancelTask 取消，双保险
            if (sessionsByCallId.get(s.getCallId()) != s
                    || s.getState() != StreamSession.State.PLAYING) {
                return;
            }

            long lastAt = rtpReceiver.lastPacketAt(s.getCallId());
            long now = System.currentTimeMillis();
            long sinceStartSec = (now - s.getStartTime()) / 1000;

            if (lastAt <= 0) {
                if (sinceStartSec > firstPacketSeconds) {
                    log.warn("[28181] 设备迟迟未推流({}s 无RTP首包)，收尾会话: callId={}",
                            firstPacketSeconds, s.getCallId());
                    submitCleanup(() -> doStop(s));
                }
                return;
            }

            long idleSec = (now - lastAt) / 1000;
            if (idleSec >= idleSeconds) {
                log.warn("[28181] 设备推流中断({}s 无RTP)，主动BYE收尾: callId={} packets={}",
                        idleSec, s.getCallId(), rtpReceiver.packetCount(s.getCallId()));
                submitCleanup(() -> doStop(s));
                return;
            }

            if (sinceStartSec > maxPushSeconds) {
                log.warn("[28181] 推流超过预期上限({}s)，强制收尾: callId={}",
                        maxPushSeconds, s.getCallId());
                submitCleanup(() -> doStop(s));
            }
        } catch (Exception e) {
            log.warn("[28181] 看门狗执行异常: callId={} error={}",
                    s.getCallId(), e.getMessage());
        }
    }

    /**
     * 监控命中后的收尾动作提交：重活交 {@link #monitorActionExecutor}，
     * 监控线程只做判定。停机/执行器已关时退回调用线程同步执行，任务不丢。
     */
    private void submitCleanup(Runnable action) {
        if (shutdown) {
            action.run();
            return;
        }
        try {
            monitorActionExecutor.execute(action);
        } catch (RejectedExecutionException e) {
            log.warn("[28181] 收尾执行器已拒绝(停机竞态)，同步执行: error={}", e.getMessage());
            action.run();
        }
    }

    /** 预期推流耗时（秒）= 录像时长 / 倍速；范围缺失返回 0（跳过时长兜底） */
    private long expectedPushSeconds(StreamSession s) {
        long start = s.getPlaybackStart();
        long end = s.getPlaybackEnd();
        if (start <= 0 || end <= start) {
            return 0;
        }
        int speed = Math.max(1, mediaProps.getLocal().getDownloadSpeed());
        return (end - start) / speed;
    }

    /**
     * 下载会话倍速控制（GB/T 28181-2022 MANSRTSP over SIP INFO）。
     *
     * <p>SDP {@code a=downloadspeed} 已在 INVITE 中完成倍速协商；此处再下发
     * {@code SCALE} 兜底——设备忽略 SDP 属性时，仍可通过会话内 INFO 调节倍速。
     *
     * <p>PLAY 默认不下发：设备在 ACK 后通常已开始推流，会话中途 PLAY 可能被
     * 设备理解为 seek 重推，造成录像时间跳变；仅当设备要求先收到 PLAY 才
     * 推流时，开启 {@code sip.media.local.download-mansrtsp-play}。
     */
    private void sendDownloadControl(StreamSession s) {
        if (s.getType() != StreamSession.Type.DOWNLOAD || mediaProps.isZlm()) {
            return;
        }

        int speed = mediaProps.getLocal().getDownloadSpeed();
        if (speed <= 1) {
            return;
        }

        try {
            InetSocketAddress addr = new InetSocketAddress(s.getDeviceHost(), s.getDevicePort());
            boolean play = mediaProps.getLocal().isDownloadMansrtspPlay();
            if (play) {
                sipServer.send(addr, SipRequestBuilder.mansrtspInfo(props, s,
                        SipRequestBuilder.mansrtspPlay(s.getPlaybackStart(), s.getPlaybackEnd())));
            }
            sipServer.send(addr, SipRequestBuilder.mansrtspInfo(props, s,
                    SipRequestBuilder.mansrtspScale(speed)));
            log.info("[28181] 已下发倍速控制: callId={} scale={} play={}",
                    s.getCallId(), speed, play);
        } catch (Exception e) {
            log.warn("[28181] 倍速控制下发失败: callId={} error={}",
                    s.getCallId(), e.getMessage());
        }
    }

    /**
     * 回放媒体控制（GB/T 28181-2022 MANSRTSP over SIP INFO）。
     *
     * <p>动作：{@code pause} 暂停 / {@code resume} 继续（PLAY 原区间）/
     * {@code scale} 倍速（speed） / {@code seek} 拖动（startTime）。
     * 设备以 SIP 200 OK 应答，执行结果以设备实际行为为准。
     *
     * <p>三条边界：<ul>
     *   <li>仅 PLAYBACK 会话：DOWNLOAD 会话暂停会停止 RTP 推流，
     *       触发看门狗「首包/空闲超时」拆会话（下载语义不同，不支持）；</li>
     *   <li>仅 local 模式：zlm 会话由 ZlmMediaService 托管 Dialog，
     *       此处拿不到 To-tag 等会话态，明确拒绝而非发残包；</li>
     *   <li>仅 PLAYING 状态：INVITING 中设备可能未就绪，控制无意义。</li>
     * </ul>
     *
     * @param action    pause / resume / scale / seek
     * @param speed     倍速（仅 scale），0.25~16，设备常用 1/2/4/8
     * @param seekStart 拖动目标起点 epoch 秒（仅 seek），须落在回放区间内
     */
    public Map<String, Object> playbackControl(String deviceId, String channelId,
                                               String action, double speed, long seekStart) {
        // 回放控制是纯 SIP INFO→设备信令（MANSRTSP），与媒体面（local 自研收流
        // 或 ZLM 收流）无关；ZLM 会话同样带 deviceHost/Port（BYE 同源），
        // 且 startInternal 已存 playback 区间，resume/seek 可用
        StreamSession s = getSession(deviceId, channelId);
        if (s == null) {
            throw new IllegalStateException("会话不存在: " + deviceId + ":" + channelId);
        }
        if (s.getType() != StreamSession.Type.PLAYBACK) {
            throw new IllegalArgumentException(
                    "仅回放会话支持媒体控制，当前会话类型: " + s.getType());
        }
        if (s.getState() != StreamSession.State.PLAYING) {
            throw new IllegalStateException("回放会话未就绪（state=" + s.getState() + "），请先开始回放");
        }

        String body;
        switch (action == null ? "" : action) {
            case "pause":
                body = SipRequestBuilder.mansrtspPause();
                break;
            case "resume":
                // 继续播放：PLAY 原区间（设备侧从暂停点恢复）
                body = SipRequestBuilder.mansrtspPlay(s.getPlaybackStart(), s.getPlaybackEnd());
                break;
            case "scale":
                if (speed < 0.25 || speed > 16) {
                    throw new IllegalArgumentException("倍速须在 0.25~16: " + speed);
                }
                body = SipRequestBuilder.mansrtspScale(speed);
                break;
            case "seek":
                if (seekStart < s.getPlaybackStart() || seekStart > s.getPlaybackEnd()) {
                    throw new IllegalArgumentException("拖动起点须在回放区间 ["
                            + s.getPlaybackStart() + ", " + s.getPlaybackEnd() + "] 内: " + seekStart);
                }
                body = SipRequestBuilder.mansrtspPlay(seekStart, s.getPlaybackEnd());
                break;
            default:
                throw new IllegalArgumentException("不支持的回放控制动作: " + action
                        + "，可选: pause/resume/scale/seek");
        }

        try {
            sipServer.send(new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                    SipRequestBuilder.mansrtspInfo(props, s, body));
        } catch (Exception e) {
            // 与倍速下发一致：信令发送失败不拆会话，仅记日志
            log.warn("[28181] 回放控制下发失败: callId={} action={} error={}",
                    s.getCallId(), action, e.getMessage());
            throw new IllegalStateException("回放控制下发失败: " + e.getMessage());
        }

        if ("scale".equals(action)) {
            s.setPlaybackScale(speed);
        }
        log.info("[28181] 回放控制: callId={} action={} body={}", s.getCallId(), action, body);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("callId", s.getCallId());
        r.put("deviceId", deviceId);
        r.put("channelId", channelId);
        r.put("action", action);
        r.put("mansrtsp", body);
        r.put("playbackScale", s.getPlaybackScale());
        r.put("playbackStart", s.getPlaybackStart());
        r.put("playbackEnd", s.getPlaybackEnd());
        return r;
    }

    /** 设备主动 BYE：按 callId 停止会话。 */
    public void handleDeviceBye(String callId) {
        if (mediaProps.isZlm()) {
            zlmMediaService.handleDeviceBye(callId);
            return;
        }
        StreamSession s = sessionsByCallId.get(callId);
        if (s == null) {
            return;
        }
        log.info("[28181] 设备主动BYE: callId={} channel={}", callId, s.getChannelId());
        // 设备已发 BYE，平台不再回发（与 1.0 一致）
        doStop(s, false);
    }

    public boolean hasSession(String callId) {
        if (mediaProps.isZlm()) {
            return zlmMediaService.hasSession(callId);
        }
        return sessionsByCallId.containsKey(callId);
    }

    public StreamSession getSession(String callId) {
        return sessionsByCallId.get(callId);
    }

    public StreamSession getSession(String deviceId, String channelId) {
        // ZLM 模式：会话由 ZlmMediaService 维护，必须委托查询，
        // 否则查的是本地空表（FlvController.redirectToZlm 曾因此永远 404）
        if (mediaProps.isZlm()) {
            return zlmMediaService.getSessionByKey(deviceId, channelId);
        }
        return sessionsByKey.get(key(deviceId, channelId));
    }

    public List<StreamSession> sessions() {
        if (mediaProps.isZlm()) {
            return zlmMediaService.sessions();
        }
        return new ArrayList<>(sessionsByKey.values());
    }

    /**
     * RTP 收流统计（本地模式）：用于判断媒体链路是否打通
     * （receivedPackets 持续增长说明设备推流到达 ffmpeg）。
     * ZLM 模式或无路由时返回 exists=false。
     */
    public Map<String, Object> rtpStats(String callId) {
        Map<String, Object> m = new LinkedHashMap<>();
        RtpReceiver.Session r = rtpReceiver.sessions().get(callId);
        if (r == null) {
            m.put("exists", false);
            return m;
        }
        m.put("exists", true);
        m.put("receivedPackets", r.getPackets());
        m.put("receivedBytes", r.getBytes());
        m.put("payloadType", r.getPayloadType());
        m.put("ssrcFromRtp", r.getSsrc());
        m.put("lostPackets", r.getLostPackets());
        m.put("rtpSrcAddr", r.getSrcAddr());
        return m;
    }

    // ==================== FLV 广播 ====================

    /**
     * 获取 / 懒创建会话 FLV 广播；等待会话进入 PLAYING（最多 8 秒）。
     */
    public FlvBroadcast getFlvBroadcast(StreamSession s) throws InterruptedException {
        if (s == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (mediaProps.isZlm()) {
            return null;
        }

        FlvBroadcast bc = flvBroadcasts.get(s.getCallId());
        if (bc != null) {
            return bc;
        }

        // 等待媒体就绪：仅依赖会话状态，绝不持有 flvBroadcasts 锁——
        // 否则新会话建流期间（最长 8s）全局锁被占用，其他会话的 FLV
        // 获取会被串行阻塞
        long deadline = System.currentTimeMillis() + 8_000;
        while (s.getState() != StreamSession.State.PLAYING
                && s.getState() != StreamSession.State.STOPPED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }

        if (s.getState() == StreamSession.State.STOPPED) {
            throw new IllegalStateException("会话已停止: " + s.getCallId());
        }

        if (s.getState() != StreamSession.State.PLAYING) {
            throw new IllegalStateException("等待媒体就绪超时: " + s.getCallId());
        }

        // 就绪后创建广播必须互斥（防并发对同一会话重复包装 ffmpeg stdout）
        synchronized (flvBroadcasts) {
            bc = flvBroadcasts.get(s.getCallId());
            if (bc == null) {
                bc = new FlvBroadcast(s, s.getFfmpeg().getInputStream());
                flvBroadcasts.put(s.getCallId(), bc);
            }
            return bc;
        }
    }

    // ==================== 下载 ====================

    /** 下载文件（按设备/通道定位会话）。 */
    public File downloadFile(String deviceId, String channelId) {
        // ZLM 模式：会话由 ZlmMediaService 维护，必须委托查询。
        // 本类的 sessionsByKey 在 zlm 模式下始终为空，直接查永远 null，
        // 前端下载状态/文件端点会误判"文件未生成"
        if (mediaProps.isZlm()) {
            StreamSession s = zlmMediaService.getSessionByKey(deviceId, channelId);
            if (s == null || s.getType() != StreamSession.Type.DOWNLOAD) {
                return null;
            }
            return downloadFile(s);
        }
        StreamSession s = sessionsByKey.get(key(deviceId, channelId));
        if (s == null || s.getType() != StreamSession.Type.DOWNLOAD) {
            return null;
        }
        return downloadFile(s);
    }

    /** 下载文件（按会话定位）。 */
    public File downloadFile(StreamSession s) {
        if (s == null || s.getType() != StreamSession.Type.DOWNLOAD) {
            return null;
        }
        Path dir = Paths.get(mediaProps.getLocal().getDownloadDir())
                .toAbsolutePath().normalize();
        Path file = dir.resolve(s.getDownloadFileName());
        if (Files.exists(file)) {
            return file.toFile();
        }
        Path part = dir.resolve(s.getDownloadFileName() + ".part");
        return Files.exists(part) ? part.toFile() : null;
    }

    // ==================== INVITE ====================

    /** 发送 INVITE（含重传调度 + 超时保护）。 */
    private void sendInvite(StreamSession s) {
        if (s.getLocalPort() <= 0) {
            log.error("[28181] 端口分配失败（池耗尽），停止会话: callId={}", s.getCallId());
            doStop(s);
            return;
        }

        try {
            byte[] invite = SipRequestBuilder.invite(
                    props, s.getDeviceHost(), s.getDevicePort(),
                    s.getChannelId(), s.getCallId(), s.getFromTag(),
                    s.getBranch(), s.getLocalPort(), s.getSsrc());
            if (s.isPlayback()) {
                // 回放/下载：改用带时间范围的 SDP；下载会话附加倍速属性
                // （GB/T 28181-2022 a=downloadspeed，设备不支持时按 1x 推流）
                int speed = s.getType() == StreamSession.Type.DOWNLOAD
                        ? mediaProps.getLocal().getDownloadSpeed()
                        : 0;
                if (speed > 1) {
                    log.info("[28181] 下载倍速: {}x channel={}", speed, s.getChannelId());
                }
                invite = SipRequestBuilder.playbackInvite(
                        props, s.getDeviceHost(), s.getDevicePort(),
                        s.getChannelId(), s.getCallId(), s.getFromTag(),
                        s.getBranch(), s.getLocalPort(), s.getSsrc(),
                        s.getPlaybackStart(), s.getPlaybackEnd(), speed);
            }

            s.setInvitePacket(invite);
            sipServer.send(
                    new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                    invite);

            // 登记 RTP 路由（绑定预分配端口）
            int bound = rtpReceiver.register(
                    s.getCallId(), s.getDeviceId(), s.getChannelId(),
                    s.getDeviceHost(), s.getDevicePort(), s.getSsrc(),
                    s.getLocalPort());
            if (bound < 0) {
                log.error("[28181] RTP端口绑定失败，停止会话: callId={} port={}",
                        s.getCallId(), s.getLocalPort());
                doStop(s);
                return;
            }

            scheduleRetransmit(s, 0);
            scheduleTimeout(s);

            log.info("[28181] {}INVITE已发送: callId={} channel={} port={} ssrc={}",
                    s.isPlayback() ? "回放" : "点播",
                    s.getCallId(), s.getChannelId(), s.getLocalPort(), s.getSsrc());
        } catch (Exception e) {
            log.error("[28181] INVITE 发送失败: callId={}", s.getCallId(), e);
            doStop(s);
        }
    }

    /** INVITE 重传（指数退避，最多 4 次）。 */
    private void scheduleRetransmit(StreamSession s, int attempt) {
        if (attempt >= INVITE_RETRY_DELAYS.length) {
            return;
        }
        if (shutdown || s.getState() != StreamSession.State.INVITING) {
            return;
        }

        inviteScheduler.schedule(() -> {
            if (shutdown || s.getState() != StreamSession.State.INVITING) {
                return;
            }
            try {
                if (s.getInvitePacket() != null) {
                    sipServer.send(
                            new InetSocketAddress(s.getDeviceHost(), s.getDevicePort()),
                            s.getInvitePacket());
                    s.setInviteRetries(s.getInviteRetries() + 1);
                    log.info("[28181] INVITE重传: callId={} retry={}",
                            s.getCallId(), s.getInviteRetries());
                }
                scheduleRetransmit(s, attempt + 1);
            } catch (Exception e) {
                log.warn("[28181] INVITE重传失败: callId={}", s.getCallId(), e);
            }
        }, INVITE_RETRY_DELAYS[attempt], TimeUnit.MILLISECONDS);
    }

    /** INVITE 超时保护：超时未应答则释放会话。 */
    private void scheduleTimeout(StreamSession s) {
        ScheduledFuture<?> f = inviteScheduler.schedule(() -> {
            if (s.getState() == StreamSession.State.INVITING) {
                log.warn("[28181] INVITE超时: callId={} channel={}",
                        s.getCallId(), s.getChannelId());
                doStop(s);
            }
        }, INVITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        timeoutTasks.put(s.getCallId(), f);
    }

    // ==================== 媒体管道 ====================

    /** 启动 ffmpeg 管道：RTP 载荷 → ffmpeg stdin → FLV/MP4 输出。 */
    private void startMediaPipeline(StreamSession s) throws Exception {
        // RTP 端口已在 sendInvite 阶段完成绑定，此处直接启动 ffmpeg，
        // 避免重复注册导致 DatagramSocket 关闭重建丢包
        Process p = buildFfmpeg(s);
        s.setFfmpeg(p);

        // 挂接 RTP 转发：设备推流 → SinkWriter 队列 → ffmpeg stdin
        // （直播/回放走 pipe:1 出 FLV，下载走文件落盘；不挂接则 ffmpeg 永远收不到数据）
        rtpReceiver.attachSink(s.getCallId(), p.getOutputStream());

        // ffmpeg stderr 是定位媒体链路问题的唯一依据（banner / stream mapping /
        // "Invalid data found" / 编码错误等），必须按行打印；
        // 一旦广播器未就绪 → HTTP 504 → 播放器 HttpStatusCodeInvalid，
        // 仅凭这里的日志即可定位 ffmpeg 侧根因
        ffmpegExecutor.submit(() -> drainFfmpegLog(s.getCallId(), p.getErrorStream()));
        if (s.getType() == StreamSession.Type.DOWNLOAD) {
            // MP4 落盘：stdout 不使用（二进制内容，静默丢弃，禁止打印）
            ffmpegExecutor.submit(() -> drainQuietly(p.getInputStream()));
        }

        log.info("[28181] ffmpeg媒体管道已启动: callId={} type={} port={}",
                s.getCallId(), s.getType(), s.getLocalPort());
    }

    private Process buildFfmpeg(StreamSession s) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(mediaProps.getLocal().getFfmpegPath());
        cmd.add("-f");
        cmd.add("mpeg");
        cmd.add("-i");
        cmd.add("pipe:0");
        cmd.add("-map");
        cmd.add("0:v:0");
        cmd.add("-map");
        cmd.add("0:a:0?");

        if (s.getType() == StreamSession.Type.DOWNLOAD) {
            // 离线转码压缩：保持设备原始分辨率，仅调码率控制。
            // preset/crf 经 sip.media.local.download-{preset,crf} 配置
            // （MediaProperties 已做取值校验），默认 medium + CRF 26，
            // 相比旧隐式 CRF23+ultrafast 体积约降 40~55%。
            // -g 50：监控内容 GOP 缩短，播放 seek 更快；-profile:v high 兼容性最好。
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
        } else {
            cmd.add("-c:v");
            cmd.add("copy");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("64k");
            cmd.add("-flvflags");
            cmd.add("no_duration_filesize");
            cmd.add("-f");
            cmd.add("flv");
            cmd.add("pipe:1");
        }

        log.info("[28181] ffmpeg命令: callId={} cmd={}", s.getCallId(), String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    /**
     * 消费并打印 ffmpeg stderr（排查媒体链路问题的关键日志）。
     *
     * <p>ffmpeg 将 banner、输入分析、stream mapping、错误诊断全部写到 stderr。
     * 典型可定位根因：
     * <ul>
     *   <li>{@code [mpeg @ ...] Invalid data found when processing input} —— 设备未推流 / RTP 未到达 / PS 格式不符</li>
     *   <li>{@code Could not find codec parameters / Output file #0 does not contain any stream} —— 未解析出视频流</li>
     *   <li>{@code Conversion failed!} —— 转码参数或 codec 不支持</li>
     *   <li>{@code Broken pipe} —— 广播器已关闭而 ffmpeg 仍在写 stdout</li>
     * </ul>
     *
     * <p>ffmpeg 周期输出的进度行（frame=.../bitrate=...）高频刷屏，过滤掉；
     * 含错误特征的行以 WARN 打印，其余 INFO。stderr 关闭即代表进程退出，
     * 此时打一条 EOF 日志并尝试带出退出码。
     */
    private void drainFfmpegLog(String callId, InputStream in) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (isFfmpegProgressLine(line)) {
                    trackFfmpegOutTime(callId, line);
                    continue;
                }
                if (isFfmpegErrorLine(line)) {
                    log.warn("[28181] ffmpeg[{}] {}", callId, line);
                } else {
                    log.info("[28181] ffmpeg[{}] {}", callId, line);
                }
            }
            log.warn("[28181] ffmpeg[{}] stderr 已关闭（进程退出，广播器将随之 EOF）", callId);
        } catch (Exception e) {
            log.debug("[28181] ffmpeg[{}] stderr 读取结束: {}", callId, e.getMessage());
        }
    }

    /** ffmpeg 周期性进度行（无诊断价值，避免刷屏） */
    private static boolean isFfmpegProgressLine(String line) {
        return line.startsWith("frame=")
                || line.startsWith("size=")
                || (line.contains("fps=") && line.contains("bitrate="));
    }

    /**
     * ffmpeg 进度行中的输出时间戳，形如 {@code time=00:00:23.45}。
     *
     * <p>负时间戳（起始对齐前的 {@code time=-00:00:00.01}）不匹配，直接跳过。
     */
    private static final Pattern FFMPEG_TIME_PATTERN =
            Pattern.compile("time=(\\d{1,3}):(\\d{2}):(\\d{2}\\.\\d+)");

    /**
     * 从 ffmpeg 进度行解析输出时间（秒）写入会话，供下载进度查询端点使用。
     *
     * <p>进度行被过滤不打印，但其中的 {@code time=} 是"已转码的录像时长"——
     * 下载/回放进度的唯一真实信号（设备按 1x 推流时同样准确）。
     */
    private void trackFfmpegOutTime(String callId, String line) {
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

    /** ffmpeg 错误特征行（WARN 级别突出显示） */
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

    /** 静默丢弃流内容（仅用于 ffmpeg stdout 二进制数据，禁止打印） */
    private void drainQuietly(InputStream in) {
        try (in) {
            byte[] buf = new byte[8192];
            while (in.read(buf) >= 0) {
                // 丢弃
            }
        } catch (Exception ignored) {
            // 忽略
        }
    }

    // ==================== 清理 ====================

//    private void cleanup(StreamSession s) {
//        if (s == null) {
//            return;
//        }
//
//        FlvBroadcast bc = flvBroadcasts.remove(s.getCallId());
//        if (bc != null) {
//            bc.close();
//        }
//
//        Process p = s.getFfmpeg();
//        if (p != null) {
//            s.setFfmpeg(null);
//            // 顺序至关重要：必须先断输入、再等退出。
//            // 关 RTP 路由 → sink 写线程退出 → 关闭 ffmpeg stdin → pipe:0 EOF
//            // → ffmpeg flush 编码队列、写 moov、按 +faststart 重排文件后自行退出。
//            // 若先 destroy()：Windows 上即 TerminateProcess，ffmpeg 瞬间死亡，
//            // moov 永远写不出来，.part 改名后就是无法播放的损坏文件。
//            rtpReceiver.stop(s.getCallId());
//            // DOWNLOAD 必须给足 ffmpeg 写 moov/faststart 重排的时间；
//            // 超时仍存活才强杀（此时文件已损坏，仅兜底防线程泄漏）
//            long waitMillis = s.getType() == StreamSession.Type.DOWNLOAD
//                    ? FFMPEG_DOWNLOAD_WAIT_MILLIS
//                    : FFMPEG_WAIT_MILLIS;
//            try {
//                p.waitFor(waitMillis, TimeUnit.MILLISECONDS);
//            } catch (InterruptedException ignored) {
//                Thread.currentThread().interrupt();
//            }
//            if (p.isAlive()) {
//                log.warn("[28181] ffmpeg 优雅退出超时({}ms)，强制结束: callId={} type={}",
//                        waitMillis, s.getCallId(), s.getType());
//                p.destroyForcibly();
//            }
//        } else {
//            rtpReceiver.stop(s.getCallId());
//        }
//
//        if (s.getType() == StreamSession.Type.DOWNLOAD) {
//            finalizeDownload(s);
//        }
//
//        s.setState(StreamSession.State.STOPPED);
//    }

    private void cleanup(StreamSession s) {
        if (s == null) {
            return;
        }

        String callId = s.getCallId();
        FlvBroadcast bc = flvBroadcasts.remove(callId);
        if (bc != null) {
            bc.close();
        }

        rtpReceiver.stop(callId);

        Process p = s.getFfmpeg();
        if (p != null) {
            s.setFfmpeg(null);

            // 关闭 FFmpeg stdin，使 pipe:0 收到 EOF，触发正常收尾。
            try {
                p.getOutputStream().close();
            } catch (IOException e) {
                log.warn("[28181] 关闭 FFmpeg stdin 失败: callId={}", callId, e);
            }

            long waitMillis = s.getType() == StreamSession.Type.DOWNLOAD
                    ? FFMPEG_DOWNLOAD_WAIT_MILLIS
                    : FFMPEG_WAIT_MILLIS;

            try {
                if (!p.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                    log.warn("[28181] FFmpeg 优雅退出超时({}ms)，强制结束: callId={} type={}",
                            waitMillis, callId, s.getType());
                    p.destroyForcibly();
                    p.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        if (s.getType() == StreamSession.Type.DOWNLOAD) {
            finalizeDownload(s);
        }

        s.setState(StreamSession.State.STOPPED);
    }

    private void finalizeDownload(StreamSession s) {
        try {
            Path dir = Paths.get(mediaProps.getLocal().getDownloadDir())
                    .toAbsolutePath().normalize();
            Path part = dir.resolve(s.getDownloadFileName() + ".part");
            Path done = dir.resolve(s.getDownloadFileName());
            if (Files.exists(part) && !Files.exists(done)) {
                try {
                    Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception moveErr) {
                    // Windows：浏览器"边下边传"持有 .part 打开句柄时
                    // Files.move 会因文件被锁定而失败（sharing violation），
                    // 退化为复制（读 .part 不受锁影响）
                    Files.copy(part, done, StandardCopyOption.REPLACE_EXISTING);
                    log.info("[28181] 下载完成(复制收尾，.part由下载端关闭后清理): {}",
                            s.getDownloadFileName());
                    return;
                }
                log.info("[28181] 下载完成: {}", s.getDownloadFileName());
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
            log.warn("[28181] 下载文件收尾失败: callId={} err={}",
                    s.getCallId(), e.getMessage());
        }
    }

    // ==================== 工具 ====================

    private static String key(String deviceId, String channelId) {
        return deviceId + ":" + channelId;
    }

    private Object lockOf(String key) {
        return playLocks.computeIfAbsent(key, k -> new Object());
    }

    private static void cancelTask(Map<String, ScheduledFuture<?>> map, String id) {
        ScheduledFuture<?> f = map.remove(id);
        if (f != null) {
            f.cancel(false);
        }
    }

    private static String buildDownloadFileName(String channelId, long start, long end) {
        String st = LocalDateTime.ofInstant(Instant.ofEpochSecond(start), ZoneId.of("+8"))
                .format(DL_NAME_TIME);
        String et = LocalDateTime.ofInstant(Instant.ofEpochSecond(end), ZoneId.of("+8"))
                .format(DL_NAME_TIME);
        return "rec_" + channelId + "_" + st + "_" + et + ".mp4";
    }

    // ==================== 停机 ====================

    @EventListener
    public void onShutdown(ContextClosedEvent event) {
        shutdown = true;
        log.info("[28181] 媒体服务停机：停止全部会话");
        for (StreamSession s : new ArrayList<>(sessionsByKey.values())) {
            try {
                doStop(s);
            } catch (Exception e) {
                log.warn("[28181] 停机停止会话失败: callId={}", s.getCallId(), e);
            }
        }
        inviteScheduler.shutdownNow();
        monitorScheduler.shutdownNow();
        monitorActionExecutor.shutdownNow();
        ffmpegExecutor.shutdownNow();
    }

    @PreDestroy
    public void destroy() {
        if (!shutdown) {
            shutdown = true;
            for (StreamSession s : new ArrayList<>(sessionsByKey.values())) {
                try {
                    doStop(s);
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    // ==================== ZLM 门面 ====================

    public String zlmFlvUrl(StreamSession s) {
        return zlmMediaService.flvUrl(s);
    }

    public String zlmWsFlvUrl(StreamSession s) {
        return zlmMediaService.wsFlvUrl(s);
    }

    public String zlmHlsUrl(StreamSession s) {
        return zlmMediaService.hlsUrl(s);
    }

    /**
     * 等待 ZLM 注册 MediaSource（设备 RTP 到达 + PS 解封装约 1~2s）。
     *
     * <p>与 FlvController 的 FLV 重定向同一语义：会话已停止或超时返回 false。
     */
    public boolean waitZlmReady(StreamSession s, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (s.getState() == StreamSession.State.STOPPED) {
                return false;
            }
            if (zlmMediaService.isMediaReady(s)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return zlmMediaService.isMediaReady(s);
    }

    public String zlmWebrtcUrl(StreamSession s) {
        return zlmMediaService.webrtcUrl(s);
    }

    public boolean isZlmMediaReady(StreamSession s) {
        return zlmMediaService.isMediaReady(s);
    }
}
