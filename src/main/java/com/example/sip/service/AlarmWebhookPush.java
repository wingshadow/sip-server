package com.example.sip.service;

import com.example.sip.config.SipServerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 告警 HTTP 外发（Webhook）。
 *
 * <p>把设备告警（MANSCDP Alarm MESSAGE 与 NOTIFY 订阅事件两条来源）
 * 以 JSON POST 推送到第三方系统（运维平台/短信网关/工单系统）。
 * 默认关闭，启用后配置 {@code sip.server.alarm-webhook-url} 即生效。
 *
 * <h3>关键约束：SIP 信令线程永不被阻塞</h3>
 * <ul>
 *   <li>异步入队 + 单线程消费，信令路径只做 {@code offer}（微秒级）；</li>
 *   <li>队列有界（2048），满时<b>丢弃最旧并告警</b>——告警洪泛时
 *       保 SIP 信令，不保投递完整性；</li>
 *   <li>JDK HttpClient 异步发送，连接/读取超时均可配置；</li>
 *   <li>任何异常只记日志，绝不向上抛（调用方是 SIP 处理线程）。</li>
 * </ul>
 */
@Component
public class AlarmWebhookPush {

    private static final Logger log = LoggerFactory.getLogger(AlarmWebhookPush.class);

    /** 队列容量：满则丢弃最旧（告警洪泛时保信令优先） */
    private static final int QUEUE_CAPACITY = 2048;

    private final SipServerProperties props;
    private final ObjectMapper objectMapper;
    private final ExecutorService worker;

    public AlarmWebhookPush(SipServerProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread t = new Thread(runnable, "alarm-webhook-push");
                    t.setDaemon(true);
                    return t;
                },
                (runnable, pool) -> {
                    // 队列满：丢最旧任务，保 SIP 信令线程不被拖慢
                    pool.getQueue().poll();
                    try {
                        pool.getQueue().put(runnable);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    log.warn("[28181] 告警推送队列已满({})，丢弃最旧事件", QUEUE_CAPACITY);
                });
        this.worker = executor;
    }

    /**
     * 推送一条告警（异步入队，立即返回）。
     *
     * @param deviceId 设备/通道国标编号
     * @param source   来源：{@code message}（MANSCDP MESSAGE 上报）/ {@code subscribe}（NOTIFY 订阅事件）
     * @param fields   告警字段（priority/method/time/description/type 等，值需为非 null）
     */
    public void push(String deviceId, String source, Map<String, String> fields) {
        if (!props.isAlarmWebhookEnabled()
                || props.getAlarmWebhookUrl() == null
                || props.getAlarmWebhookUrl().isBlank()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", deviceId);
        payload.put("source", source);
        payload.putAll(fields);

        worker.execute(() -> doPost(payload));
    }

    private void doPost(Map<String, Object> payload) {
        String url = props.getAlarmWebhookUrl();
        try {
            String body = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(props.getAlarmWebhookTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(props.getAlarmWebhookTimeoutMs()))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            log.warn("[28181] 告警推送被拒: url={} status={} device={}",
                                    url, response.statusCode(), payload.get("deviceId"));
                        } else {
                            log.debug("[28181] 告警推送成功: url={} device={}",
                                    url, payload.get("deviceId"));
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("[28181] 告警推送失败: url={} device={} error={}",
                                url, payload.get("deviceId"), ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            // 序列化/构造请求失败：只记日志，绝不影响 SIP 线程
            log.warn("[28181] 告警推送异常: url={} device={} error={}",
                    url, payload.get("deviceId"), e.getMessage());
        }
    }
}
