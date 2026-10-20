package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.SipMessage;
import com.example.sip.service.media.MediaService;
import com.example.sip.sip.SipResponseBuilder;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * 视频媒体信令处理器（INVITE / 响应 / BYE）。
 *
 * <p>设备侧视频流由平台 INVITE 发起（MediaService），这里处理三类报文：
 * <ul>
 *   <li>设备反向 INVITE（非常规）：以 486 拒绝，平台不接受未经请求的视频流</li>
 *   <li>INVITE 的 SIP 响应（200 OK / 4xx-6xx）：转交 {@link MediaService}</li>
 *   <li>设备 BYE：结束对应 callId 的媒体会话并回 200 OK</li>
 * </ul>
 */
@Component
public class MediaSignalHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaSignalHandler.class);

    private final SipServerProperties props;
    private final MediaService mediaService;

    public MediaSignalHandler(SipServerProperties props, MediaService mediaService) {
        this.props = props;
        this.mediaService = mediaService;
    }

    /** 设备反向 INVITE（视频）：拒绝（平台不接受未经请求的视频流） */
    public void handleInvite(SipMessage req, InetSocketAddress sender, ChannelHandlerContext ctx) {
        String from = SipUtil.extractSipId(req.getHeader("From"));
        log.info("[28181] 设备反向INVITE(视频)，拒绝: from={} callId={}",
                from, req.getHeader("Call-ID"));
        SipResponder.reply(ctx, sender,
                SipResponseBuilder.build(req, 486, "Busy Here", null, null, props));
    }

    /** INVITE 响应（200 OK 完成媒体协商；4xx-6xx 失败清理） */
    public void handleResponse(SipMessage msg) {
        mediaService.handleResponse(msg);
    }

    /**
     * 设备 BYE。
     *
     * @return true 表示本处理器认领了该 callId（路由器据此决定是否回 200 OK）
     */
    public boolean handleBye(SipMessage msg) {
        String callId = msg.getHeader("Call-ID");
        if (!mediaService.hasSession(callId)) {
            return false;
        }
        log.info("[28181] 设备BYE(媒体): callId={}", callId);
        mediaService.handleDeviceBye(callId);
        return true;
    }
}
