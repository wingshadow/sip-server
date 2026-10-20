package com.example.sip.monitor;

import com.example.sip.service.DeviceRegistry;
import com.example.sip.service.media.MediaService;
import com.example.sip.service.media.RtpReceiver;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * SIP 平台运行指标（Actuator {@code /actuator/metrics} 可见，{@code sip.} 前缀）。
 *
 * <p>采集对象均为现有内存状态的只读视图，不介入任何 SIP 信令 / 媒体逻辑：
 * <ul>
 *   <li>{@code sip.devices.online / sip.devices.total}：设备注册表（{@link DeviceRegistry}）</li>
 *   <li>{@code sip.sessions.active}：活跃媒体会话数（{@link MediaService#activeSessionCount()}）</li>
 *   <li>{@code sip.ffmpeg.processes}：存活 ffmpeg 子进程数（local 媒体链路；zlm 模式恒 0）</li>
 *   <li>{@code sip.rtp.ports.available / sip.rtp.ports.capacity}：RTP 端口池水位
 *       （耗尽即新点播失败，是最需要提前告警的容量指标）</li>
 * </ul>
 *
 * <p>端点暴露白名单由 {@code management.endpoints.web.exposure.include} 控制
 * （见 {@code application.yml}）；{@code shutdown} 端点刻意不暴露——它能直接终止服务。
 */
@Component
public class SipMetrics {

    private final MeterRegistry meterRegistry;
    private final DeviceRegistry registry;
    private final MediaService mediaService;
    private final RtpReceiver rtpReceiver;

    public SipMetrics(MeterRegistry meterRegistry,
                      DeviceRegistry registry,
                      MediaService mediaService,
                      RtpReceiver rtpReceiver) {
        this.meterRegistry = meterRegistry;
        this.registry = registry;
        this.mediaService = mediaService;
        this.rtpReceiver = rtpReceiver;
    }

    /** 注册 gauge：被观测对象均为 Spring 单例，弱引用不会失效；禁止在运行中重复注册 */
    @PostConstruct
    public void registerGauges() {
        Gauge.builder("sip.devices.online", registry, DeviceRegistry::onlineCount)
                .description("在线设备数（心跳未超时）")
                .register(meterRegistry);
        Gauge.builder("sip.devices.total", registry, r -> r.all().size())
                .description("注册设备总数（含离线）")
                .register(meterRegistry);
        Gauge.builder("sip.sessions.active", mediaService, MediaService::activeSessionCount)
                .description("活跃媒体会话数（点播/回放/下载）")
                .register(meterRegistry);
        Gauge.builder("sip.ffmpeg.processes", mediaService, MediaService::activeFfmpegCount)
                .description("存活 ffmpeg 子进程数（local 媒体链路；zlm 模式为 0）")
                .register(meterRegistry);
        Gauge.builder("sip.rtp.ports.available", rtpReceiver, RtpReceiver::availablePorts)
                .description("RTP 端口池可用端口数（归零后新点播将失败）")
                .register(meterRegistry);
        Gauge.builder("sip.rtp.ports.capacity", rtpReceiver, RtpReceiver::portCapacity)
                .description("RTP 端口池容量")
                .register(meterRegistry);
    }
}
