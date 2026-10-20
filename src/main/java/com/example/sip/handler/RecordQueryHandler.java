package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Device;
import com.example.sip.model.RecordInfo;
import com.example.sip.model.RecordInfoQuery;
import com.example.sip.model.SipMessage;
import com.example.sip.parser.RecordInfoParser;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.sip.SipRequestBuilder;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 录像查询处理器（RecordInfo）。
 *
 * <p>平台发送 MANSCDP RecordInfo 查询 MESSAGE，设备可能分多包返回
 * （SumNum 为总量，本包 Item 为分片）；本处理器按 SN 聚合，收齐
 * SumNum 条即完成，超时返回已收到的部分结果。
 */
@Component
public class RecordQueryHandler {

    private static final Logger log = LoggerFactory.getLogger(RecordQueryHandler.class);

    /** 单次查询等待超时（毫秒） */
    private static final long RECORD_QUERY_TIMEOUT_MILLIS = 8000;

    /** 分包保护上限（防止异常设备无限分包） */
    private static final int MAX_PACKETS = 200;

    private static final DateTimeFormatter SIP_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SipServerProperties props;
    private final DeviceRegistry registry;
    private final SipChannelHolder channelHolder;

    /** CSeq 计数器（查询事务） */
    private final AtomicLong cseq = new AtomicLong(1);

    /** SN -> 查询上下文（分包聚合） */
    private final Map<String, RecordQueryContext> recordQueries = new ConcurrentHashMap<>();

    public RecordQueryHandler(SipServerProperties props,
                              DeviceRegistry registry,
                              SipChannelHolder channelHolder) {
        this.props = props;
        this.registry = registry;
        this.channelHolder = channelHolder;
    }

    /** 单次录像查询的聚合上下文 */
    private static class RecordQueryContext {
        String deviceId;
        String channelId;
        final List<RecordInfo> records = new ArrayList<>();
        final AtomicInteger receivedPackets = new AtomicInteger(0);
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        volatile int sumNum = -1;
    }

    // =========================================================
    // 对外 API（Web 层调用）
    // =========================================================

    /**
     * 查询设备录像。
     *
     * @param deviceId  设备编号
     * @param channelId 通道编号
     * @param startTime 开始时间（epoch 秒）
     * @param endTime   结束时间（epoch 秒）
     * @return hasRecords / recordCount / sumNum / records / partial
     */
    public Map<String, Object> queryRecords(String deviceId,
                                            String channelId,
                                            long startTime,
                                            long endTime) {
        Device device = registry.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在或不在线: " + deviceId);
        }

        Channel channel = channelHolder.get();
        if (channel == null) {
            throw new IllegalStateException("SIP UDP Channel 尚未初始化");
        }

        String sn = String.valueOf(cseq.incrementAndGet());
        RecordQueryContext ctx = new RecordQueryContext();
        ctx.deviceId = deviceId;
        ctx.channelId = channelId;
        recordQueries.put(sn, ctx);

        String xml = buildRecordQueryXml(sn, channelId, startTime, endTime);
        byte[] packet = SipRequestBuilder.message(
                props, device.getHost(), device.getPort(), channelId,
                UUID.randomUUID().toString(), sn, xml);

        send(channel, new InetSocketAddress(device.getHost(), device.getPort()), packet);

        log.info("[28181] >>> 发送录像查询 RecordInfo: device={} channel={} sn={} 区间=[{}, {}]",
                deviceId, channelId, sn, startTime, endTime);

        try {
            return ctx.future.get(RECORD_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return partialResult(ctx, true);
        } finally {
            recordQueries.remove(sn);
        }
    }

    // =========================================================
    // RecordInfo 应答处理（由 DeviceMessageHandler 路由进来）
    // =========================================================

    /** 处理设备 RecordInfo 应答（可能分多包，按 SN 聚合） */
    public void handleResponse(SipMessage msg, org.w3c.dom.Element root) {
        byte[] body = msg.getBodyBytes();
        if (body == null || body.length == 0) {
            return;
        }

        RecordInfoQuery result = RecordInfoParser.parse(body);
        String sn = result.getSn();
        String deviceId = result.getDeviceId();

        RecordQueryContext ctx = sn == null ? null : recordQueries.get(sn);
        if (ctx == null) {
            log.warn("[28181] RecordInfo 响应无匹配查询（可能已超时清理）: sn={} device={}",
                    sn, deviceId);
            return;
        }

        if (!result.isSuccess()) {
            log.warn("[28181] RecordInfo 查询失败: device={} sn={} reason={}",
                    deviceId, sn, result.getReason());
            ctx.sumNum = 0;
            ctx.future.complete(partialResult(ctx, false));
            return;
        }

        int packets;
        boolean done;

        synchronized (ctx) {
            if (result.getSumNum() >= 0) {
                ctx.sumNum = result.getSumNum();
            }
            ctx.records.addAll(result.getRecords());
            packets = ctx.receivedPackets.incrementAndGet();

            log.info("[28181] RecordInfo 分包: sn={} sumNum={} 本包 {} 条, 累计 {} 条 (第 {} 包)",
                    sn, ctx.sumNum, result.getRecords().size(), ctx.records.size(), packets);

            done = ctx.sumNum == 0
                    || (ctx.sumNum > 0 && ctx.records.size() >= ctx.sumNum)
                    || packets > MAX_PACKETS;
        }

        for (RecordInfo record : result.getRecords()) {
            log.info("[28181] 录像: device={} {} ~ {} path={} type={}",
                    record.getDeviceId(), record.getStartTime(), record.getEndTime(),
                    record.getFilePath(), record.getType());
        }

        if (done) {
            ctx.future.complete(partialResult(ctx, false));
        }
    }

    // =========================================================
    // 工具
    // =========================================================

    private Map<String, Object> partialResult(RecordQueryContext ctx, boolean timeout) {
        Map<String, Object> result = new HashMap<>();

        List<RecordInfo> snapshot;
        int sumNum;
        synchronized (ctx) {
            sumNum = ctx.sumNum;
            snapshot = new ArrayList<>(ctx.records);
        }

        result.put("deviceId", ctx.deviceId);
        result.put("channelId", ctx.channelId);
        result.put("hasRecords", !snapshot.isEmpty());
        result.put("recordCount", snapshot.size());
        result.put("sumNum", sumNum);
        result.put("records", snapshot);
        if (timeout) {
            result.put("partial", true);
        }
        return result;
    }

    private static String buildRecordQueryXml(String sn,
                                              String channelId,
                                              long startTime,
                                              long endTime) {
        String start = SIP_TIME.format(Instant.ofEpochSecond(startTime));
        String end = SIP_TIME.format(Instant.ofEpochSecond(endTime));

        return "<?xml version=\"1.0\" encoding=\"GB18030\"?>\r\n"
                + "<Query>\r\n"
                + "    <CmdType>RecordInfo</CmdType>\r\n"
                + "    <SN>" + sn + "</SN>\r\n"
                + "    <DeviceID>" + channelId + "</DeviceID>\r\n"
                + "    <StartTime>" + start + "</StartTime>\r\n"
                + "    <EndTime>" + end + "</EndTime>\r\n"
                + "    <Type>all</Type>\r\n"
                + "</Query>\r\n";
    }

    private static void send(Channel channel, InetSocketAddress target, byte[] packet) {
        SipResponder.send(channel, target, packet);
    }
}
