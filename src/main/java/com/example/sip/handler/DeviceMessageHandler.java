package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Channel;
import com.example.sip.model.SipMessage;
import com.example.sip.service.CatalogService;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.sip.SipResponseBuilder;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MANSCDP MESSAGE 处理器（Keepalive / Catalog / Alarm / RecordInfo / DeviceInfo / DeviceStatus）。
 *
 * <p>设备通过 SIP MESSAGE 上报心跳、目录、告警、录像查询结果，
 * 统一入口为 {@link #handle(SipMessage, InetSocketAddress, ChannelHandlerContext)}。
 * RecordInfo 应答转交 {@link RecordQueryHandler} 做聚合；Catalog 分页
 * （SumNum 为总量、多包 Item）由本类按 {@code deviceId#SN} 聚合后整体落地。
 * Catalog/DeviceInfo/DeviceStatus 同时可能是平台主动查询的应答，
 * 旁路通知 {@link DeviceQueryHandler}（在途查询匹配则完成 Future）。
 */
@Component
public class DeviceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceMessageHandler.class);

    private final SipServerProperties props;
    private final CatalogService catalogService;
    private final DeviceRegistry registry;
    private final RecordQueryHandler recordQueryHandler;
    private final DeviceQueryHandler deviceQueryHandler;
    private final com.example.sip.service.AlarmWebhookPush alarmWebhookPush;

    public DeviceMessageHandler(SipServerProperties props,
                                CatalogService catalogService,
                                DeviceRegistry registry,
                                RecordQueryHandler recordQueryHandler,
                                DeviceQueryHandler deviceQueryHandler,
                                com.example.sip.service.AlarmWebhookPush alarmWebhookPush) {
        this.props = props;
        this.catalogService = catalogService;
        this.registry = registry;
        this.recordQueryHandler = recordQueryHandler;
        this.deviceQueryHandler = deviceQueryHandler;
        this.alarmWebhookPush = alarmWebhookPush;
    }

    /** 目录分包聚合 TTL：超时未收齐按已收到的部分落地（防设备漏发末包导致目录永缺） */
    private static final long CATALOG_AGG_TTL_MILLIS = 30_000;

    /** 分包保护上限（防止异常设备无限分包） */
    private static final int MAX_CATALOG_PACKETS = 200;

    /** 目录分包聚合：key = {@code deviceId + "#" + SN} */
    private final Map<String, CatalogAgg> catalogAggs = new ConcurrentHashMap<>();

    public void handle(SipMessage msg, InetSocketAddress sender, ChannelHandlerContext ctx) {
        byte[] body = msg.getBodyBytes();
        if (body == null || body.length == 0) {
            log.warn("[28181] MESSAGE 无消息体，忽略");
            return;
        }

        Document doc;
        try {
            doc = SipUtil.parseXml(body);
        } catch (Exception e) {
            log.warn("[28181] MESSAGE XML 解析失败: {}", e.getMessage());
            return;
        }

        Element root = doc.getDocumentElement();
        String cmdType = SipUtil.getXmlValue(root, "CmdType");
        if (cmdType == null) {
            log.warn("[28181] MESSAGE 缺少 CmdType，忽略");
            return;
        }

        switch (cmdType) {
            case "Keepalive":
                handleKeepalive(msg, root, sender);
                break;
            case "Catalog":
                // Catalog 自行回带 MANSCDP body 的 200 OK，不走末尾通用应答
                handleCatalog(msg, root, sender, ctx);
                return;
            case "Alarm":
                handleAlarm(msg, root);
                break;
            case "RecordInfo":
                recordQueryHandler.handleResponse(msg, root);
                break;
            case "DeviceInfo":
            case "DeviceStatus":
                // 平台主动查询的应答：无在途查询时静默丢弃（设备不会主动上报）
                deviceQueryHandler.onQueryResponse(cmdType,
                        SipUtil.extractSipId(msg.getHeader("From")), root);
                break;
            default:
                log.info("[28181] 未处理的MANSCDP消息: CmdType={} from={}",
                        cmdType, SipUtil.extractSipId(msg.getHeader("From")));
        }

        // MANSCDP MESSAGE 统一回 200 OK：设备依赖该应答完成 SIP 事务
        // （Keepalive/Catalog/Alarm/RecordInfo/未知 CmdType 均回，与 1.0 行为一致）
        SipResponder.reply(ctx, sender, SipResponseBuilder.ok(msg, props));
    }

    /** 心跳：刷新设备活跃时间（未登记设备自动登记，兼容服务器重启场景） */
    private void handleKeepalive(SipMessage msg, Element root, InetSocketAddress sender) {
        String deviceId = SipUtil.extractSipId(msg.getHeader("From"));
        if (deviceId == null) {
            deviceId = SipUtil.getXmlValue(root, "DeviceID");
        }
        if (deviceId == null) {
            return;
        }
        registry.keepalive(deviceId, sender.getAddress().getHostAddress(), sender.getPort());
        log.debug("[28181] 心跳: {}", deviceId);
    }

    /** 目录：解析通道列表并更新设备，回带平台设备列表 XML 的 200 OK */
    private void handleCatalog(SipMessage msg, Element root, InetSocketAddress sender,
                               ChannelHandlerContext ctx) {
        String deviceId = SipUtil.extractSipId(msg.getHeader("From"));
        if (deviceId == null) {
            deviceId = SipUtil.getXmlValue(root, "DeviceID");
        }
        if (deviceId == null) {
            SipResponder.reply(ctx, sender, SipResponseBuilder.ok(msg, props));
            return;
        }

        List<Channel> channels = new ArrayList<>();
        NodeList items = root.getElementsByTagName("Item");
        for (int i = 0; i < items.getLength(); i++) {
            Node node = items.item(i);
            if (node instanceof Element) {
                Channel c = SipUtil.parseChannel((Element) node);
                if (c != null) {
                    channels.add(c);
                }
            }
        }

        String sn = SipUtil.getXmlValue(root, "SN");
        int sum = -1;
        try {
            sum = Integer.parseInt(SipUtil.getXmlValue(root, "SumNum"));
        } catch (Exception ignore) {
            // 忽略：部分设备不带 SumNum，按单包全量处理
        }

        registry.keepalive(deviceId, sender.getAddress().getHostAddress(), sender.getPort());

        List<Channel> toApply;
        if (sum > 0) {
            // 分页目录：按 deviceId#SN 聚合，收齐 SumNum 条才整体覆盖，
            // 避免后到的分片包把先到的通道全量冲掉（旧行为：每包 setChannels 覆盖）
            flushExpiredCatalogAggs();
            toApply = mergeCatalog(deviceId, sn, sum, channels);
        } else {
            // SumNum 缺失或为 0：单包全量语义，直接覆盖
            toApply = channels;
        }

        // 平台主动目录查询的在途应答：匹配则完成 Future（不影响下方落地逻辑）
        deviceQueryHandler.onCatalogResponse(deviceId, sn, sum, channels);

        if (toApply != null) {
            DeviceRegistryLocal.update(registry, deviceId, toApply);
            log.info("[28181] 目录更新: device={} channels={} sn={} sum={}",
                    deviceId, toApply.size(), sn, sum);
        }

        // 与 1.0 一致：Catalog 应答带 MANSCDP body（平台设备列表 XML，
        // 供上级平台目录查询/级联场景；设备上报目录同样收到带 body 的 200 OK）
        String xmlResp = catalogService.buildCatalogXml(props.getId(), sn, registry.all());
        SipResponder.reply(ctx, sender, SipResponseBuilder.build(
                msg, 200, "OK", "Application/MANSCDP+xml", xmlResp, props));
    }

    /**
     * 目录分包聚合：SumNum&gt;0 时按 {@code deviceId#SN} 累计，收齐 SumNum 条返回合并结果；
     * 未收齐返回 {@code null}（等待后续分片，本包不落地）。
     * 同设备出现新 SN 时丢弃未完成的旧聚合（设备重报目录场景）。
     */
    private List<Channel> mergeCatalog(String deviceId, String sn, int sum, List<Channel> packetChannels) {
        String key = deviceId + "#" + (sn == null ? "" : sn);
        CatalogAgg agg = catalogAggs.get(key);
        if (agg == null) {
            CatalogAgg fresh = new CatalogAgg(deviceId, sn);
            CatalogAgg prev = catalogAggs.putIfAbsent(key, fresh);
            agg = prev != null ? prev : fresh;
            if (prev == null) {
                // 同设备旧聚合（SN 变更/重报）作废，防止过期后把旧部分数据覆盖新目录
                catalogAggs.entrySet().removeIf(e ->
                        !e.getKey().equals(key) && e.getValue().deviceId.equals(deviceId));
                log.info("[28181] 目录分页开始: device={} sn={} sum={}", deviceId, sn, sum);
            }
        }

        List<Channel> merged;
        synchronized (agg) {
            agg.sumNum = sum;
            agg.channels.addAll(packetChannels);
            agg.packets++;
            log.info("[28181] 目录分页: device={} sn={} sum={} 本包 {} 条, 累计 {} 条 (第 {} 包)",
                    deviceId, sn, sum, packetChannels.size(), agg.channels.size(), agg.packets);
            if (agg.channels.size() >= sum || agg.packets > MAX_CATALOG_PACKETS) {
                merged = new ArrayList<>(agg.channels);
                catalogAggs.remove(key);
                log.info("[28181] 目录分页完成: device={} sn={} 共 {} 条 (sum={})",
                        deviceId, sn, merged.size(), sum);
            } else {
                merged = null;
            }
        }
        return merged;
    }

    /**
     * 惰性清理过期的目录聚合：超时未收齐按已收到的部分落地。
     * 随每次分页 Catalog 包触发，无需定时任务（聚合条目数 = 分页中的设备数，量极小）。
     */
    private void flushExpiredCatalogAggs() {
        long now = System.currentTimeMillis();
        catalogAggs.entrySet().removeIf(e -> {
            CatalogAgg agg = e.getValue();
            if (now - agg.startTime < CATALOG_AGG_TTL_MILLIS) {
                return false;
            }
            List<Channel> partial;
            synchronized (agg) {
                partial = new ArrayList<>(agg.channels);
            }
            if (partial.isEmpty()) {
                // 聚合为空（无有效 Item）：直接丢弃，不用空列表覆盖设备已有目录
                return true;
            }
            log.warn("[28181] 目录分页超时，按已收到部分落地: device={} sn={} 累计 {} 条 (sum={})",
                    agg.deviceId, agg.sn, partial.size(), agg.sumNum);
            DeviceRegistryLocal.update(registry, agg.deviceId, partial);
            return true;
        });
    }

    /** 告警：记录日志 + 外发 Webhook（异步，不阻塞信令线程） */
    private void handleAlarm(SipMessage msg, Element root) {
        String deviceId = SipUtil.extractSipId(msg.getHeader("From"));
        String alarmPriority = SipUtil.getXmlValue(root, "AlarmPriority");
        String alarmMethod = SipUtil.getXmlValue(root, "AlarmMethod");
        String alarmTime = SipUtil.getXmlValue(root, "AlarmTime");
        String alarmDescription = SipUtil.getXmlValue(root, "AlarmDescription");
        String alarmType = SipUtil.getXmlValue(root, "AlarmType");
        log.info("[28181] 设备告警: device={} priority={} method={} time={} desc={}",
                deviceId, alarmPriority, alarmMethod, alarmTime, alarmDescription);

        alarmWebhookPush.push(deviceId, "message", alarmFields(
                alarmPriority, alarmMethod, alarmTime, alarmDescription, alarmType));
    }

    /** 组装告警外发字段（null 值剔除，避免 JSON 出现空串歧义） */
    static Map<String, String> alarmFields(String priority, String method,
                                           String time, String description, String type) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        putIfPresent(fields, "alarmPriority", priority);
        putIfPresent(fields, "alarmMethod", method);
        putIfPresent(fields, "alarmTime", time);
        putIfPresent(fields, "alarmDescription", description);
        putIfPresent(fields, "alarmType", type);
        return fields;
    }

    private static void putIfPresent(Map<String, String> m, String k, String v) {
        if (v != null && !v.isEmpty()) {
            m.put(k, v);
        }
    }

    /** 设备名刷新的小工具（避免 RegisterHandler 依赖） */
    private static final class DeviceRegistryLocal {
        static void update(DeviceRegistry registry, String deviceId, List<Channel> channels) {
            com.example.sip.model.Device d = registry.get(deviceId);
            if (d != null) {
                d.setChannels(channels);
                registry.updateChannels(d);
            }
        }
    }

    /** 目录分包聚合上下文（SumNum 分页场景，字段在 synchronized(agg) 内访问） */
    private static final class CatalogAgg {
        final String deviceId;
        final String sn;
        final List<Channel> channels = new ArrayList<>();
        final long startTime = System.currentTimeMillis();
        int sumNum = -1;
        int packets = 0;

        CatalogAgg(String deviceId, String sn) {
            this.deviceId = deviceId;
            this.sn = sn;
        }
    }
}
