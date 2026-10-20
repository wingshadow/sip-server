package com.example.sip.config;

import com.example.sip.service.media.RtpPortPool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 媒体层 Bean 装配。
 *
 * <p>RtpPortPool 不是组件（端口范围由配置驱动且构造需参数），
 * 在此以 @Bean 工厂方式注入，供 RtpReceiver / MediaService /
 * DeviceController（端口池统计）共用。
 */
@Configuration
public class MediaConfig {

    @Bean
    public RtpPortPool rtpPortPool(MediaProperties mediaProps) {
        MediaProperties.Local local = mediaProps.getLocal();

        return new RtpPortPool(
                "media",
                local.getPortPoolStart(),
                local.getPortPoolEnd());
    }
}
