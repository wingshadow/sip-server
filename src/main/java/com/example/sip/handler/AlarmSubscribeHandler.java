package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Device;
import com.example.sip.model.SipMessage;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.sip.SipRequestBuilder;
import com.example.sip.sip.SipResponseBuilder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警订阅处理器（SUBSCRIBE / NOTIFY / 自动续订）。
 *
 * <p>GB/T 28181 告警：平台向设备发 SUBSCRIBE（Event: presence，携带 Alarm
 * Query 的 MANSCDP 体），设备以 NOTIFY 上报移动侦测等告警；
 * 订阅有效期 3600s，剩余不足 {@value #RENEW_BEFORE_EXPIRE}s 时自动续订。
 */
@Component
@EnableScheduling
public class AlarmSubscribeHandler {

    private static final Logger log = LoggerFactory.getLogger(AlarmSubscribeHandler.class);

    /** 订阅有效期（秒） */
    private static final int SUBSCRIBE_EXPIRES = 3600;

    /** 剩余不足该秒数时提前续订 */
    private static final long RENEW_BEFORE_EXPIRE = 300;

    private final SipServerProperties props;
    private final DeviceRegistry registry;
    private final SipChannelHolder channelHolder;
    private final com.example.sip.service.AlarmWebhookPush alarmWebhookPush;

    /** CSeq 计数器（订阅事务） */
    private final AtomicLong cseq = new AtomicLong(1);

    /** 设备订阅状态：deviceId -> 订阅信息 */
    private final Map<String, SubscriptionInfo> alarmSubscriptions = new ConcurrentHashMap<>();

    public AlarmSubscribeHandler(SipServerProperties props,
                                 DeviceRegistry registry,
                                 SipChannelHolder channelHolder,
                                 com.example.sip.service.AlarmWebhookPush alarmWebhookPush) {
        this.props = props;
        this.registry = registry;
        this.channelHolder = channelHolder;
        this.alarmWebhookPush = alarmWebhookPush;
    }

    /** 订阅信息（dialog 标识 + 续订计时） */
    private static class SubscriptionInfo {
        String deviceId;
        String callId;
        String fromTag;
        volatile String toTag;
        volatile int expires;
        volatile boolean active;
        volatile long lastStateTime;
    }

    // =========================================================
    // 对外 API（Web 层调用）
    // =========================================================

    /** 订阅设备告警（已订阅则幂等返回） */
    public synchronized Map<String, Object> subscribeAlarm(String deviceId) {
        Map<String, Object> result = new HashMap<>();

        Device device = registry.get(deviceId);
        if (device == null) {
            result.put("success", false);
            result.put("message", "设备不存在");
            return result;
        }

        SubscriptionInfo old = alarmSubscriptions.get(deviceId);
        if (old != null && old.active) {
            result.put("success", true);
            result.put("message", "设备已经订阅");
            result.put("deviceId", deviceId);
            result.put("callId", old.callId);
            return result;
        }

        Channel channel = channelHolder.get();
        if (channel == null) {
            result.put("success", false);
            result.put("message", "SIP UDP Channel 尚未初始化");
            return result;
        }

        String callId = UUID.randomUUID().toString();
        String fromTag = newTag();
        long currentCseq = cseq.incrementAndGet();

        String xml = buildAlarmQueryXml(currentCseq, deviceId);
        byte[] packet = SipRequestBuilder.subscribe(
                props, deviceId, device.getHost(), device.getPort(),
                callId, fromTag, null, currentCseq, SUBSCRIBE_EXPIRES, xml);

        SubscriptionInfo info = new SubscriptionInfo();
        info.deviceId = deviceId;
        info.callId = callId;
        info.fromTag = fromTag;
        info.expires = SUBSCRIBE_EXPIRES;
        info.active = false;
        info.lastStateTime = System.currentTimeMillis();
        alarmSubscriptions.put(deviceId, info);

        send(channel, new InetSocketAddress(device.getHost(), device.getPort()), packet);

        log.info("[28181] >>> 发送报警 SUBSCRIBE: device={} callId={} cseq={}",
                deviceId, callId, currentCseq);

        result.put("success", true);
        result.put("message", "SUBSCRIBE 已发送");
        result.put("deviceId", deviceId);
        result.put("callId", callId);
        result.put("cseq", currentCseq);
        result.put("expires", SUBSCRIBE_EXPIRES);
        return result;
    }

    /** 取消订阅 */
    public synchronized Map<String, Object> unsubscribeAlarm(String deviceId) {
        Map<String, Object> result = new HashMap<>();

        SubscriptionInfo info = alarmSubscriptions.get(deviceId);
        if (info == null) {
            result.put("success", false);
            result.put("message", "当前没有订阅");
            return result;
        }

        Device device = registry.get(deviceId);
        if (device == null) {
            result.put("success", false);
            result.put("message", "设备不存在");
            return result;
        }

        Channel channel = channelHolder.get();
        if (channel == null) {
            result.put("success", false);
            result.put("message", "SIP UDP Channel 尚未初始化");
            return result;
        }

        long currentCseq = cseq.incrementAndGet();
        String xml = buildAlarmQueryXml(currentCseq, deviceId);
        byte[] packet = SipRequestBuilder.subscribe(
                props, deviceId, device.getHost(), device.getPort(),
                info.callId, info.fromTag, info.toTag, currentCseq, 0, xml);

        send(channel, new InetSocketAddress(device.getHost(), device.getPort()), packet);

        info.active = false;
        alarmSubscriptions.remove(deviceId);

        log.info("[28181] >>> 取消报警订阅: device={} callId={} cseq={}",
                deviceId, info.callId, currentCseq);

        result.put("success", true);
        result.put("message", "取消订阅请求已发送");
        result.put("deviceId", deviceId);
        return result;
    }

    /** 当前全部订阅（供前端查询） */
    public Map<String, Object> subscriptions() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, SubscriptionInfo> e : alarmSubscriptions.entrySet()) {
            SubscriptionInfo info = e.getValue();
            Map<String, Object> one = new HashMap<>();
            one.put("deviceId", info.deviceId);
            one.put("callId", info.callId);
            one.put("active", info.active);
            one.put("expires", info.expires);
            out.put(e.getKey(), one);
        }
        return out;
    }

    // =========================================================
    // SIP 消息处理（由 SipRequestHandler 路由进来）
    // =========================================================

    /** 设备反向 SUBSCRIBE（少见）：直接 200 OK */
    public void handleSubscribe(SipMessage req, InetSocketAddress sender, ChannelHandlerContext ctx) {
        SipResponder.reply(ctx, sender, SipResponseBuilder.ok(req, props));
    }

    /** 处理 SUBSCRIBE 响应（200/4xx-6xx） */
    public void handleSubscribeResponse(SipMessage msg, int statusCode) {
        String callId = msg.getHeader("Call-ID");
        SubscriptionInfo info = findByCallId(callId);

        if (info == null) {
            log.warn("[28181] SUBSCRIBE 响应无匹配订阅: callId={} status={}",
                    callId, statusCode);
            return;
        }

        if (statusCode == 200) {
            info.toTag = SipRequestBuilder.extractTag(msg.getHeader("To"));
            info.active = true;
            // 与 1.0 一致：设备回 Expires 头时按其值协商订阅时长，否则用缺省值
            String expHeader = msg.getHeader("Expires");
            if (expHeader != null) {
                try {
                    int devExpires = Integer.parseInt(expHeader.trim());
                    if (devExpires > 0) {
                        info.expires = devExpires;
                    }
                } catch (NumberFormatException ignore) {
                    // 解析失败保留缺省时长
                }
            } else {
                info.expires = SUBSCRIBE_EXPIRES;
            }
            info.lastStateTime = System.currentTimeMillis();
            log.info("[28181] 报警订阅成功: device={} callId={} expires={}s",
                    info.deviceId, callId, info.expires);
        } else {
            info.active = false;
            alarmSubscriptions.remove(info.deviceId);
            log.warn("[28181] 报警订阅失败: device={} status={}", info.deviceId, statusCode);
        }
    }

    /** 处理设备 NOTIFY（告警上报） */
    public void handleNotify(SipMessage msg, Element root) {
        String callId = msg.getHeader("Call-ID");
        SubscriptionInfo info = findByCallId(callId);
        String deviceId = info != null ? info.deviceId
                : SipUtil.extractSipId(msg.getHeader("From"));

        // 订阅状态校准（与 1.0 一致）：active;expires= 刷新续订基准；
        // terminated 移除本地记录，等待自动重新订阅
        handleSubscriptionState(callId, deviceId, msg.getHeader("Subscription-State"));

        String alarmPriority = SipUtil.getXmlValue(root, "AlarmPriority");
        String alarmMethod = SipUtil.getXmlValue(root, "AlarmMethod");
        String alarmTime = SipUtil.getXmlValue(root, "AlarmTime");
        String alarmDescription = SipUtil.getXmlValue(root, "AlarmDescription");
        String alarmType = SipUtil.getXmlValue(root, "AlarmType");

        log.info("[28181] 收到 NOTIFY: device={} callId={} event={} state={} priority={} method={} time={} desc={}",
                deviceId, callId, msg.getHeader("Event"),
                msg.getHeader("Subscription-State"),
                alarmPriority, alarmMethod, alarmTime, alarmDescription);

        // 订阅事件只对 active 状态外发；terminated/校准事件无告警语义
        String state = msg.getHeader("Subscription-State");
        if (state != null && state.startsWith("active")) {
            alarmWebhookPush.push(deviceId, "subscribe", DeviceMessageHandler.alarmFields(
                    alarmPriority, alarmMethod, alarmTime, alarmDescription, alarmType));
        }
    }

    // =========================================================
    // 自动续订
    // =========================================================

    @Scheduled(fixedDelay = 60_000)
    public void renewAlarmSubscriptions() {
        Channel channel = channelHolder.get();
        if (channel == null || alarmSubscriptions.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (SubscriptionInfo info : alarmSubscriptions.values()) {
            try {
                renewIfNeeded(channel, info, now);
            } catch (Exception e) {
                log.error("[28181] 续订异常: device={}", info.deviceId, e);
            }
        }
    }

    private void renewIfNeeded(Channel channel, SubscriptionInfo info, long now) {
        if (!info.active) {
            return;
        }

        long elapsedSec = (now - info.lastStateTime) / 1000;
        long remainSec = info.expires - elapsedSec;
        if (remainSec > RENEW_BEFORE_EXPIRE) {
            return;
        }

        Device device = registry.get(info.deviceId);
        if (device == null) {
            log.warn("[28181] 续订跳过，设备不存在: {}", info.deviceId);
            return;
        }

        long currentCseq = cseq.incrementAndGet();
        String xml = buildAlarmQueryXml(currentCseq, info.deviceId);
        byte[] packet = SipRequestBuilder.subscribe(
                props, info.deviceId, device.getHost(), device.getPort(),
                info.callId, info.fromTag, info.toTag, currentCseq, SUBSCRIBE_EXPIRES, xml);

        send(channel, new InetSocketAddress(device.getHost(), device.getPort()), packet);

        info.expires = SUBSCRIBE_EXPIRES;
        info.lastStateTime = now;

        log.info("[28181] >>> 自动续订报警订阅: device={} callId={} remain={}s",
                info.deviceId, info.callId, remainSec);
    }

    // =========================================================
    // 工具
    // =========================================================

    private SubscriptionInfo findByCallId(String callId) {
        if (callId == null) {
            return null;
        }
        for (SubscriptionInfo info : alarmSubscriptions.values()) {
            if (callId.equals(info.callId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * 处理 NOTIFY 中的 Subscription-State 头（与 1.0 一致）：
     * <ul>
     *   <li>active;expires=xxx —— 订阅生效，按设备给定值校准续订基准；</li>
     *   <li>terminated;reason=xxx —— 设备终止订阅，移除本地记录等待自动重订。</li>
     * </ul>
     */
    private void handleSubscriptionState(String callId,
                                          String notifyDeviceId,
                                          String subscriptionState) {
        if (subscriptionState == null || subscriptionState.isEmpty()) {
            return;
        }

        SubscriptionInfo info = findByCallId(callId);
        if (info == null && notifyDeviceId != null) {
            info = alarmSubscriptions.get(notifyDeviceId);
        }
        if (info == null) {
            return;
        }

        String lower = subscriptionState.trim().toLowerCase();
        if (lower.startsWith("active")) {
            int expires = parseExpiresParam(subscriptionState);
            if (expires > 0) {
                info.expires = expires;
            }
            info.active = true;
            info.lastStateTime = System.currentTimeMillis();
            log.info("[28181] 订阅状态 active: device={} expires={}s",
                    info.deviceId, info.expires);
        } else if (lower.startsWith("terminated")) {
            String reason = extractParam(subscriptionState, "reason");
            info.active = false;
            log.warn("[28181] 订阅被设备终止: device={} reason={} callId={}",
                    info.deviceId, reason, callId);
            alarmSubscriptions.remove(info.deviceId);
        }
    }

    /** 从 Subscription-State 中解析 expires 参数值（如 active;expires=3600） */
    private int parseExpiresParam(String subscriptionState) {
        String v = extractParam(subscriptionState, "expires");
        if (v == null) {
            return -1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 从 "k1=v1;k2=v2" 形式的头域中提取指定参数 */
    private String extractParam(String headerValue, String name) {
        if (headerValue == null) {
            return null;
        }
        for (String part : headerValue.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(name)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static void send(Channel channel, InetSocketAddress target, byte[] packet) {
        SipResponder.send(channel, target, packet);
    }

    private static String newTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String buildAlarmQueryXml(long sn, String deviceId) {
        return "<?xml version=\"1.0\" encoding=\"GB18030\"?>\r\n"
                + "<Query>\r\n"
                + "    <CmdType>Alarm</CmdType>\r\n"
                + "    <SN>" + sn + "</SN>\r\n"
                + "    <DeviceID>" + deviceId + "</DeviceID>\r\n"
                + "    <StartAlarmPriority>0</StartAlarmPriority>\r\n"
                + "    <EndAlarmPriority>0</EndAlarmPriority>\r\n"
                + "    <AlarmMethod>0</AlarmMethod>\r\n"
                + "</Query>\r\n";
    }
}
