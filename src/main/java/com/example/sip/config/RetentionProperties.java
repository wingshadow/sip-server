package com.example.sip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 媒体产物磁盘保留策略（downloads 下载 MP4 / hls 切片清理）。
 *
 * <p>这两个目录的产物此前只写不删：下载文件 GB 级、HLS 切片量大，
 * 7×24 运行必然打满磁盘（进而拖垮 ffmpeg 落盘与日志输出）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.retention")
public class RetentionProperties {

    /** 是否启用定时清理（默认 true；false 时任务空转跳过） */
    private boolean enabled = true;

    /**
     * 文件保留天数：修改时间早于 now−N 天的产物被删除。
     * 正在写入的 .part / m3u8+ts 的 mtime 持续更新，不会被误删
     */
    private int maxAgeDays = 7;

    /** 两轮清理的间隔（小时；fixedDelay，自上轮执行完起算） */
    private long sweepIntervalHours = 6;
}
