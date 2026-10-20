
package com.example.sip.service.zlm;

import com.example.sip.config.MediaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * ZLMediaKit HTTP API 客户端（/index/api/*，secret 鉴权）。
 *
 * <p>封装：
 * <ul>
 *     <li>openRtpServer / openRtpServerMultiplex / closeRtpServer</li>
 *     <li>getMediaList / close_streams</li>
 *     <li>startSendRtp / stopSendRtp</li>
 *     <li>根据 RTP 接收端口发现实际 MediaSource stream</li>
 * </ul>
 *
 * <p>所有调用异常 / code!=0 均返回失败值，不向上抛异常。
 */
@Component
public class ZlmApiClient {

    /** 日志记录器，统一以 [ZLM] 前缀标记所有 ZLM 相关日志。 */
    private static final Logger log = LoggerFactory.getLogger(ZlmApiClient.class);

    /** 媒体服务配置，提供 ZLM 的 apiBase（HTTP 基地址）与 secret（鉴权密钥）。 */
    private final MediaProperties mediaProps;

    /** JSON 解析器，用于解析 ZLM 返回的 JSON 响应体。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 共享的 HTTP 客户端，建立连接超时 3s；
     * JDK 11+ HttpClient 线程安全，整个客户端复用同一实例。
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 构造注入媒体配置。
     *
     * @param mediaProps 媒体服务配置（含 ZLM 的 apiBase / secret）
     */
    public ZlmApiClient(MediaProperties mediaProps) {
        this.mediaProps = mediaProps;
    }

    /**
     * 打开国标 RTP 收流端口。
     *
     * <p>当前 ZLM 模式使用固定 30002 UDP 复用端口。
     *
     * @param streamId RTP Server 的绑定 stream_id
     * @param ssrc     保留该参数兼容现有调用方；Multiplex 模式不使用该参数
     * @return ZLM 分配的 UDP 端口；失败返回 -1
     */
    public int openRtpServer(String streamId, String ssrc) {
        // ssrc 仅为兼容旧签名保留，实际转发到多路复用接口
        return openRtpServerMultiplex(streamId);
    }

    /**
     * 打开固定 30002 的多路复用 RTP Server。
     *
     * <p>Multiplex 模式由 ZLM 根据 RTP SSRC 区分不同 RTP 流。
     */
    public int openRtpServerMultiplex(String streamId) {
        int port = callInt(
                "openRtpServerMultiplex",
                "port", String.valueOf(mediaProps.getZlm().getRtpPort()),  // 固定监听端口（复用模式）
                "stream_id", streamId,     // 业务侧约定的 stream 标识
                "tcp_mode", "0",           // 0=UDP 收流
                "local_ip", "0.0.0.0"      // 监听所有网卡
        );

        log.info(
                "[ZLM] RTP复用服务器: streamId={} port={}",
                streamId,
                port
        );

        return port;
    }

    /**
     * 关闭 RTP 收流端口。
     */
    public boolean closeRtpServer(String streamId) {
        return callBool("closeRtpServer", "stream_id", streamId);
    }

    /**
     * 关闭 ZLM 上指定 MediaSource。
     */
    public boolean closeStreams(String app, String streamId) {
        return callBool(
                "close_streams",
                "app", app,
                "stream", streamId,
                "force", "1"               // 强制关闭，不等待订阅者释放
        );
    }

    /**
     * 让 ZLM 把指定流以 RTP 方式发往目标。
     */
    public boolean startSendRtp(String app, String stream, String dstUrl,
                                int dstPort, String ssrc, int srcPort) {
        return callBool(
                "startSendRtp",
                "vhost", "__defaultVhost__",
                "app", app,
                "stream", stream,
                "ssrc", ssrc,                              // 出流 SSRC，与对端协商一致
                "dst_url", dstUrl,                         // 目标地址
                "dst_port", String.valueOf(dstPort),       // 目标端口
                "is_udp", "1",                             // 1=UDP 发送
                "src_port", String.valueOf(srcPort)        // 本地源端口
        );
    }

    /**
     * 停止 ZLM 的 RTP 发送。
     */
    public boolean stopSendRtp(String app, String stream) {
        return callBool(
                "stopSendRtp",
                "vhost", "__defaultVhost__",
                "app", app,
                "stream", stream
        );
    }

    /**
     * 从 getServerConfig 读取 ZLM HTTP 端口。
     */
    public int getServerConfigHttpPort() {
        // resultKey 传 null：交由 callInt 走 data[].http.port 解析分支
        return callInt("getServerConfig", null, null);
    }

