package com.example.sip.service;

import com.example.sip.config.MediaProperties;
import com.example.sip.config.RetentionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 磁盘保留清理单元测试（@TempDir 隔离，不触碰真实 downloads/hls）。
 *
 * <p>验证：过期文件（含子目录递归）删除、未过期文件与活跃 .part 保留、
 * enabled=false 时完全不动。
 */
class DiskRetentionServiceTest {

    @TempDir
    Path tmp;

    private static final long DAY = 86_400_000L;

    private MediaProperties mediaProps(Path downloads) {
        MediaProperties mediaProps = new MediaProperties();
        mediaProps.getLocal().setDownloadDir(downloads.toString());
        return mediaProps;
    }

    @Test
    void sweep_deletesOnlyExpiredFiles() throws Exception {
        Path downloads = tmp.resolve("downloads");
        Files.createDirectories(downloads.resolve("sub"));

        Path oldMp4 = downloads.resolve("old.mp4");
        Files.writeString(oldMp4, "x");
        Files.setLastModifiedTime(oldMp4, FileTime.fromMillis(System.currentTimeMillis() - 8 * DAY));

        Path freshMp4 = downloads.resolve("fresh.mp4");
        Files.writeString(freshMp4, "x");

        // 正在写入的下载临时文件（mtime 新）：绝不能被清理
        Path activePart = downloads.resolve("active.mp4.part");
        Files.writeString(activePart, "x");

        // 子目录内的过期 HLS 切片：递归清理
        Path oldTs = downloads.resolve("sub").resolve("old.ts");
        Files.writeString(oldTs, "x");
        Files.setLastModifiedTime(oldTs, FileTime.fromMillis(System.currentTimeMillis() - 8 * DAY));

        DiskRetentionService service = new DiskRetentionService(
                new RetentionProperties(), mediaProps(downloads),
                tmp.resolve("hls").toString());

        service.sweep();

        assertThat(oldMp4).doesNotExist();
        assertThat(oldTs).doesNotExist();
        assertThat(freshMp4).exists();
        assertThat(activePart).exists();
    }

    @Test
    void sweep_disabled_isNoop() throws Exception {
        Path downloads = tmp.resolve("downloads");
        Files.createDirectories(downloads);
        Path old = downloads.resolve("old.mp4");
        Files.writeString(old, "x");
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 30 * DAY));

        RetentionProperties props = new RetentionProperties();
        props.setEnabled(false);
        new DiskRetentionService(props, mediaProps(downloads),
                tmp.resolve("hls").toString()).sweep();

        assertThat(old).exists();
    }

    @Test
    void sweep_missingDirs_isSilent() {
        // 目录不存在（首次部署）不应抛异常
        DiskRetentionService service = new DiskRetentionService(
                new RetentionProperties(), mediaProps(tmp.resolve("no-downloads")),
                tmp.resolve("no-hls").toString());
        service.sweep(); // 无异常即通过
    }
}
