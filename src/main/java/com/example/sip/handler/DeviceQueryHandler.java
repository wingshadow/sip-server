package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Channel;
import com.example.sip.model.Device;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.sip.SipRequestBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设备查询处理器（Catalog / DeviceInfo / DeviceStatus）。
 *
 * <p>平台主动向设备发 MANSCDP 查询 MESSAGE，聚合设备应答后返回调用方。
 * 这是接入 NVR 的第一步：NVR 下挂几十路通道，部分 NVR 默认不上报目录，
 * 平台必须主动查询才能拿到通道树。
 *
 * <p>与 {@link RecordQueryHandler} 同构（请求-聚合-超时），但按
 * {@code deviceId} 维度的单在途查询（同设备同时只允许一个查询，
 * 新查询会把旧查询置为完成态），而非按 SN：设备对查询的应答
 * 只回带 DeviceID/SN，按设备维度匹配更稳。
 *
 * <p>目录应答的<b>注册表落地不走这里</b>：{@link DeviceMessageHandler}
 * 的 Catalog 正常处理路径（分页聚合 + updateChannels）对平台主动查询
 * 与设备主动上报一视同仁；本类只负责把结果回给查询调用方。
 */
@Component
public class DeviceQueryHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceQueryHandler.class);

    /** 单次查询等待超时（毫秒）：NVR 64 路分页目录可能数秒才收齐 */
    private static final long QUERY_TIMEOUT_MILLIS = 10_000;

    /** SN 序列（查询事务，与录像查询各自独立计数） */
    private final AtomicLong snSeq = new AtomicLong(1);

    /** deviceId -> 在途查询上下文（同设备仅一个） */
    private final Map<String, QueryContext> pending = new ConcurrentHashMap<>();

    private final SipServerProperties props;
    private final DeviceRegistry registry;
    private final SipChannelHolder channelHolder;

    public DeviceQueryHandler(SipServerProperties props,
                              DeviceRegistry registry,
                              SipChannelHolder channelHolder) {
        this.props = props;
        this.registry = registry;
        this.channelHolder = channelHolder;
    }

    /** 在途查询上下文 */
    private static class QueryContext {
        final String deviceId;
        final String sn;
        final String type;
        final List<Channel> channels = new ArrayList<>();
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        volatile int sumNum = -1;
        volatile boolean completed;

        QueryContext(String deviceId, String sn, String type) {
            this.deviceId = deviceId;
            this.sn = sn;
            this.type = type;
        }
    }

    // =========================================================
    // 对外 API（Web 层调用）
    // =========================================================

    /**
     * 主动查询设备目录（NVR 返回其下全部通道，可能分页）。
     *
     * @return channelCount / channels / sumNum / complete / timedOut
     */
    public Map<String, Object> queryCatalog(String deviceId) {
        return sendQuery(deviceId, "Catalog",
                "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n"
                        + "<Query>\r\n"
                        + "    <CmdType>Catalog</CmdType>\r\n"
                        + "    <SN>%s</SN>\r\n"
                        + "    <DeviceID>" + deviceId + "</DeviceID>\r\n"
                        + "</Query>\r\n");
    }

    /** 主动查询设备信息（厂商/型号/固件/通道数） */
    public Map<String, Object> queryDeviceInfo(String deviceId) {
        return sendQuery(deviceId, "DeviceInfo",
                "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n"
                        + "<Query>\r\n"
                        + "    <CmdType>DeviceInfo</CmdType>\r\n"
                        + "    <SN>%s</SN>\r\n"
                        + "    <DeviceID>" + deviceId + "</DeviceID>\r\n"
                        + "</Query>\r\n");
    }

    /**
     * 主动查询设备实时状态（通道在线/编码/录像/告警状态位）。
     *
     * <p>运维排障入口：目录里 Status=OFF 的通道到底能不能播，
     * 以这里的 Online/Status 字段为准。
     */
    public Map<String, Object> queryDeviceStatus(String deviceId) {
        return sendQuery(deviceId, "DeviceStatus",
                "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n"
                        + "<Query>\r\n"
                        + "    <CmdType>DeviceStatus</CmdType>\r\n"
                        + "    <SN>%s</SN>\r\n"
                        + "    <DeviceID>" + deviceId + "</DeviceID>\r\n"
                        + "</Query>\r\n");
    }

    /**
     * 发送查询并等待聚合结果。
     *
     * @param xmlTemplate 查询 XML 模板，{@code %s} 占位符由 SN 填充
     */
    private Map<String, Object> sendQuery(String deviceId, String type, String xmlTemplate) {
        Device device = registry.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        // 与 com.example.sip.model.Channel 同名，netty 侧此处全限定
        io.netty.channel.Channel channel = channelHolder.get();
        if (channel == null) {
            throw new IllegalStateException("SIP UDP Channel 尚未初始化");
        }

        // 同设备在途查询：旧的先置完成（防调用方永久挂起），新的替换
        QueryContext prev = pending.remove(deviceId);
        if (prev != null) {
            prev.completed = true;
            prev.future.complete(timeoutResult(prev, true));
        }

        String sn = String.valueOf(snSeq.incrementAndGet());
        QueryContext ctx = new QueryContext(deviceId, sn, type);
        pending.put(deviceId, ctx);

        String xml = String.format(xmlTemplate, sn);
        byte[] packet = SipRequestBuilder.message(
                props, device.getHost(), device.getPort(), deviceId,
                UUID.randomUUID().toString(), sn, xml);

        SipResponder.send(channel, new InetSocketAddress(device.getHost(), device.getPort()), packet);
        log.info("[28181] >>> 发送设备查询 {}: device={} sn={}", type, deviceId, sn);

        try {
            return ctx.future.get(QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return timeoutResult(ctx, true);
        } finally {
            if (pending.remove(deviceId, ctx)) {
                ctx.completed = true;
            }
        }
    }

    // =========================================================
    // 应答处理（由 DeviceMessageHandler 路由进来）
    // =========================================================

    /**
     * Catalog 应答：按设备维度累计通道，收齐 SumNum 条即完成。
     * 未匹配在途查询（设备主动上报）时静默忽略——落地由
     * DeviceMessageHandler 的正常路径负责。
     */
    public void onCatalogResponse(String deviceId, String sn, int sumNum, List<Channel> channels) {
        QueryContext ctx = pending.get(deviceId);
        if (ctx == null || ctx.completed || !"Catalog".equals(ctx.type)) {
            return;
        }

        List<Channel> snapshot;
        boolean done;
        synchronized (ctx) {
            ctx.channels.addAll(channels);
            if (sumNum >= 0) {
                ctx.sumNum = sumNum;
            }
            // SumNum 缺失（<0）按单包全量语义，首包即完成；
            // SumNum=0 表示设备无通道，同样立即完成
            done = ctx.sumNum <= 0 || ctx.channels.size() >= ctx.sumNum;
            snapshot = new ArrayList<>(ctx.channels);
        }

        log.info("[28181] 目录查询应答: device={} sn={} 本包 {} 条, 累计 {} 条 (sum={})",
                deviceId, sn, channels.size(), snapshot.size(), ctx.sumNum);

        if (done) {
            completeChannels(ctx, snapshot);
        }
    }

    /**
     * DeviceInfo / DeviceStatus 应答：提取 Result + 类型相关字段。
     * 单包语义，首包即完成。
     */
    public void onQueryResponse(String cmdType, String deviceId, Element root) {
        QueryContext ctx = pending.get(deviceId);
        if (ctx == null || ctx.completed || !cmdType.equals(ctx.type)) {
            return;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("deviceId", deviceId);
        fields.put("type", cmdType);
        String result = text(root, "Result");
        fields.put("result", result == null ? "" : result);
        if (result != null && !"OK".equalsIgnoreCase(result)) {
            completeFields(ctx, fields);
            return;
        }
        for (String key : "DeviceInfo".equals(cmdType)
                ? INFO_FIELDS : STATUS_FIELDS) {
            String v = text(root, key);
            if (v != null) {
                fields.put(key, v);
            }
        }
        completeFields(ctx, fields);
    }

    // =========================================================
    // 内部
    // =========================================================

    /** 完成查询：目录类（payload 为通道列表） */
    private void completeChannels(QueryContext ctx, List<Channel> channels) {
        Map<String, Object> result = new HashMap<>();
        result.put("deviceId", ctx.deviceId);
        result.put("sn", ctx.sn);
        result.put("type", ctx.type);
        result.put("complete", true);
        result.put("channels", channels);
        result.put("channelCount", channels.size());
        result.put("sumNum", ctx.sumNum);
        ctx.future.complete(result);
    }

    /** 完成查询：字段类（payload 为键值对，DeviceInfo/DeviceStatus） */
    private void completeFields(QueryContext ctx, Map<String, Object> fields) {
        Map<String, Object> result = new HashMap<>();
        result.put("deviceId", ctx.deviceId);
        result.put("sn", ctx.sn);
        result.put("type", ctx.type);
        result.put("complete", true);
        result.putAll(fields);
        ctx.future.complete(result);
    }

    private Map<String, Object> timeoutResult(QueryContext ctx, boolean timedOut) {
        Map<String, Object> result = new HashMap<>();
        result.put("deviceId", ctx.deviceId);
        result.put("sn", ctx.sn);
        result.put("type", ctx.type);
        result.put("complete", false);
        if (timedOut) {
            result.put("timedOut", true);
        }
        if ("Catalog".equals(ctx.type)) {
            List<Channel> snapshot;
            synchronized (ctx) {
                snapshot = new ArrayList<>(ctx.channels);
            }
            result.put("channels", snapshot);
            result.put("channelCount", snapshot.size());
            result.put("sumNum", ctx.sumNum);
        }
        return result;
    }

    private static String text(Element root, String tag) {
        var nodes = root.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String v = nodes.item(0).getTextContent();
        return v == null ? null : v.trim();
    }

    /** DeviceInfo 应答关注的字段 */
    private static final List<String> INFO_FIELDS = List.of(
            "Manufacturer", "Model", "Firmware", "ChannelCount", "Name");

    /** DeviceStatus 应答关注的字段（通道级状态位，NVR 排障核心） */
    private static final List<String> STATUS_FIELDS = List.of(
            "Online", "Status", "Reason", "Encode", "Record", "Alarm", "DeviceTime");
}