    /**
     * 查询指定 MediaSource。
     */
    public JsonNode getMediaList(String app, String stream) {
        return call(
                "getMediaList",
                "vhost", "__defaultVhost__",
                "app", app,
                "stream", stream
        );
    }

    /**
     * 查询指定 APP 下的全部 MediaSource。
     */
    public JsonNode getMediaList(String app) {
        return call(
                "getMediaList",
                "vhost", "__defaultVhost__",
                "app", app
        );
    }

    /**
     * 判断指定流是否已经存在。
     */
    public boolean hasStream(String app, String stream) {
        JsonNode root = getMediaList(app, stream);

        // 查询失败（异常 / code!=0）一律视为不存在
        if (root == null) {
            return false;
        }

        JsonNode data = root.path("data");
        return data.isArray() && !data.isEmpty();
    }

    /**
     * 根据 ZLM RTP 接收端口查找实际 MediaSource stream。
     *
     * <p>重点：
     * getMediaList 返回的本地 RTP 端口位于：
     *
     * <pre>
     * data[].originSock.local_port
     * </pre>
     *
     * <p>不能只查 data[].port。
     *
     * @param app     ZLM app，例如 rtp
     * @param rtpPort RTP 接收端口
     * @return 实际 MediaSource stream；找不到返回 null
     */
    public String findStreamByRtpPort(String app, int rtpPort) {
        // 非法端口直接短路
        if (rtpPort <= 0) {
            return null;
        }

        JsonNode root = getMediaList(app);

        if (root == null) {
            return null;
        }

        JsonNode data = root.path("data");

        if (!data.isArray() || data.isEmpty()) {
            return null;
        }

        for (JsonNode item : data) {
            String stream = item.path("stream").asText(null);

            // 跳过无 stream 标识的条目
            if (stream == null || stream.isEmpty()) {
                continue;
            }

            // 真实本地 RTP 端口：originSock.local_port
            int localPort = item
                    .path("originSock")
                    .path("local_port")
                    .asInt(-1);

            if (localPort == rtpPort) {
                log.info(
                        "[ZLM] 根据RTP本地端口发现实际流: app={} localPort={} stream={}",
                        app,
                        rtpPort,
                        stream
                );
                return stream;
            }

            // 兼容部分旧版本可能直接返回 port 字段。
            int legacyPort = item.path("port").asInt(-1);

            if (legacyPort == rtpPort) {
                log.info(
                        "[ZLM] 根据兼容port字段发现实际流: app={} port={} stream={}",
                        app,
                        rtpPort,
                        stream
                );
                return stream;
            }
        }

        return null;
    }

    /**
     * 根据 RTP 接收端口查找实际 MediaSource。
     *
     * <p>优先通过 originSock.local_port 找真实 MediaSource。
     * 如果暂时没有找到，再尝试 requestStreamId。
     */
    public String findStreamByRtpPort(String app, int rtpPort, String requestStreamId) {
        // 非法端口直接短路
        if (rtpPort <= 0) {
            return null;
        }

        JsonNode root = getMediaList(app);

        if (root == null) {
            return null;
        }

        JsonNode data = root.path("data");

        if (!data.isArray() || data.isEmpty()) {
            return null;
        }

        // 端口未命中时的兜底：仅按 requestStreamId 命中的 stream
        String fallback = null;

        for (JsonNode item : data) {
            String stream = item.path("stream").asText(null);

            // 跳过无 stream 标识的条目
            if (stream == null || stream.isEmpty()) {
                continue;
            }

            // 真实本地 RTP 端口：originSock.local_port（优先匹配）
            int localPort = item
                    .path("originSock")
                    .path("local_port")
                    .asInt(-1);

            if (localPort == rtpPort) {
                log.info(
                        "[ZLM] RTP本地端口匹配实际流: localPort={} requestStreamId={} actualStream={}",
                        rtpPort,
                        requestStreamId,
                        stream
                );
                return stream;
            }

            // 兼容旧字段。
            int legacyPort = item.path("port").asInt(-1);

            if (legacyPort == rtpPort) {
                log.info(
                        "[ZLM] RTP兼容port字段匹配实际流: port={} requestStreamId={} actualStream={}",
                        rtpPort,
                        requestStreamId,
                        stream
                );
                return stream;
            }

            // 端口都没命中时，记录一次按 requestStreamId 的兜底候选
            if (requestStreamId != null
                    && requestStreamId.equals(stream)) {
                fallback = stream;
            }
        }

        // 全部端口未命中，退化为按请求 streamId 命中
        if (fallback != null) {
            log.info(
                    "[ZLM] 使用 requestStreamId 匹配实际流: requestStreamId={}",
                    fallback
            );
        }

        return fallback;
    }

