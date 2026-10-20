package com.example.sip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 语音对讲配置（多端口模式）。
 *
 * <p>每个语音会话独占一个本地 UDP 端口（从 port-pool 分配），
 * 200 OK answer SDP 的 m=audio 通告该端口，设备直接推流到会话端口，
 * 支持多台设备同时语音对讲。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sip.voice")
public class VoiceProperties {

    /** 是否启用语音对讲 */
    private boolean enabled = true;

    /** 语音 RTP 端口池起始端口（含） */
    private int portPoolStart = 30100;

    /** 语音 RTP 端口池结束端口（含） */
    private int portPoolEnd = 30150;

    /** 端口池大小（= end - start + 1），即最大并发语音会话数 */
    public int poolSize() {
        return Math.max(0, portPoolEnd - portPoolStart + 1);
    }
}
