package com.example.sip.controller;

import com.example.sip.config.MediaProperties;
import com.example.sip.config.SipServerProperties;
import com.example.sip.handler.AlarmSubscribeHandler;
import com.example.sip.handler.DeviceControlHandler;
import com.example.sip.handler.DeviceQueryHandler;
import com.example.sip.handler.RecordQueryHandler;
import com.example.sip.model.Device;
import com.example.sip.model.StreamSession;
import com.example.sip.model.VoiceSession;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.service.media.MediaService;
import com.example.sip.service.media.RtpPortPool;
import com.example.sip.service.voice.VoiceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 管理接口（/api/**）：设备、直播、回放、下载、录像查询、语音、告警。
 *
 * <p>统一原则：
 * <ul>
 *   <li>控制器只做参数解析与结果组装，业务全部下沉 Service；</li>
 *   <li>媒体模式（local/zlm）由 {@link MediaService} 内部按 {@link MediaProperties#isZlm()} 分发；</li>
 *   <li>直播/回放/下载共用一套会话模型（{@link StreamSession#getType()} 区分）。</li>
 * </ul>
 */
@RestController
public class DeviceController {

    /** 等待 ZLM 注册 MediaSource 的上限（与 FlvController 的 FLV 重定向一致）。 */
    private static final long ZLM_READY_WAIT_MS = 15_000;

    private final DeviceRegistry registry;
    private final MediaService mediaService;
    private final VoiceService voiceService;
    private final AlarmSubscribeHandler alarmHandler;
    private final RecordQueryHandler recordQueryHandler;
    private final DeviceQueryHandler deviceQueryHandler;
    private final DeviceControlHandler deviceControlHandler;
    private final MediaProperties mediaProps;
    private final SipServerProperties props;
    private final RtpPortPool portPool;

    public DeviceController(DeviceRegistry registry,
                            MediaService mediaService,
                            VoiceService voiceService,
                            AlarmSubscribeHandler alarmHandler,
                            RecordQueryHandler recordQueryHandler,
                            DeviceQueryHandler deviceQueryHandler,
                            DeviceControlHandler deviceControlHandler,
                            MediaProperties mediaProps,
                            SipServerProperties props,
                            RtpPortPool portPool) {
        this.registry = registry;
        this.mediaService = mediaService;
        this.voiceService = voiceService;
        this.alarmHandler = alarmHandler;
        this.recordQueryHandler = recordQueryHandler;
        this.deviceQueryHandler = deviceQueryHandler;
        this.deviceControlHandler = deviceControlHandler;
        this.mediaProps = mediaProps;
        this.props = props;
        this.portPool = portPool;
    }

    // =========================================================
    // 设备 / 状态
    // =========================================================