    /**
     * 查询指定 RTP stream 的 RTP 信息。
     *
     * <p>可用于调试 RTP 是否已经真正进入 ZLM。
     */
    public JsonNode getRtpInfo(String streamId) {
        return call(
                "getRtpInfo",
                "stream_id", streamId
        );
    }

    /**
     * GET 调用 ZLM API。
     *
     * <p>统一拼接 secret 鉴权与查询参数，发送 GET 请求并解析响应；
     * 任何异常或 code!=0 均记录日志并返回 null，不向上抛出。
     *
     * @param api 接口名（不含 /index/api/ 前缀），如 getMediaList
     * @param kv  成对出现的查询参数（key,value,key,value...）；value 为 null 时按空串处理
     * @return 解析后的响应根节点；失败返回 null
     */
    private JsonNode call(String api, String... kv) {
        MediaProperties.Zlm z = mediaProps.getZlm();

        // 基地址 + /index/api/{api} + ?secret=...（secret 需 URL 编码）
        StringBuilder url = new StringBuilder(z.getApiBase())
                .append("/index/api/")
                .append(api)
                .append("?secret=")
                .append(encode(z.getSecret()));

        // 按 (key,value) 成对追加查询参数；key 为 null 表示该组参数跳过
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) {
                continue;
            }

            url.append('&')
                    .append(kv[i])
                    .append('=')
                    .append(kv[i + 1] == null ? "" : encode(kv[i + 1]));
        }

        log.debug("[ZLM] API请求: {}", api);

        try {
            // 单次请求读超时 5s
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(url.toString())
                    )
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(
                    req,
                    HttpResponse.BodyHandlers.ofString()
            );

            JsonNode root = mapper.readTree(resp.body());

            // ZLM 约定 code==0 表示成功，其余视为业务失败
            if (root.path("code").asInt(-1) != 0) {
                log.warn(
                        "[ZLM] {} 调用失败: code={} msg={}",
                        api,
                        root.path("code").asInt(),
                        root.path("msg").asText()
                );
                return null;
            }

            return root;
        } catch (Exception e) {
            // 网络 / 解析等异常统一吞掉，返回 null 由调用方按失败处理
            log.error("[ZLM] {} 调用异常", api, e);
            return null;
        }
    }

    /**
     * 取整型结果。
     *
     * <p>优先读顶层 {@code port} 字段（openRtpServer 类接口直接返回端口）；
     * 否则回退到 {@code data[0].http.port}（getServerConfig 类接口）。
     *
     * @param api 接口名
     * @param kv  成对查询参数
     * @return 解析到的端口值；失败返回 -1
     */
    private int callInt(String api, String... kv) {
        JsonNode root = call(api, kv);

        if (root == null) {
            return -1;
        }

        // 分支一：响应顶层直接带 port（RTP Server 分配端口）
        JsonNode p = root.path("port");

        if (p.isNumber()) {
            return p.asInt();
        }

        // 分支二：从 data 数组首项的 http.port 读取（如 getServerConfig）
        JsonNode d = root.path("data");

        if (d.isArray() && !d.isEmpty()) {
            return d.get(0)
                    .path("http")
                    .path("port")
                    .asInt(-1);
        }

        return -1;
    }

    /**
     * 取布尔结果。
     *
     * <p>兼容 ZLM 多种返回形态：布尔 result、字符串 result（hit/true）、
     * 以及仅以 code==0 表示成功的接口。
     *
     * @param api 接口名
     * @param kv  成对查询参数
     * @return 调用是否成功
     */
    private boolean callBool(String api, String... kv) {
        JsonNode root = call(api, kv);

        if (root == null) {
            return false;
        }

        JsonNode r = root.path("result");

        // 形态一：result 为布尔值
        if (r.isBoolean()) {
            return r.asBoolean();
        }

        // 形态二：result 为字符串（如 close_streams 返回 "hit"）
        if (r.isTextual()) {
            return "hit".equalsIgnoreCase(r.asText())
                    || "true".equalsIgnoreCase(r.asText());
        }

        // 形态三：无 result 字段，退化为按 code==0 判定成功
        return root.path("code").asInt(-1) == 0;
    }

    /**
     * 对查询参数做 URL 编码（UTF-8）。
     *
     * @param value 原始值；为 null 时返回空串
     * @return 编码后的字符串
     */
    private String encode(String value) {
        if (value == null) {
            return "";
        }

        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}