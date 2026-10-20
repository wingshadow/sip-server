package com.example.sip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 媒体面配置：自研链路（local）与 ZLMediaKit（zlm）二选一。
 *
 * <p>mode=local：RtpPortPool + RtpReceiver(每会话独立端口) + ffmpeg + FlvBroadcast 自研媒体链路。
 * <p>mode=zlm：ZLMediaKit 接管 RTP 收流及媒体分发，本服务只负责 SIP 信令。
 *
 * <p>多端口模型：每个点播/回放/下载会话独占一个本地 UDP 端口（从 local.port-pool 分配），
 * SIP INVITE SDP 的 m=video 通告该端口，设备直接推流到会话端口，彻底摆脱单端口共享模式。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sip.media")
public class MediaProperties {

    /** 媒体面模式 */
    private Mode mode = Mode.LOCAL;

    /** 自研媒体配置 */
    private final Local local = new Local();

    /** ZLMediaKit 配置 */
    private final Zlm zlm = new Zlm();

    public enum Mode {
        LOCAL, ZLM
    }

    @Getter
    @Setter
    public static class Local {

        /** 多端口模式：本地 RTP 接收端口池起始端口（含）。与语音池（默认 30100 起）必须不重叠 */
        private int portPoolStart = 30000;

        /** 多端口模式：本地 RTP 接收端口池结束端口（含）。默认到 30099，为语音池留出 30100+ */
        private int portPoolEnd = 30099;

        /** 端口池大小（= end - start + 1），即最大并发媒体会话数 */
        public int poolSize() {
            return Math.max(0, portPoolEnd - portPoolStart + 1);
        }

        /** ffmpeg 可执行文件路径（需在 PATH 或绝对路径） */
        private String ffmpegPath = "ffmpeg";

        /** HLS 切片输出目录 */
        private String hlsDir = "./hls";

        /** 录像下载（MP4）输出目录 */
        private String downloadDir = "./downloads";

        /**
         * 录像下载倍速（GB/T 28181-2022 SDP a=downloadspeed，默认 4）。
         *
         * <p>两路生效：INVITE SDP 携带 {@code a=downloadspeed:<n>} 协商倍速；
         * 会话建立后再经 SIP INFO（Application/MANSRTSP）下发 {@code SCALE} 兜底。
         * 设备均不支持时按 1x 实时推流，下载仍可正常完成。设为 1 即恢复原行为。
         *
         * <p><b>倍速是"时间轴倍速"而非"文件压缩率"</b>：设备以 N 倍速率读取并
         * 发送录像，收到的仍是完整录像（时长与体积基本不变），仅传输耗时缩短
         * 为 1/N；设备侧代价（读卡+解码+封包+发送）随倍速线性上升，低端 IPC
         * 设 4 易宕机，建议 1~2。要减小文件体积请调 {@link #downloadCrf}
         * 等转码参数。
         */
        private int downloadSpeed = 4;

        /**
         * 下载 MP4 转码 preset（默认 medium）。
         *
         * <p>下载是离线转码（设备推完后 ffmpeg 继续写文件），不必迁就实时性：
         * ultrafast 省 CPU 但同 CRF 下体积大 30~50%。多路并发下载可调回
         * veryfast/faster，或用硬件编码（需自行改造 -c:v）。
         */
        private String downloadPreset = "medium";

        /**
         * 下载 MP4 转码 CRF（x264 恒定质量，0~51，默认 26）。
         *
         * <p>保持设备原始分辨率不变，仅通过码率控制压缩体积：CRF 23
         * （x264 默认）偏保守，26 对监控内容体积可再降 25~35% 且画质
         * 几乎无损；越小质量越高体积越大，28+ 适合存档场景。
         */
        private int downloadCrf = 26;

        /**
         * 下载会话是否额外下发 MANSRTSP PLAY（默认 false）。
         *
         * <p>设备在 ACK 后通常已开始推流，无需 PLAY；仅当设备要求先收到
         * PLAY 才推流时开启。注意：会话中途 PLAY 可能被设备理解为 seek
         * 重推，造成录像时间跳变，非必要不开启。
         */
        private boolean downloadMansrtspPlay = false;

        /**
         * 回放/下载会话 RTP 空闲阈值（秒，默认 15）。
         *
         * <p>设备推流结束应发 BYE 收尾，但部分设备静默停推：超过该时长
         * 无新 RTP 包即判定推流结束/中断，平台主动 BYE 收尾（ffmpeg 写
         * moov 落盘），避免下载永远卡在进度上限。
         *
         * <p>首包宽限为该值 4 倍（下限 60s），容纳设备起推与 ffmpeg 输入
         * 解析耗时（2688x1520 实测约 10s）。
         */
        private int downloadIdleSeconds = 15;

        /** x264 preset 白名单（ffmpeg -preset 合法值） */
        private static final Set<String> PRESET_WHITELIST = Set.of(
                "ultrafast", "superfast", "veryfast", "faster", "fast",
                "medium", "slow", "slower", "veryslow", "placebo");

        /**
         * 校验 download-preset：非法值直接让配置加载失败，避免把错误参数
         * 拼进 ffmpeg 命令行导致下载全部失败。
         */
        public void setDownloadPreset(String preset) {
            String p = (preset == null ? "medium" : preset).trim().toLowerCase();
            if (!PRESET_WHITELIST.contains(p)) {
                throw new IllegalArgumentException(
                        "sip.media.local.download-preset 非法: " + preset
                                + "，可选值: " + PRESET_WHITELIST);
            }
            this.downloadPreset = p;
        }

        /** 校验 download-crf：x264 CRF 合法范围 [0, 51] */
        public void setDownloadCrf(int crf) {
            if (crf < 0 || crf > 51) {
                throw new IllegalArgumentException(
                        "sip.media.local.download-crf 超出范围 [0,51]: " + crf);
            }
            this.downloadCrf = crf;
        }
    }

    @Getter
    @Setter
    public static class Zlm {

        /** ZLM HTTP API 地址 */
        private String apiBase;

        /** ZLM API Secret */
        private String secret;

        /** SIP INVITE SDP 中的媒体接收 IP */
        private String mediaIp;

        /** ZLM HTTP API / HTTP 播放端口 */
        private int httpPort;

        /** 浏览器访问 ZLM 时使用的主机地址 */
        private String playHost;

        /** GB/T 28181 RTP 收流应用名 */
        private String rtpApp;

        /** openRtpServer 接收端口（ZLM 侧） */
        private int rtpPort;

        /** ZLM RTSP 端口 */
        private int rtspPort;

        /** ZLM RTMP 端口 */
        private int rtmpPort;
    }

    public boolean isZlm() {
        return mode == Mode.ZLM;
    }

    public boolean isLocal() {
        return mode == Mode.LOCAL;
    }
}
