package com.example.sip.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 静态资源映射：将磁盘上的 HLS 切片目录暴露为 /hls/**，
 * 供浏览器直接拉流播放（m3u8 + ts）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.hls-dir:./hls}")
    private String hlsDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String dir = new File(hlsDir).getAbsolutePath().replace('\\', '/');
        if (!dir.endsWith("/")) {
            dir += "/";
        }
        registry.addResourceHandler("/hls/**")
                .addResourceLocations("file:" + dir)
                .setCachePeriod(0);
    }
}
