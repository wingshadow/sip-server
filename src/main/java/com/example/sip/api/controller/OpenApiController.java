package com.example.sip.api.controller;

import com.example.sip.model.Device;
import com.example.sip.model.StreamSession;
import com.example.sip.model.VoiceSession;
import com.example.sip.api.exception.DeviceNotFoundException;
import com.example.sip.api.dto.OpenApiResult;
import com.example.sip.api.dto.DeviceChannelRequest;
import com.example.sip.api.dto.PlaybackStartRequest;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.service.media.MediaService;
import com.example.sip.service.voice.VoiceService;
import com.example.sip.service.zlm.ZlmMediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对外 Open API（/api/v1）：轻量级 GB/T 28181 设备接入与媒体能力入口。
 *
 * <p>定位：只做参数校验、调用现有 Service、组装返回结果；
 * SIP/RTP/ZLM/媒体逻辑全部复用 {@link MediaService} / {@link VoiceService}，
 * 与内部接口（DeviceController 的 /api/**）同源，行为完全一致。
 *
 * <p>统一响应体 {@link OpenApiResult}：{code, message, data}。
 *
 * <p>sessionId 语义（即现有会话的唯一标识）：
 * <ul>
 *   <li>直播 / 回放：SIP Call-ID（{@link StreamSession#getCallId()}，
 *       会话建立时即生成）</li>
 *   <li>语音：会话键 deviceId:channelId（{@link VoiceSession#getKey()}；
 *       广播阶段 Call-ID 由设备反向 INVITE 才产生，key 是现有唯一稳定标识）</li>
 * </ul>
 *
 * <p>媒体模式（sip.media.mode）由 {@link MediaService} 内部感知：
 * zlm → ZlmMediaService，local → 多端口 RtpReceiver + ffmpeg 管线。
 */
@RestController
public class OpenApiController {

    private static final Logger log = LoggerFactory.getLogger(OpenApiController.class);

    private final DeviceRegistry registry;
    private final MediaService mediaService;
    private final VoiceService voiceService;
    private final ZlmMediaService zlmMediaService;

    public OpenApiController(DeviceRegistry registry,
                             MediaService mediaService,
                             VoiceService voiceService,
                             ZlmMediaService zlmMediaService) {
        this.registry = registry;
        this.mediaService = mediaService;
        this.voiceService = voiceService;
        this.zlmMediaService = zlmMediaService;
    }

    // =========================================================
    // 设备状态
    // =========================================================

