package com.example.sip.controller;

import com.example.sip.config.MediaProperties;
import com.example.sip.model.StreamSession;
import com.example.sip.service.local.FlvBroadcast;
import com.example.sip.service.media.MediaService;
import com.example.sip.service.zlm.ZlmMediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 媒体播放与下载端点。
 *
 * <ul>
 *   <li>GET /live/{deviceId}/{channelId}.flv —— 直播 HTTP-FLV（local：广播器拉流；zlm：重定向）</li>
 *   <li>GET /playback/{deviceId}/{channelId}/{start}/{end}.flv —— 回放 HTTP-FLV</li>
 *   <li>GET /api/download/file?deviceId=&channelId= —— 录像 MP4 下载（local 模式）</li>
 *   <li>GET /api/download/list —— 下载目录清单</li>
 * </ul>
 *
 * <p>多端口模型下，一个会话对应一个独占 UDP 端口 + 一个 ffmpeg 管道 + 一个
 * HTTP-FLV 广播器；多个浏览器客户端共享同一广播器（Bootstrap + 实时分发）。
 */
@RestController
public class FlvController {

    private static final Logger log = LoggerFactory.getLogger(FlvController.class);

    /** 等待 ffmpeg/广播器就绪的最长时间（毫秒） */
    private static final long READY_WAIT_MILLIS = 15_000;

    /** 广播器轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MILLIS = 300;

    /**
     * 等待 ffmpeg 创建下载文件的最长时间（毫秒）。
     *
     * <p>INVITE 200 OK 后 ffmpeg 还要等 RTP 首包 + 输入解析才会创建 .part
     * （2688x1520 实测约 10s），而前端在 /api/download/start 返回后立即
     * window.open 下载端点，必须等待文件出现再响应。
     */
    private static final long DOWNLOAD_FILE_WAIT_MILLIS = 30_000;

    /** .part 出现后轮询最终文件的间隔（毫秒） */
    private static final long DOWNLOAD_POLL_MILLIS = 500;

    /** 等待最终文件落定的兜底上限（毫秒）：会话无回放结束时间时使用 */
    private static final long DOWNLOAD_FINALIZE_WAIT_MILLIS = 600_000;

    /**
     * 回放结束时间之后等待收尾的宽限（毫秒）。
     *
     * <p>需覆盖 MediaService 中 DOWNLOAD 会话的 ffmpeg 优雅退出等待
     * （flush 编码队列 + 写 moov + faststart 重排大文件，实测可达数十秒）。
     */
    private static final long DOWNLOAD_FINALIZE_GRACE_MILLIS = 90_000;

    private final MediaService mediaService;
    private final ZlmMediaService zlmMediaService;
    private final MediaProperties mediaProps;

    public FlvController(MediaService mediaService,
                         ZlmMediaService zlmMediaService,
                         MediaProperties mediaProps) {
        this.mediaService = mediaService;
        this.zlmMediaService = zlmMediaService;
        this.mediaProps = mediaProps;
    }

    // =========================================================
    // 直播 FLV
    // =========================================================

    @GetMapping("/live/{deviceId}/{channelId}.flv")
    public void liveFlv(@PathVariable String deviceId, @PathVariable String channelId,
                        HttpServletResponse resp)
            throws IOException {
        if (mediaProps.isZlm()) {
            redirectToZlm(deviceId, channelId, resp);
            return;
        }

        StreamSession s = mediaService.getOrStartLive(deviceId, channelId);
        streamFlv(resp, s);
    }

    // =========================================================
    // 回放 FLV
    // =========================================================

    @GetMapping("/playback/{deviceId}/{channelId}/{start}/{end}.flv")
    public void playbackFlv(@PathVariable String deviceId,
                            @PathVariable String channelId,
                            @PathVariable long start,
                            @PathVariable long end,
                            HttpServletResponse resp) throws IOException {
        if (mediaProps.isZlm()) {
            redirectToZlm(deviceId, channelId, resp);
            return;
        }

        StreamSession s = mediaService.getOrStartPlayback(deviceId, channelId, start, end);
        streamFlv(resp, s);
    }