    @GetMapping("/api/devices")
    public List<Map<String, Object>> devices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Device d : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", d.getDeviceId());
            m.put("name", d.getName());
            m.put("host", d.getHost());
            m.put("port", d.getPort());
            m.put("transport", d.getTransport());
            m.put("online", d.isOnline());
            m.put("registerTime", d.getRegisterTime()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            m.put("lastKeepaliveTime", d.getLastKeepaliveTime());
            m.put("catalogUpdateTime", d.getCatalogUpdateTime());
            m.put("channels", d.getChannels());
            out.add(m);
        }
        return out;
    }

    // =========================================================
    // 设备主动查询（NVR 接入：拉通道树 / 设备信息 / 通道状态）
    // =========================================================

    /**
     * 主动查询设备目录（NVR 返回全部子通道，可能分页）。
     *
     * <p>设备主动上报的目录同样会落地注册表；本端点用于
     * “设备刚注册还没上报目录”或“通道变更后主动刷新”场景。
     */
    @PostMapping("/api/devices/{deviceId}/catalog/query")
    public Map<String, Object> queryCatalog(@PathVariable String deviceId) {
        return deviceQueryHandler.queryCatalog(deviceId);
    }

    /** 主动查询设备信息（厂商/型号/固件/通道数） */
    @PostMapping("/api/devices/{deviceId}/info/query")
    public Map<String, Object> queryDeviceInfo(@PathVariable String deviceId) {
        return deviceQueryHandler.queryDeviceInfo(deviceId);
    }

    /** 主动查询设备状态（Online/Status/Encode/Record 等，排障用） */
    @PostMapping("/api/devices/{deviceId}/status/query")
    public Map<String, Object> queryDeviceStatus(@PathVariable String deviceId) {
        return deviceQueryHandler.queryDeviceStatus(deviceId);
    }

    // =========================================================
    // 云台控制 / 预置位（NVR 下挂球机）
    // =========================================================

    /**
     * 云台控制。
     *
     * @param action right/left/down/up/zoomIn/zoomOut/focusNear/focusFar/stop
     * @param speed  1~255，缺省 100（stop 忽略）
     */
    @PostMapping("/api/devices/{deviceId}/channels/{channelId}/ptz")
    public Map<String, Object> ptzControl(@PathVariable String deviceId,
                                          @PathVariable String channelId,
                                          @RequestBody Map<String, String> req) {
        int speed = parseIntOrDefault(req.get("speed"), 100);
        return deviceControlHandler.ptz(deviceId, channelId, req.get("action"), speed);
    }

    /**
     * 预置位操作。
     *
     * @param op       set=设置 / call=调用 / delete=删除
     * @param presetId 预置位号 1~255
     */
    @PostMapping("/api/devices/{deviceId}/channels/{channelId}/preset")
    public Map<String, Object> presetControl(@PathVariable String deviceId,
                                             @PathVariable String channelId,
                                             @RequestBody Map<String, String> req) {
        int presetId = parseIntOrDefault(req.get("presetId"), 1);
        return deviceControlHandler.preset(deviceId, channelId, req.get("op"), presetId);
    }

    /** 自定义 PTZCmd 透传（16 位十六进制，非标设备逃生口） */
    @PostMapping("/api/devices/{deviceId}/channels/{channelId}/ptz/custom")
    public Map<String, Object> customPtz(@PathVariable String deviceId,
                                         @PathVariable String channelId,
                                         @RequestBody Map<String, String> req) {
        return deviceControlHandler.custom(deviceId, channelId, req.get("ptzCmd"));
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverId", props.getId());
        m.put("server", props.getIp() + ":" + props.getPort());
        m.put("deviceCount", registry.all().size());
        m.put("onlineDevices", registry.onlineCount());
        m.put("mediaMode", mediaProps.isZlm() ? "zlm" : "local");
        m.put("mediaSessions", mediaService.sessions().size());
        m.put("voiceSessions", voiceService.sessions().size());
        m.put("rtpPortUsed", portPool.capacity() - portPool.available());
        m.put("rtpPortCapacity", portPool.capacity());
        return m;
    }

    /** 媒体链路概览（端口池 + 会话） */
    @GetMapping("/api/media")
    public Map<String, Object> media() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mediaProps.isZlm() ? "zlm" : "local");
        m.put("rtpPortCapacity", portPool.capacity());
        m.put("rtpPortAvailable", portPool.available());

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (StreamSession s : mediaService.sessions()) {
            sessions.add(sessionJson(s));
        }
        m.put("sessions", sessions);
        return m;
    }

    // =========================================================
    // 直播 LIVE
    // =========================================================

    /** 开始直播（local 先停旧会话再新建；zlm 幂等复用） */
    @PostMapping("/api/play/start")
    public Map<String, Object> startPlay(@RequestBody Map<String, String> req) {
        String deviceId = req.get("deviceId");
        String channelId = req.get("channelId");
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }

        StreamSession s = mediaService.startLive(deviceId, channelId);
        return sessionJson(s);
    }

    /** 停止直播 */
    @PostMapping("/api/play/stop")
    public Map<String, Object> stopPlay(@RequestBody Map<String, String> req) {
        String deviceId = req.get("deviceId");
        String channelId = req.get("channelId");
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }
        mediaService.stop(deviceId, channelId);
        return Map.of("success", true);
    }

    @GetMapping("/api/play/sessions")
    public List<Map<String, Object>> playSessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StreamSession s : mediaService.sessions()) {
            out.add(sessionJson(s));
        }
        return out;
    }

    /**
     * zlm 模式 WebRTC 播放地址（等 MediaSource 就绪后返回）。
     *
     * <p>设备 PS 流内嵌 G.711a(PCMA) 音频，mpegts.js/MSE 无法解码
     * （报 CodecUnsupported 并中断整个播放），改用浏览器原生 WebRTC
     * （PCMA 是其合法编码）。等待语义与 FlvController 的 FLV 重定向一致：
     * 未开始播放返回 error，超时同样返回 error 由前端展示。
     */
    @GetMapping("/api/play/webrtc-url")
    public Map<String, Object> webrtcPlayUrl(@RequestParam String deviceId,
                                             @RequestParam String channelId) {
        return webrtcPlayUrl(deviceId, channelId, "会话不存在，请先开始播放");
    }

    /**
     * zlm 模式 WebRTC 回放地址（等 MediaSource 就绪后返回）。
     *
     * <p>回放流与直播同为设备 PS 送流、经 ZLM rtp app 注册为 MediaSource，
     * 只是 SSRC 不同；信令地址构造与等待语义完全一致。
     */
    @GetMapping("/api/playback/webrtc-url")
    public Map<String, Object> webrtcPlaybackUrl(@RequestParam String deviceId,
                                                 @RequestParam String channelId) {
        return webrtcPlayUrl(deviceId, channelId, "会话不存在，请先开始回放");
    }

    /**
     * WebRTC 信令地址的公共实现：等 ZLM 注册 MediaSource 后返回。
     *
     * <p>设备 PS 流内嵌 G.711a(PCMA) 音频，mpegts.js/MSE 无法解码
     * （报 CodecUnsupported 并中断整个播放），改用浏览器原生 WebRTC
     * （PCMA 是其合法编码）。等待语义与 FlvController 的 FLV 重定向一致：
     * 未开始播放返回 error，超时同样返回 error 由前端展示。
     *
     * @param notStartedMsg 会话不存在时的提示（按直播/回放区分措辞）
     */
    private Map<String, Object> webrtcPlayUrl(String deviceId, String channelId,
                                              String notStartedMsg) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }
        StreamSession s = mediaService.getSession(deviceId, channelId);
        if (s == null) {
            r.put("error", notStartedMsg);
            return r;
        }
        if (!mediaProps.isZlm() || !s.isZlm()) {
            r.put("error", "WebRTC 仅 zlm 模式支持");
            return r;
        }
        if (!mediaService.waitZlmReady(s, ZLM_READY_WAIT_MS)) {
            r.put("error", "媒体流建立超时(" + ZLM_READY_WAIT_MS / 1000
                    + "s)，请检查设备推流是否到达 ZLM");
            return r;
        }
        r.put("webrtcUrl", mediaService.zlmWebrtcUrl(s));
        return r;
    }

    // =========================================================
    // 回放 / 下载 PLAYBACK / DOWNLOAD
    // =========================================================

    /**
     * 开始回放。
     *
     * <p>startTime/endTime 支持 {@code 2026-01-01T00:00:00} 或秒级 epoch。
     */
    @PostMapping("/api/playback/start")
    public Map<String, Object> startPlayback(@RequestBody Map<String, String> req) {
        String deviceId = req.get("deviceId");
        String channelId = req.get("channelId");
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }
        long start = parseTime(req.get("startTime"));
        long end = parseTime(req.get("endTime"));
        // 与 1.0 一致：仅拒绝 end < start（end == start 视为零长度回放，合法）
        if (start <= 0 || end <= 0 || end < start) {
            throw new IllegalArgumentException("时间参数无效，需为合法时间且 end >= start");
        }

        // 幂等发起：同设备+通道+时间范围的重复请求复用现有会话，
        // 不拆建（BYE+INVITE 风暴是摄像机宕机的典型诱因）。
        // 时间范围不同仍会正常拆建（用户主动切换回放片段不受影响）。
        StreamSession s = mediaService.getOrStartPlayback(deviceId, channelId, start, end);
        return sessionJson(s);
    }

    /** 停止回放（同时结束下载会话） */
    @PostMapping("/api/playback/stop")
    public Map<String, Object> stopPlayback(@RequestBody Map<String, String> req) {
        String deviceId = req.get("deviceId");
        String channelId = req.get("channelId");
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }
        mediaService.stop(deviceId, channelId);
        return Map.of("success", true);
    }

    @GetMapping("/api/playback/sessions")
    public List<Map<String, Object>> playbackSessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StreamSession s : mediaService.sessions()) {
            out.add(sessionJson(s));
        }
        return out;
    }

    /**
     * 开始录像下载（local 模式：MP4 .part → 完成改名）。
     *
     * <p>返回 downloadFileName/downloadUrl，文件由 {@link
     * com.example.sip.controller.FlvController} 的下载端点提供。
     */
    @PostMapping("/api/download/start")
    public Map<String, Object> startDownload(@RequestBody Map<String, String> req) {
        String deviceId = req.get("deviceId");
        String channelId = req.get("channelId");
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }
        long start = parseTime(req.get("startTime"));
        long end = parseTime(req.get("endTime"));
        // 与 1.0 一致：仅拒绝 end < start
        if (start <= 0 || end <= 0 || end < start) {
            throw new IllegalArgumentException("时间参数无效，需为合法时间且 end >= start");
        }

        // 幂等发起：同设备+通道+时间范围的重复下载请求复用现有会话，
        // 不拆建（设备侧重复开回放流+解码器是压力主因之一）
        StreamSession s = mediaService.getOrStartDownload(deviceId, channelId, start, end);
        Map<String, Object> resp = sessionJson(s);
        // 回带协商倍速：前端据此估算"设备推流/转码"阶段耗时（录像时长/倍速）
        resp.put("downloadSpeed", mediaProps.getLocal().getDownloadSpeed());
        return resp;
    }

    // =========================================================
    // 录像查询 RECORD
    // =========================================================

    /** 录像检索（按时间窗，聚合设备分包应答） */
    @PostMapping("/api/records/query")
    public Map<String, Object> queryRecords(@RequestBody Map<String, String> req) {
        String deviceId = req.get("deviceId");
        String channelId = req.get("channelId");
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }
        long start = req.containsKey("startTime")
                ? parseTime(req.get("startTime"))
                : 0L;
        long end = req.containsKey("endTime")
                ? parseTime(req.get("endTime"))
                : System.currentTimeMillis() / 1000L;
        if (start < 0 || end <= 0 || end < start) {
            throw new IllegalArgumentException("时间参数无效，需为合法时间且 end >= start");
        }
        return recordQueryHandler.queryRecords(deviceId, channelId, start, end);
    }

    /** 已检索到的录像列表（前端结果页数据源同 query 返回） */
    @GetMapping("/api/records")
    public Map<String, Object> records() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", "请使用 POST /api/records/query 按设备与时间窗检索");
        m.put("sessions", mediaService.sessions().size());
        return m;
    }

    // =========================================================
    // 语音对讲 VOICE
    // =========================================================

    /**
     * 开始语音会话。
     *
     * @param mode talk=平台主动 INVITE 双向对讲（默认）；
     *             broadcast=平台主动广播（MESSAGE 通知 + s=Broadcast INVITE，单向喊话）；
     *             legacy-broadcast=旧广播（设备反向 INVITE 推流，方向为设备→平台）
     */
    @PostMapping("/api/voice/start")
    public Map<String, Object> startVoice(@RequestBody Map<String, String> req) {
        String deviceId = req.get("deviceId");
        String channelId = req.get("channelId");
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }
        String mode = req.getOrDefault("mode", "talk");

        VoiceSession s;
        if ("broadcast".equalsIgnoreCase(mode)) {
            s = voiceService.startBroadcastTalk(deviceId, channelId);
        } else if ("legacy-broadcast".equalsIgnoreCase(mode)) {
            s = voiceService.start(deviceId, channelId);
        } else {
            s = voiceService.startTalk(deviceId, channelId);
        }
        return voiceJson(s);
    }

    @PostMapping("/api/voice/stop")
    public Map<String, Object> stopVoice(@RequestBody Map<String, String> req) {
        String deviceId = req.get("deviceId");
        String channelId = req.get("channelId");
        if (channelId == null || channelId.isEmpty()) {
            channelId = deviceId;
        }
        voiceService.stop(deviceId, channelId);
        return Map.of("success", true);
    }

    @GetMapping("/api/voice/sessions")
    public List<Map<String, Object>> voiceSessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VoiceSession s : voiceService.sessions()) {
            out.add(voiceJson(s));
        }
        return out;
    }

    // =========================================================
    // 告警订阅 ALARM
    // =========================================================

    @PostMapping("/api/alarm/subscribe")
    public Map<String, Object> subscribeAlarm(@RequestBody Map<String, String> req) {
        return alarmHandler.subscribeAlarm(req.get("deviceId"));
    }

    @PostMapping("/api/alarm/unsubscribe")
    public Map<String, Object> unsubscribeAlarm(@RequestBody Map<String, String> req) {
        return alarmHandler.unsubscribeAlarm(req.get("deviceId"));
    }

    @GetMapping("/api/alarm/subscriptions")
    public Map<String, Object> alarmSubscriptions() {
        return alarmHandler.subscriptions();
    }

    // =========================================================
    // 结果组装（只读 getter，无业务逻辑）
    // =========================================================

    private Map<String, Object> sessionJson(StreamSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callId", s.getCallId());
        m.put("deviceId", s.getDeviceId());
        m.put("channelId", s.getChannelId());
        m.put("type", s.getType().name());
        m.put("state", s.getState().name());
        m.put("zlm", s.isZlm());
        m.put("ssrc", s.getSsrc());
        m.put("localPort", s.getLocalPort());
        m.put("playback", s.isPlayback());
        m.put("createTime", s.getCreateTime());

        if (s.isPlayback()) {
            m.put("playbackStart", s.getPlaybackStart());
            m.put("playbackEnd", s.getPlaybackEnd());
        }

        if (mediaProps.isZlm() && s.isZlm()) {
            m.put("flvUrl", s.getFlvUrl());
        } else {
            m.put("liveFlvUrl", s.getLiveFlvUrl());
            // 兼容 1.0 契约：local 模式同样输出 flvUrl（回放优先 playbackFlvUrl）
            m.put("flvUrl", s.isPlayback() ? s.getPlaybackFlvUrl() : s.getLiveFlvUrl());
            if (s.isPlayback()) {
                m.put("playbackFlvUrl", s.getPlaybackFlvUrl());
            }
            // RTP 收流统计（1.0 契约字段：receivedPackets 等）
            m.putAll(mediaService.rtpStats(s.getCallId()));
        }

        if (s.getType() == StreamSession.Type.DOWNLOAD) {
            m.put("downloadFileName", s.getDownloadFileName());
            m.put("downloadUrl", s.getDownloadUrl());
        }
        return m;
    }

    private Map<String, Object> voiceJson(VoiceSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.getKey());
        m.put("deviceId", s.getDeviceId());
        m.put("channelId", s.getChannelId());
        m.put("broadcast", s.isBroadcast());
        m.put("state", s.getState().name());
        m.put("stopReason", s.getStopReason());
        m.put("ssrc", s.getSsrc());
        m.put("audioIp", s.getAudioIp());
        m.put("audioPort", s.getAudioPort());
        m.put("payloadType", s.getPayloadType());
        m.put("localPort", s.getLocalPort());
        m.put("packetsSent", s.getPacketsSent());
        m.put("createTime", s.getCreateTime());
        m.put("talkStartTime", s.getTalkStartTime());
        if (s.getCallId() != null) {
            m.put("callId", s.getCallId());
        }
        if (s.getBroadcastCallId() != null) {
            m.put("broadcastCallId", s.getBroadcastCallId());
        }
        return m;
    }

    /**
     * 回放媒体控制（MANSRTSP over SIP INFO）：暂停/继续/倍速/拖动。
     *
     * <p>body: {@code {"action":"pause|resume|scale|seek", "speed":2.0, "startTime":"..."}}
     * <ul>
     *   <li>仅 local 模式的回放会话；zlm 模式会话 Dialog 由 ZlmMediaService 托管，会明确拒绝</li>
     *   <li>seek 的 startTime 须落在回放区间内（ISO 或 epoch 秒）</li>
     * </ul>
     */
    @PostMapping("/api/playback/control")
    public Map<String, Object> playbackControl(@RequestBody Map<String, String> req) {
        double speed = parseDoubleOrDefault(req.get("speed"), 1.0);
        long seekStart = parseTime(req.get("startTime"));
        return mediaService.playbackControl(
                req.get("deviceId"), req.get("channelId"), req.get("action"), speed, seekStart);
    }

    /** 解析整数参数，空/非法时回落默认值（云台速度、预置位号等） */
    private static int parseIntOrDefault(String s, int def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 解析 double 参数，空/非法时回落默认值（回放倍速等） */
    private static double parseDoubleOrDefault(String s, double def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 解析时间参数为秒级 epoch：
     * 支持 ISO（T 或空格分隔，+8 时区）、Instant、秒级 epoch。
     */
    private static long parseTime(String s) {
        if (s == null || s.trim().isEmpty()) {
            return -1;
        }
        String t = s.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(t).atZone(ZoneId.of("+8")).toEpochSecond();
        } catch (Exception ignore) {
            // 继续尝试其他格式
        }
        try {
            return java.time.Instant.parse(t).getEpochSecond();
        } catch (Exception ignore) {
            // 继续尝试其他格式
        }
        try {
            return Long.parseLong(t);
        } catch (Exception ignore) {
            // 继续尝试其他格式
        }
        return -1;
    }
}
