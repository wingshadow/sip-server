package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.SipMessage;
import com.example.sip.service.voice.VoiceService;
import com.example.sip.sip.SdpParser;
import com.example.sip.sip.SdpParser.SdpInfo;
import com.example.sip.sip.SipResponseBuilder;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 语音对讲信令处理器（设备反向 INVITE / ACK / BYE / 响应）。
 *
 * <p>语音对讲两种形态：
 * <ul>
 *   <li>平台主动 INVITE（VoiceService.startTalk）：设备 200 OK → 平台 ACK → 双向音频</li>
 *   <li>语音广播：平台 MESSAGE → 设备反向 INVITE → 平台 200 OK（answer SDP）→ 设备推流</li>
 * </ul>
 * 本处理器负责把设备侧信令路由到 {@link VoiceService}。
 */
@Component
public class VoiceSignalHandler {

    private final SipServerProperties props;
    private final VoiceService voiceService;

    public VoiceSignalHandler(SipServerProperties props, VoiceService voiceService) {
        this.props = props;
        this.voiceService = voiceService;
    }

    /** 设备反向 INVITE（语音广播应答 / 设备发起对讲） */
    public void handleInvite(SipMessage msg, ChannelHandlerContext ctx, InetSocketAddress sender) {
        String sdp = msg.getBodyText(StandardCharsets.ISO_8859_1);
        SdpInfo sdpInfo = null;
        if (sdp != null && sdp.contains("m=audio")) {
            sdpInfo = SdpParser.parse(sdp);
        }

        boolean handled = voiceService.handleDeviceInvite(msg, sdpInfo, sender);
        if (!handled) {
            // 端口池耗尽等无法处理的场景：拒绝
            SipResponder.reply(ctx, sender, SipResponseBuilder.build(
                    msg, 486, "Busy Here", null, null, props));
        }
    }

    /** 设备 ACK（完成平台主动 INVITE 的三次握手） */
    public void handleAck(SipMessage msg, ChannelHandlerContext ctx) {
        voiceService.handleDeviceAck(msg.getHeader("Call-ID"));
    }

    /**
     * 设备 BYE。
     *
     * @return true 表示本处理器认领了该 callId
     */
    public boolean handleBye(SipMessage msg) {
        String callId = msg.getHeader("Call-ID");
        if (!voiceService.hasSession(callId)) {
            return false;
        }
        voiceService.handleDeviceBye(callId);
        return true;
    }

    /**
     * 语音 INVITE 的 SIP 响应（200 OK / 4xx-6xx）。
     *
     * @return true 表示语音会话拥有该 callId（响应已由语音服务处理）
     */
    public boolean handleInviteResponseIfOwned(SipMessage msg) {
        String callId = msg.getHeader("Call-ID");
        if (!voiceService.hasSession(callId)) {
            return false;
        }
        voiceService.handleInviteResponse(msg);
        return true;
    }

    /** 广播 MESSAGE 的 SIP 响应 */
    public void handleMessageResponse(SipMessage msg) {
        voiceService.handleBroadcastResponse(
                msg.getHeader("Call-ID"), msg.statusCode(), msg.reasonPhrase());
    }
}