    /**
     * 设备在线状态。
     *
     * <p>GET /api/v1/devices/{gbDeviceId}/status
     *
     * <p>在线状态直接读取 {@link DeviceRegistry}；设备未注册返回 404 / 40400。
     */
    @GetMapping("/api/v1/devices/{gbDeviceId}/status")
    public OpenApiResult<Map<String, Object>> deviceStatus(@PathVariable String gbDeviceId) {
        String id = requireDeviceId(gbDeviceId);
        Device d = registry.get(id);
        if (d == null) {
            throw new DeviceNotFoundException(id);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("gbDeviceId", d.getDeviceId());
        data.put("online", d.isOnline());
        data.put("name", d.getName());
        data.put("host", d.getHost());
        data.put("port", d.getPort());
        data.put("transport", d.getTransport());
        data.put("registerTime", d.getRegisterTime());
        data.put("lastKeepaliveTime", d.getLastKeepaliveTime());
        return OpenApiResult.ok(data);
    }

    // =========================================================
    // 直播 Live
    // =========================================================

    /**
     * 发起直播（点播）。
     *
     * <p>POST /api/v1/live/start
     * body: {"gbDeviceId":"34020000001310000001","channelId":"34020000001310000001"}
     *
     * <p>channelId 缺省取 gbDeviceId；与内部 /api/play/start 同源
     * （zlm 幂等复用会话，local 先停旧会话再新建）。
     */
    @PostMapping("/api/v1/live/start")
    public OpenApiResult<Map<String, Object>> liveStart(@RequestBody DeviceChannelRequest req) {
        String deviceId = requireDeviceId(req.gbDeviceId());
        String channelId = resolveChannelId(deviceId, req.channelId());

        StreamSession s = mediaService.startLive(deviceId, channelId);

        log.info("[OpenAPI] live/start: {} -> sessionId={} state={}",
                channelId, s.getCallId(), s.getState());
        return OpenApiResult.ok(mediaSessionData(s));
    }

    /**
     * 停止直播。
     *
     * <p>POST /api/v1/live/stop
     * body: {"gbDeviceId":"...","channelId":"..."}
     */
    @PostMapping("/api/v1/live/stop")
    public OpenApiResult<Map<String, Object>> liveStop(@RequestBody DeviceChannelRequest req) {
        String deviceId = requireDeviceId(req.gbDeviceId());
        String channelId = resolveChannelId(deviceId, req.channelId());

        mediaService.stop(deviceId, channelId);

        log.info("[OpenAPI] live/stop: {}", channelId);
        return OpenApiResult.ok(Map.of("stopped", true, "gbDeviceId", deviceId, "channelId", channelId));
    }

    // =========================================================
    // 回放 Playback
    // =========================================================

    /**
     * 发起历史回放。
     *
     * <p>POST /api/v1/playback/start
     * body: {"gbDeviceId":"...","channelId":"...","startTime":"2024-01-01T00:00:00","endTime":"2024-01-01T01:00:00"}
     *
     * <p>时间支持 ISO / 空格分隔 / 秒级 epoch（与内部接口解析语义一致）。
     *
     * <p>默认幂等：已有同设备+通道+时间范围的存活会话时复用，不拆建
     * （BYE+INVITE 风暴是摄像机宕机的典型诱因）；body 传 {@code "force": true}
     * 可强制新建（停止旧会话）。
     */
    @PostMapping("/api/v1/playback/start")
    public OpenApiResult<Map<String, Object>> playbackStart(@RequestBody PlaybackStartRequest req) {
        String deviceId = requireDeviceId(req.gbDeviceId());
        String channelId = resolveChannelId(deviceId, req.channelId());

        long start = parseTime(req.startTime());
        long end = parseTime(req.endTime());
        if (start <= 0 || end <= 0 || end < start) {
            throw new IllegalArgumentException("时间参数无效，需为合法时间且 endTime >= startTime");
        }

        boolean force = Boolean.TRUE.equals(req.force());
        StreamSession s = force
                ? mediaService.startPlayback(deviceId, channelId, start, end)
                : mediaService.getOrStartPlayback(deviceId, channelId, start, end);

        log.info("[OpenAPI] playback/start: {} [{} ~ {}] force={} -> sessionId={}",
                channelId, start, end, force, s.getCallId());
        return OpenApiResult.ok(mediaSessionData(s));
    }

    /**
     * 停止回放。
     *
     * <p>POST /api/v1/playback/stop
     * body: {"gbDeviceId":"...","channelId":"..."}
     */
    @PostMapping("/api/v1/playback/stop")
    public OpenApiResult<Map<String, Object>> playbackStop(@RequestBody DeviceChannelRequest req) {
        String deviceId = requireDeviceId(req.gbDeviceId());
        String channelId = resolveChannelId(deviceId, req.channelId());

        mediaService.stop(deviceId, channelId);

        log.info("[OpenAPI] playback/stop: {}", channelId);
        return OpenApiResult.ok(Map.of("stopped", true, "gbDeviceId", deviceId, "channelId", channelId));
    }

    // =========================================================
    // 语音 Voice（GB/T 28181-2022 语音广播 / 对讲）
    // =========================================================

    /**
     * 发起语音广播（设备随后反向音频 INVITE，会话进入 BROADCASTING/TALKING）。
     *
     * <p>POST /api/v1/voice/broadcast
     * body: {"gbDeviceId":"...","channelId":"..."}
     */
    @PostMapping("/api/v1/voice/broadcast")
    public OpenApiResult<Map<String, Object>> voiceBroadcast(@RequestBody DeviceChannelRequest req) {
        String deviceId = requireDeviceId(req.gbDeviceId());
        String channelId = resolveChannelId(deviceId, req.channelId());

        VoiceSession s = voiceService.start(deviceId, channelId);

        log.info("[OpenAPI] voice/broadcast: {} -> sessionId={} state={}",
                channelId, s.getKey(), s.getState());
        return OpenApiResult.ok(voiceSessionData(s));
    }

    /**
     * 发起语音对讲（无广播）：平台直接向设备发音频 INVITE，
     * 设备 200 OK → 平台 ACK → TALKING 后可推流。
     *
     * <p>POST /api/v1/voice/talk/start
     * body: {"gbDeviceId":"...","channelId":"..."}
     */
    @PostMapping("/api/v1/voice/talk/start")
    public OpenApiResult<Map<String, Object>> voiceTalkStart(@RequestBody DeviceChannelRequest req) {
        String deviceId = requireDeviceId(req.gbDeviceId());
        String channelId = resolveChannelId(deviceId, req.channelId());

        VoiceSession s = voiceService.startTalk(deviceId, channelId);

        log.info("[OpenAPI] voice/talk/start: {} -> sessionId={} state={}",
                channelId, s.getKey(), s.getState());
        return OpenApiResult.ok(voiceSessionData(s));
    }

    /**
     * 推送对讲音频。
     *
     * <p>POST /api/v1/voice/talk?gbDeviceId=...&channelId=...
     * Content-Type: application/octet-stream
     * body: 8kHz / 16bit / 单声道 / 小端 PCM 原始字节
     *
     * <p>要求语音会话已建立（先调 broadcast 或 talk/start，建议状态为
     * TALKING 后推流，与前端 /ws/voice 行为一致）；
     * 会话不存在或已结束返回 409 / 40900。
     */
    @PostMapping("/api/v1/voice/talk")
    public OpenApiResult<Map<String, Object>> voiceTalk(
            @RequestParam String gbDeviceId,
            @RequestParam(required = false) String channelId,
            @RequestBody byte[] pcm) {

        String deviceId = requireDeviceId(gbDeviceId);
        String ch = resolveChannelId(deviceId, channelId);

        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("音频数据不能为空（8kHz/16bit/LE PCM）");
        }

        VoiceSession s = voiceService.getSession(deviceId, ch);
        if (s == null || s.getState() == VoiceSession.State.STOPPED) {
            throw new IllegalStateException("语音会话不存在或已结束，请先调用 broadcast 或 talk/start");
        }

        voiceService.pushAudio(deviceId, ch, pcm);

        log.info("[OpenAPI] voice/talk: {} bytes={}", ch, pcm.length);
        return OpenApiResult.ok(Map.of(
                "gbDeviceId", deviceId,
                "channelId", ch,
                "sessionId", s.getKey(),
                "state", s.getState().name(),
                "acceptedBytes", pcm.length));
    }