    // =========================================================
    // 下载
    // =========================================================

    /**
     * 下载录像文件：等 ffmpeg 退出、最终 MP4 落定后整文件传输。
     *
     * <p><b>为什么不能边下边传</b>：mp4 封装器在关闭文件时会回写修补文件头
     * （mdat 尺寸字段；{@code -movflags +faststart} 还会把 moov 整体重排到
     * 文件头）。跟随 .part 增长读取会把"修补前的旧字节"流给客户端，拼装出的
     * 文件与磁盘文件大小相同但内容错位，播放器报 moov atom not found
     * （已实测复现：流式拼装文件 SHA256 与磁盘最终文件不一致）。
     *
     * <p>因此分两步等待：先等 .part 出现（确认会话已启动，约 10s），再等
     * 设备推流结束（BYE）→ ffmpeg 退出 → finalizeDownload 生成最终文件，
     * 然后带 Content-Length 整文件传输，客户端可显示真实百分比进度。
     * 前端用全屏遮罩呈现两阶段状态（推流中 → 传输中）。
     */
    @GetMapping("/api/download/file")
    public void downloadFile(@RequestParam String deviceId,
                             @RequestParam String channelId,
                             HttpServletResponse resp) throws IOException {
        File file = awaitFinalDownloadFile(deviceId, channelId);
        if (file == null) {
            log.warn("[Download] 最终文件未生成，返回404: device={} channel={}",
                    deviceId, channelId);
            resp.setStatus(HttpStatus.NOT_FOUND.value());
            resp.setContentType("text/plain;charset=UTF-8");
            resp.getWriter().write("录像文件不存在或尚未生成");
            return;
        }

        resp.setContentType("video/mp4");
        resp.setHeader("Content-Disposition",
                "attachment; filename=\"" + file.getName() + "\"");
        resp.setContentLengthLong(file.length());

        try (FileInputStream in = new FileInputStream(file);
             OutputStream out = resp.getOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            long streamed = 0;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                streamed += n;
            }
            out.flush();
            log.info("[Download] 文件下载完成: {} 字节={}", file.getName(), streamed);
        } catch (IOException e) {
            // 客户端中断（取消下载/关闭页面）属正常情况
            log.info("[Download] 客户端中断: {} err={}", file.getName(), e.getMessage());
        }
    }

    /**
     * 下载会话实时状态（前端阶段1 进度遮罩轮询）。
     *
     * <p>返回 ffmpeg 已输出的媒体时间（秒）与 .part 已产出字节数。
     * 输出时间是真实进度信号：设备不按倍速推流（1x）时，按"录像时长/倍速"
     * 的估算会过早到顶并长时间假死，而输出时间无论设备推多快都准确。
     */
    @GetMapping("/api/download/status")
    public Map<String, Object> downloadStatus(@RequestParam String deviceId,
                                              @RequestParam String channelId) {
        Map<String, Object> m = new LinkedHashMap<>();
        StreamSession s = mediaService.getSession(deviceId, channelId);
        if (s == null) {
            m.put("active", false);
            return m;
        }
        m.put("active", true);
        m.put("state", s.getState().name());
        m.put("outTimeSec", s.getFfmpegOutTimeSec());
        File f = mediaService.downloadFile(deviceId, channelId);
        m.put("partial", f != null && f.getName().endsWith(".part"));
        m.put("size", f != null ? f.length() : 0L);
        return m;
    }

    /** 等待下载文件出现（.part 或最终文件）；会话已终止仍无文件则提前放弃 */
    private File awaitDownloadFile(String deviceId, String channelId) {
        long deadline = System.currentTimeMillis() + DOWNLOAD_FILE_WAIT_MILLIS;
        while (true) {
            File f = mediaService.downloadFile(deviceId, channelId);
            if (f != null) {
                return f;
            }
            StreamSession sess = mediaService.getSession(deviceId, channelId);
            if (sess == null || sess.getState() == StreamSession.State.STOPPED) {
                return null;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(DOWNLOAD_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 等待下载完成并返回最终 MP4（ffmpeg 退出 + 收尾落定后的稳定文件）。
     *
     * <p>等待上限优先按会话回放结束时间推算——GB28181 历史回放按 1x 实时
     * 推流，3 分钟录像就要等约 3 分钟；设备迟迟不结束推流时主动停止会话
     * 兜底，避免 HTTP 连接永悬。
     */
    private File awaitFinalDownloadFile(String deviceId, String channelId) {
        File file = awaitDownloadFile(deviceId, channelId);
        if (file == null) {
            return null;
        }
        if (!file.getName().endsWith(".part")) {
            // 最终文件已存在（重复下载同一时间段）
            return file;
        }

        final File finalFile = new File(file.getParentFile(),
                file.getName().substring(0, file.getName().length() - 5));

        long waitMillis = DOWNLOAD_FINALIZE_WAIT_MILLIS;
        StreamSession sess = mediaService.getSession(deviceId, channelId);
        if (sess != null && sess.getPlaybackEnd() > 0) {
            long remain = sess.getPlaybackEnd() * 1000L - System.currentTimeMillis();
            if (remain > 0) {
                waitMillis = remain + DOWNLOAD_FINALIZE_GRACE_MILLIS;
            }
        }

        long deadline = System.currentTimeMillis() + waitMillis;
        boolean stopped = false;
        while (!finalFile.exists()) {
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                if (!stopped) {
                    // 设备迟迟不结束推流：主动停止会话，促使 ffmpeg 收尾生成最终文件
                    log.warn("[Download] 等待设备推流结束超时({}ms)，主动停止会话: channel={}",
                            waitMillis, channelId);
                    mediaService.stop(deviceId, channelId);
                    stopped = true;
                    deadline = now + DOWNLOAD_FINALIZE_GRACE_MILLIS;
                } else {
                    log.warn("[Download] 等待最终文件超时: channel={} file={}",
                            channelId, finalFile.getName());
                    return null;
                }
            }
            try {
                Thread.sleep(DOWNLOAD_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return finalFile;
    }

    /** 下载目录清单 */
    @GetMapping("/api/download/list")
    public List<Map<String, Object>> downloadList() {
        File dir = downloadDir();
        File[] files = dir.listFiles();
        if (files == null) {
            return List.of();
        }
        return Arrays.stream(files)
                .filter(File::isFile)
                .sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified()))
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", f.getName());
                    m.put("size", f.length());
                    m.put("modified", f.lastModified());
                    m.put("partial", f.getName().endsWith(".part"));
                    return m;
                })
                .collect(Collectors.toList());
    }

    // =========================================================
    // FLV 拉流核心
    // =========================================================

    /**
     * 订阅会话广播器并把 FLV 数据写给 HTTP 客户端。
     *
     * <p>本地媒体管线（设备 RTP → ffmpeg → FLV）在 INVITE 完成后才启动，
     * 因此这里等待广播器就绪；客户端断开后反订阅，无客户端会话按
     * FLV_GRACE 优雅停止。
     */
    private void streamFlv(HttpServletResponse resp, StreamSession s)
            throws IOException {
        FlvBroadcast broadcast = waitBroadcast(s);
        if (broadcast == null) {
            // 该 504 会被播放器（mpegts.js NETWORK_STATUS_CODE_INVALID）
            // 呈现为 "播放出错: HttpStatusCodeInvalid"，必须留下可定位的日志：
            // rtpStats 为 0 说明设备未推流；ffmpeg stderr 见 MediaService 日志
            log.error("[FLV] 媒体流建立超时(15s)，返回504: device={} channel={} callId={} state={} rtpStats={}",
                    s.getDeviceId(), s.getChannelId(), s.getCallId(), s.getState(),
                    mediaService.rtpStats(s.getCallId()));
            resp.sendError(HttpStatus.GATEWAY_TIMEOUT.value(), "媒体流建立超时");
            return;
        }

        resp.setStatus(HttpStatus.OK.value());
        resp.setContentType("video/x-flv");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");

        FlvBroadcast.Subscriber sub = broadcast.subscribe();
        if (sub.isClosed()) {
            log.warn("[FLV] 媒体流已关闭，返回404: device={} channel={} callId={}",
                    s.getDeviceId(), s.getChannelId(), s.getCallId());
            resp.sendError(HttpStatus.NOT_FOUND.value(), "媒体流已关闭");
            return;
        }

        OutputStream out = resp.getOutputStream();
        int idle = 0;
        try {
            while (!sub.isClosed()) {
                byte[] chunk = sub.poll(1000, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    // 长时间无数据：广播器可能已随会话关闭
                    if (broadcast.isClosed() && broadcast.getSubscriberCount() == 0) {
                        break;
                    }
                    if (++idle > 30) {
                        break;
                    }
                    continue;
                }
                idle = 0;
                out.write(chunk);
                out.flush();
            }
        } catch (IOException e) {
            log.info("[FLV] 客户端断开: device={} channel={} err={}",
                    s.getDeviceId(), s.getChannelId(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[FLV] 拉流线程被中断: device={} channel={}",
                    s.getDeviceId(), s.getChannelId());
        } finally {
            broadcast.unsubscribe(sub);
            // 无浏览器客户端时按宽限期优雅停止会话（保留 INVITE 重试窗口）
            mediaService.stopIfNoClients(s);
            log.info("[FLV] 拉流结束: device={} channel={}", s.getDeviceId(), s.getChannelId());
        }
    }

    /** 等待会话广播器就绪（最多 READY_WAIT_MILLIS） */
    private FlvBroadcast waitBroadcast(StreamSession s) {
        long deadline = System.currentTimeMillis() + READY_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            FlvBroadcast b;
            try {
                b = mediaService.getFlvBroadcast(s);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (IllegalStateException e) {
                // 会话已停止或等待媒体就绪超时（播放器将收到非200 → HttpStatusCodeInvalid）
                log.warn("[FLV] 广播器不可用: callId={} err={} rtpStats={}",
                        s.getCallId(), e.getMessage(), mediaService.rtpStats(s.getCallId()));
                return null;
            }
            if (b != null && !b.isClosed()) {
                return b;
            }
            if (s.getState() == StreamSession.State.STOPPED) {
                return null;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // =========================================================
    // ZLM 模式
    // =========================================================

    /** ZLM 模式：302 重定向到 ZLM 的 HTTP-FLV 播放地址 */
    private void redirectToZlm(String deviceId, String channelId, HttpServletResponse resp)
            throws IOException {
        StreamSession s = mediaService.getSession(deviceId, channelId);
        if (s == null) {
            // 无会话 = 尚未开始播放，与 local 模式语义一致，立即 404
            log.warn("[FLV] ZLM会话不存在，返回404: device={} channel={}", deviceId, channelId);
            resp.sendError(HttpStatus.NOT_FOUND.value(), "请先开始播放");
            return;
        }
        // 等待 ZLM 注册 MediaSource：设备 RTP 到达 + PS 解封装约 1~2s，
        // 期间播放器重试会反复 404（mpegts.js 报 HttpStatusCodeInvalid）；
        // 若 hook 未放行则永远等不到，超时 504 可与"未开始播放"区分
        long deadline = System.currentTimeMillis() + READY_WAIT_MILLIS;
        while (!zlmMediaService.isMediaReady(s) && System.currentTimeMillis() < deadline) {
            if (s.getState() == StreamSession.State.STOPPED) {
                break;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!zlmMediaService.isMediaReady(s)) {
            log.warn("[FLV] ZLM媒体流未就绪，返回504: device={} channel={} callId={} state={}",
                    deviceId, channelId, s.getCallId(), s.getState());
            resp.sendError(HttpStatus.GATEWAY_TIMEOUT.value(), "ZLM 媒体流建立超时");
            return;
        }
        resp.sendRedirect(zlmMediaService.flvUrl(s));
    }

    // =========================================================
    // 工具
    // =========================================================

    private File downloadDir() {
        String dir = mediaProps.getLocal().getDownloadDir();
        File f = new File(dir);
        if (!f.exists()) {
            f.mkdirs();
        }
        return f;
    }
}
