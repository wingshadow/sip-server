package com.example.sip.service;

import com.example.sip.config.MediaProperties;
import com.example.sip.config.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 媒体产物磁盘保留清理（downloads 下载 MP4 / hls 切片）。
 *
 * <p>两个目录此前只写不删：下载文件 GB 级、HLS 切片量大，7×24 运行
 * 必然打满磁盘（进而拖垮 ffmpeg 落盘与日志输出）。策略为按修改时间
 * 保留 N 天：正在写入的 .part / m3u8+ts 的 mtime 持续更新，不会被误删。
 *
 * <p>调度：{@code @EnableScheduling} 已由 DeviceRegistry 启用；启动后
 * 30 秒先跑一轮（等效启动即清残留），之后按 {@code app.retention} 配置间隔。
 */
@Component
public class DiskRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DiskRetentionService.class);

    private final RetentionProperties props;
    private final Path downloadsDir;
    private final Path hlsDir;

    public DiskRetentionService(RetentionProperties props,
                                MediaProperties mediaProps,
                                @Value("${app.hls-dir:./hls}") String hlsDir) {
        this.props = props;
        this.downloadsDir = Paths.get(mediaProps.getLocal().getDownloadDir())
                .toAbsolutePath().normalize();
        this.hlsDir = Paths.get(hlsDir).toAbsolutePath().normalize();
    }

    /**
     * 定时清理入口。disabled 时直接返回（空转开销可忽略）。
     */
    @Scheduled(initialDelay = 30_000,
            fixedDelayString = "#{@retentionProperties.sweepIntervalHours * 3600 * 1000}")
    public void sweep() {
        if (!props.isEnabled()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - props.getMaxAgeDays() * 86_400_000L;
        int removed = sweepDir(downloadsDir, "downloads", cutoff)
                + sweepDir(hlsDir, "hls", cutoff);
        if (removed > 0) {
            log.info("[RETENTION] 本轮共清理 {} 个过期文件（保留 {} 天）",
                    removed, props.getMaxAgeDays());
        }
    }

    /** 扫描单个目录（含子目录），删除修改时间早于 cutoff 的普通文件，返回删除数。 */
    private int sweepDir(Path dir, String label, long cutoff) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        AtomicInteger deleted = new AtomicInteger();
        AtomicLong freed = new AtomicLong();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                try {
                    FileTime mtime = Files.getLastModifiedTime(f);
                    if (mtime.toMillis() < cutoff) {
                        long size = Files.size(f);
                        Files.deleteIfExists(f);
                        deleted.incrementAndGet();
                        freed.addAndGet(size);
                    }
                } catch (Exception ignore) {
                    // 单文件失败（Windows 句柄占用/权限）不影响其余清理
                }
            });
        } catch (Exception e) {
            log.warn("[RETENTION] {} 目录扫描失败: {} err={}", label, dir, e.getMessage());
        }
        if (deleted.get() > 0) {
            log.info("[RETENTION] {} 清理: 删除 {} 个文件，释放 {} MB",
                    label, deleted.get(), freed.get() / (1024 * 1024));
        }
        return deleted.get();
    }
}