    /**
     * 停止语音对讲 / 广播。
     *
     * <p>POST /api/v1/voice/stop
     * body: {"gbDeviceId":"...","channelId":"..."}
     */
    @PostMapping("/api/v1/voice/stop")
    public OpenApiResult<Map<String, Object>> voiceStop(@RequestBody DeviceChannelRequest req) {
        String deviceId = requireDeviceId(req.gbDeviceId());
        String channelId = resolveChannelId(deviceId, req.channelId());

        voiceService.stop(deviceId, channelId);

        log.info("[OpenAPI] voice/stop: {}", channelId);
        return OpenApiResult.ok(Map.of("stopped", true, "gbDeviceId", deviceId, "channelId", channelId));
    }

    // =========================================================
    // 参数校验 / 结果组装（只读 getter，不含业务逻辑）
    // =========================================================

    /** gbDeviceId 必填校验：trim 非空 */
    private static String requireDeviceId(String gbDeviceId) {
        if (gbDeviceId == null || gbDeviceId.trim().isEmpty()) {
            throw new IllegalArgumentException("gbDeviceId 不能为空（GB/T 28181 国标设备编码）");
        }
        return gbDeviceId.trim();
    }

    /** channelId 缺省取 gbDeviceId（与内部接口语义一致） */
    private static String resolveChannelId(String deviceId, String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) {
            return deviceId;
        }
        return channelId.trim();
    }

    /**
     * 解析时间为秒级 epoch：ISO（T 或空格分隔，按 +08:00）/ Instant / 秒级 epoch。
     * 与 DeviceController.parseTime 语义一致（该方法是内部接口私有实现，此处为
     * Open API 参数校验的等价解析，不涉及业务逻辑）。
     */
    private static long parseTime(String s) {
        if (s == null || s.trim().isEmpty()) {
            return -1;
        }
        String t = s.trim().replace(' ', 'T');
        try {
            return java.time.LocalDateTime.parse(t).toEpochSecond(java.time.ZoneOffset.of("+8"));
        } catch (Exception ignore) {
        }
        try {
            return java.time.Instant.parse(t).toEpochMilli() / 1000;
        } catch (Exception ignore) {
        }
        try {
            return Long.parseLong(t);
        } catch (Exception ignore) {
        }
        return -1;
    }

    /**
     * 直播/回放会话结果：sessionId（Call-ID）+ 状态 + 播放地址
     * （地址组装规则与内部接口 sessionJson 一致，仅读会话 getter / ZlmMediaService 地址方法）。
     */
    private Map<String, Object> mediaSessionData(StreamSession s) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.getCallId());
        data.put("gbDeviceId", s.getDeviceId());
        data.put("channelId", s.getChannelId());
        data.put("state", s.getState().name());
        data.put("playback", s.isPlayback());
        data.put("ssrc", s.getSsrc());

        if (s.isZlm()) {
            data.put("media", "zlm");
            data.put("flvUrl", zlmMediaService.flvUrl(s));
            data.put("wsFlvUrl", zlmMediaService.wsFlvUrl(s));
            data.put("hlsUrl", zlmMediaService.hlsUrl(s));
            data.put("webrtcUrl", zlmMediaService.webrtcUrl(s));
        } else {
            data.put("media", "local");
            data.put("flvUrl", s.getLiveFlvUrl());
            if (s.isPlayback()) {
                data.put("playbackFlvUrl", s.getPlaybackFlvUrl());
            }
        }
        return data;
    }

    /**
     * 语音会话结果：sessionId = 会话键 deviceId:channelId（现有唯一稳定标识，
     * Call-ID 由设备反向 INVITE 才产生，未建立时省略）。
     */
    private Map<String, Object> voiceSessionData(VoiceSession s) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", s.getKey());
        data.put("gbDeviceId", s.getDeviceId());
        data.put("channelId", s.getChannelId());
        data.put("state", s.getState().name());
        data.put("stopReason", s.getStopReason());
        data.put("createTime", s.getCreateTime());
        if (s.getCallId() != null) {
            data.put("callId", s.getCallId());
        }
        return data;
    }
}
