package com.example.sip.websocket;

import com.example.sip.service.voice.VoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音对讲 WebSocket 上行通道。
 *
 * <p>连接地址：ws://host:port/ws/voice?deviceId=xxx&amp;channelId=xxx
 *
 * <p>浏览器以二进制帧推送 8kHz/16bit/单声道 小端 PCM，服务端 G.711A
 * 编码后按 20ms 节奏（160 字节 PCMA/包）经会话独占 UDP 端口
 * （VoiceRtpEndpoint，端口来自 RtpPortPool）直发设备。
 */
@Component
public class VoiceWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);

    /** 上行音频统计（限流日志用，避免每块打印影响节奏） */
    private final Map<String, long[]> audioStats = new ConcurrentHashMap<>();

    private final VoiceService voiceService;

    public VoiceWebSocketHandler(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    /** 握手校验 query 中 deviceId/channelId（缺失以 1008 POLICY_VIOLATION 拒绝）并缓存到 attributes */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, String> q = parseQuery(session);
        String deviceId = q.get("deviceId");
        String channelId = q.getOrDefault("channelId", deviceId);

        if (deviceId == null || deviceId.isEmpty()
                || channelId == null || channelId.isEmpty()) {
            log.warn("[VoiceWS] 缺少 deviceId/channelId 参数，拒绝连接: uri={}",
                    session.getUri());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        session.getAttributes().put("deviceId", deviceId);
        session.getAttributes().put("channelId", channelId);

        // 注册到语音服务：会话停止（stop/BYE/超时/异常/停机）时统一关闭
        voiceService.registerWebSocket(deviceId + ":" + channelId, session);

        log.info("[VoiceWS] 语音上行连接建立: deviceId={} channelId={} sessionId={}",
                deviceId, channelId, session.getId());
    }

    /** 收二进制帧 PCM（8kHz/16bit/LE）→ pushAudio 编码发送；无会话信息时拒绝连接 */
    @Override
    protected void handleBinaryMessage(WebSocketSession session,
                                       BinaryMessage message) {
        Map<String, Object> attrs = session.getAttributes();
        String deviceId = (String) attrs.get("deviceId");
        String channelId = (String) attrs.get("channelId");

        if (deviceId == null || channelId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        if (pcm.length == 0) {
            return;
        }

        // 上行音频限流 INFO 日志（每 2 秒一条）：区分"浏览器没发"与"后端没收到/没发"
        long[] st = audioStats.computeIfAbsent(
                deviceId + ":" + channelId, k -> new long[4]);
        long now = System.currentTimeMillis();
        synchronized (st) {
            st[0] += pcm.length;
        }
        if (now - st[1] > 2000) {
            long bytes;
            long lastBytes;
            long lastAt;
            synchronized (st) {
                bytes = st[0];
                lastBytes = st[2];
                lastAt = st[1] == 0 ? now - 2000 : st[1];
                st[2] = bytes;
                st[1] = now;
            }
            long blocksPerSec = (bytes - lastBytes) / 512 * 1000L / Math.max(1, now - lastAt);
            log.info("[VoiceWS] 上行音频: deviceId={} channel={} 累计 {} 字节 ≈{} 块/s（应≈31）",
                    deviceId, channelId, bytes, blocksPerSec);
        }

        voiceService.pushAudio(deviceId, channelId, pcm);
    }

    /** 传输异常记 warn 日志 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.warn("[VoiceWS] 传输异常: sessionId={} error={}",
                session.getId(), ex.getMessage());
    }

    /** 连接关闭：反注册并记日志 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Map<String, Object> attrs = session.getAttributes();
        Object deviceId = attrs.get("deviceId");
        Object channelId = attrs.get("channelId");
        if (deviceId != null && channelId != null) {
            voiceService.unregisterWebSocket(
                    deviceId + ":" + channelId, session);
            audioStats.remove(deviceId + ":" + channelId);
        }

        log.info("[VoiceWS] 连接关闭: sessionId={} status={}",
                session.getId(), status);
    }

    /** 解析 URI 查询参数（deviceId/channelId）。 */
    private static Map<String, String> parseQuery(WebSocketSession session) {
        Map<String, String> result = new HashMap<>();
        try {
            URI uri = session.getUri();
            if (uri == null || uri.getQuery() == null) {
                return result;
            }
            for (String pair : uri.getQuery().split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String val = eq < 0 ? "" : pair.substring(eq + 1);
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(val, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("[VoiceWS] 解析查询参数失败: {}", e.getMessage());
        }
        return result;
    }

    /** 忽略异常关闭连接 */
    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
