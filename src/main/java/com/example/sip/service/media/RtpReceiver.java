package com.example.sip.service.media;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * RTP 媒体接收器（多端口模式）。
 *
 * <p>每个媒体会话独占一个本地 UDP 端口：register() 时从 {@link RtpPortPool}
 * 分配端口并绑定独立 DatagramSocket，启动独立接收线程。
 * SIP INVITE SDP 通告该端口，设备直接推流到会话端口，
 * 从根上消除单端口共享下的 SSRC 冲突与相互干扰。
 *
 * <p>职责：接收 RTP/PS 流 → 解析 RTP 固定头统计 → 将载荷实时转发给
 * 下游（ffmpeg 转码 / FLV 广播），慢下游丢帧但不阻塞接收。
 */
@Service
public class RtpReceiver {

    private static final Logger log = LoggerFactory.getLogger(RtpReceiver.class);

    /** 每个下游队列的缓冲块数（约 700KB）；直播场景下游慢时丢弃最旧数据，绝不阻塞 RTP 接收 */
    private static final int SINK_QUEUE = 512;

    private final RtpPortPool portPool;

    /** callId -> 路由（统计 + 下游转发 + 独立 socket） */
    private final Map<String, Session> routes = new ConcurrentHashMap<>();

    public RtpReceiver(RtpPortPool portPool) {
        this.portPool = portPool;
        log.info("[28181] 多端口RTP接收器初始化: 端口池容量={}", portPool.capacity());
    }

    /**
     * 为会话分配端口并登记媒体路由（发送 INVITE 前调用）。
     *
     * <p>绑定端口失败时自动换池中下一个端口重试；池耗尽返回 -1。
     *
     * @return 实际分配的本地 RTP 端口；池耗尽或全部绑定失败返回 -1
     */
    public int register(String callId, String deviceId, String channelId,
                        String deviceHost, int devicePort, String ssrc) {
        return register(callId, deviceId, channelId, deviceHost, devicePort, ssrc, -1);
    }

    /**
     * 登记媒体路由并绑定指定端口（preferredPort &gt;= 0，须先从 {@link #acquirePort()} 获取）。
     *
     * <p>preferredPort &lt; 0 时从池中自动选取端口。
     *
     * @return 实际绑定的本地 RTP 端口；失败返回 -1
     */
    public int register(String callId, String deviceId, String channelId,
                        String deviceHost, int devicePort, String ssrc,
                        int preferredPort) {

        Session old = routes.remove(callId);
        if (old != null) {
            releaseSession(old);
        }

        DatagramSocket socket;
        int port;

        if (preferredPort >= 0) {
            port = preferredPort;
            try {
                socket = new DatagramSocket(port);
            } catch (SocketException e) {
                log.warn("[28181] 指定端口绑定失败: callId={} port={} err={}",
                        callId, port, e.getMessage());
                portPool.release(port);
                return -1;
            }
        } else {
            // 池耗尽快速失败
            if (portPool.available() <= 0) {
                log.error("[28181] RTP端口池耗尽，拒绝新媒体会话: callId={} capacity={}",
                        callId, portPool.capacity());
                return -1;
            }

            int attempts = 0;
            socket = null;
            port = -1;
            while (attempts++ < 8) {
                int p = portPool.acquire();
                if (p < 0) {
                    log.error("[28181] RTP端口池耗尽: callId={}", callId);
                    return -1;
                }
                try {
                    socket = new DatagramSocket(p);
                    port = p;
                    break;
                } catch (SocketException e) {
                    log.warn("[28181] 端口绑定失败，换端口重试: callId={} port={} err={}",
                            callId, p, e.getMessage());
                    portPool.release(p);
                }
            }
            if (socket == null) {
                log.error("[28181] 多次端口绑定失败，放弃会话: callId={}", callId);
                return -1;
            }
        }

        Session r = new Session(callId, deviceId, channelId,
                deviceHost, devicePort, port, socket);
        try {
            r.ssrc = ssrc == null ? -1L : Long.parseLong(ssrc.trim());
        } catch (Exception ignore) {
            r.ssrc = -1L;
        }

        routes.put(callId, r);

        final DatagramSocket bound = socket;
        Thread t = new Thread(() -> run(bound, r),
                "rtp-recv-" + callId.substring(Math.max(0, callId.length() - 6)));
        t.setDaemon(true);
        t.start();

        log.info("[28181] RTP会话端口已分配: callId={} device={} channel={} ssrc={} port={}",
                callId, deviceId, channelId, ssrc, port);
        return port;
    }

    /**
     * 仅从端口池预分配一个端口（不绑定 socket）。
     *
     * <p>用于 StreamSession.localPort 为 final 的场景：
     * 先 acquirePort() 取得端口 → 构造会话 → register(..., preferredPort) 绑定。
     *
     * @return 端口；池耗尽返回 -1
     */
    public int acquirePort() {
        return portPool.acquireRotational();
    }

    /**
     * RTP 收流主循环（每会话独立线程）：解析 RTP 头统计，转发载荷给下游。
     */
    private void run(DatagramSocket socket, Session r) {
        byte[] buf = new byte[65536];
        while (!socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                dispatch(p, r);
            } catch (Exception e) {
                if (!socket.isClosed()) {
                    log.warn("[28181] RTP接收异常: callId={} port={} error={}",
                            r.callId, r.port, e.getMessage());
                }
                break;
            }
        }
        log.info("[28181] RTP接收线程退出: callId={} port={} packets={}",
                r.callId, r.port, r.packets);
    }

    /**
     * 分发单个 RTP 包：本 socket 专属于单一会话，直接归属；
     * 保留 SSRC / 源地址日志用于诊断。
     */
    private void dispatch(DatagramPacket p, Session r) {
        if (p.getLength() < 12) {
            return;
        }

        byte[] d = p.getData();
        int off = p.getOffset();
        long ssrc = ((long) (d[off + 8] & 0xFF) << 24)
                | ((long) (d[off + 9] & 0xFF) << 16)
                | ((long) (d[off + 10] & 0xFF) << 8)
                | (d[off + 11] & 0xFF);

        // 学习设备实际源地址（诊断用）
        if (r.srcAddr == null) {
            synchronized (this) {
                if (r.srcAddr == null) {
                    r.srcAddr = (InetSocketAddress) p.getSocketAddress();
                    log.info("[28181] RTP源地址已登记: callId={} src={} ssrc={}",
                            r.callId, r.srcAddr, ssrc);
                }
            }
        }

        if (r.packets == 0) {
            log.info("[28181] 收到RTP首包: callId={} src={} len={} pt={} ssrc={} port={}",
                    r.callId, p.getSocketAddress(), p.getLength(),
                    d[off + 1] & 0x7F, ssrc, r.port);
        }

        r.packets++;
        r.bytes += p.getLength();
        r.lastPacketAt = System.currentTimeMillis();

        int[] h = parseRtpHeader(p, r);
        forward(p, h, r);
    }

    /**
     * 解析 RTP 固定头（12 字节 + CSRC + 可能的扩展头），返回 [载荷偏移, 载荷长度]；
     * 包长不足 12 或扩展头越界时返回 null（调用方 forward 判空丢弃）。
     */
    private int[] parseRtpHeader(DatagramPacket p, Session r) {
        byte[] d = p.getData();
        int off = p.getOffset();
        int len = p.getLength();

        if (len < 12) {
            return null;
        }

        r.version = (d[off] >> 6) & 0x03;
        r.payloadType = d[off + 1] & 0x7F;

        int csrc = d[off] & 0x0F;
        int x = (d[off] >> 4) & 0x01;
        int hdrLen = 12 + csrc * 4;

        if (x == 1 && len >= hdrLen + 4) {
            int extLen = ((d[off + hdrLen + 2] & 0xFF) << 8)
                    | (d[off + hdrLen + 3] & 0xFF);
            hdrLen += 4 + extLen * 4;
        }

        int seq = ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
        long ts = ((long) (d[off + 4] & 0xFF) << 24)
                | ((long) (d[off + 5] & 0xFF) << 16)
                | ((long) (d[off + 6] & 0xFF) << 8)
                | (d[off + 7] & 0xFF);
        long ssrc = ((long) (d[off + 8] & 0xFF) << 24)
                | ((long) (d[off + 9] & 0xFF) << 16)
                | ((long) (d[off + 10] & 0xFF) << 8)
                | (d[off + 11] & 0xFF);

        // 16 位序列号做差并屏蔽高位，正确处理回绕；乱序（差值过大）忽略
        if (r.lastSeq >= 0) {
            int delta = (seq - r.lastSeq) & 0xFFFF;
            if (delta > 1 && delta < 32768) {
                r.lostPackets += delta - 1;
            }
        }

        r.lastSeq = seq;
        r.lastTimestamp = ts;
        r.rtpSsrc = ssrc;

        if (hdrLen > len) {
            return null;
        }

        return new int[]{off + hdrLen, len - hdrLen};
    }

    /** 将 RTP 载荷广播写入该路由所有下游（ffmpeg stdin），慢下游丢帧但不阻塞接收 */
    private void forward(DatagramPacket p, int[] h, Session r) {
        if (h == null) {
            return;
        }

        for (SinkWriter w : r.sinks) {
            if (w.failed) {
                r.sinks.remove(w);
                continue;
            }
            w.write(p.getData(), h[0], h[1]);
        }
    }

    /** 挂接一个媒体转发下游（如 ffmpeg 进程 stdin），支持多个下游同时消费 */
    public void attachSink(String callId, OutputStream out) {
        Session r = routes.get(callId);
        if (r != null) {
            r.sinks.add(new SinkWriter(callId, out));
            log.info("[28181] 媒体转发已挂接: callId={} sinks={}", callId, r.sinks.size());
        } else {
            log.warn("[28181] 媒体转发挂接失败，路由不存在: callId={}", callId);
        }
    }

    /** 断开指定媒体转发下游（关闭流，通知该 ffmpeg EOF） */
    public void detachSink(String callId, OutputStream out) {
        Session r = routes.get(callId);
        if (r != null) {
            for (SinkWriter w : r.sinks) {
                if (w.out == out) {
                    r.sinks.remove(w);
                    w.close();
                    break;
                }
            }
        }
    }

    /** 断开全部媒体转发下游（释放会话时调用） */
    public void detachSink(String callId) {
        Session r = routes.get(callId);
        if (r != null) {
            for (SinkWriter w : r.sinks) {
                w.close();
            }
            r.sinks.clear();
        }
    }

    /**
     * 该会话最近一个 RTP 包的接收时间（epoch millis）。
     *
     * <p>供媒体流看门狗判断设备是否还在推流；无会话或尚未收到包返回 0。
     */
    public long lastPacketAt(String callId) {
        Session r = routes.get(callId);
        return r == null ? 0 : r.lastPacketAt;
    }

    /** 该会话累计 RTP 包数（无会话返回 0） */
    public long packetCount(String callId) {
        Session r = routes.get(callId);
        return r == null ? 0 : r.packets;
    }

    /**
     * 停止指定会话的 RTP 路由：关闭会话 socket → 释放端口 → 断开下游 → 移除路由。
     */
    public void stop(String callId) {
        Session r = routes.get(callId);
        if (r == null) {
            log.warn("[28181] RTP路由不存在: callId={}", callId);
            return;
        }

        log.info("[28181] 开始释放RTP路由: callId={} port={}", callId, r.port);

        detachSink(callId);
        log.info("[28181] RTP下游已释放: callId={}", callId);

        releaseSession(r);
        routes.remove(callId, r);

        log.info("[28181] RTP路由已释放: callId={} packets={} bytes={}",
                callId, r.packets, r.bytes);
    }

    /** 关闭会话 socket 并归还端口到池 */
    private void releaseSession(Session r) {
        DatagramSocket s = r.socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
        portPool.release(r.port);
    }

    /** 全部路由快照（HTTP 查询用），key 为 callId */
    public Map<String, Session> sessions() {
        return routes;
    }

    /** 当前端口池剩余可用端口数 */
    public int availablePorts() {
        return portPool.available();
    }

    /** 端口池容量（最大并发媒体会话数） */
    public int portCapacity() {
        return portPool.capacity();
    }

    /** 平台关闭时关闭全部会话 socket */
    @PreDestroy
    public void shutdown() {
        for (Session r : routes.values()) {
            DatagramSocket s = r.socket;
            if (s != null && !s.isClosed()) {
                s.close();
            }
            portPool.release(r.port);
        }
        routes.clear();
        log.info("[28181] 多端口媒体接收器已关闭");
    }

    /** 媒体路由统计（一个 callId 一路，独占一个本地端口） */
    public static class Session {
        /** 会话唯一标识（SIP Call-ID），一个 callId 对应一路媒体 */
        final String callId;
        /** 设备编号（国标 20 位设备编码） */
        final String deviceId;
        /** 通道编号（国标通道编码） */
        final String channelId;
        /** 设备信令侧 IP（登记时传入，用于日志/诊断） */
        final String deviceHost;
        /** 设备信令侧端口（登记时传入，用于日志/诊断） */
        final int devicePort;
        /** 本会话独占的本地 RTP 端口 */
        final int port;
        /** 本会话独占的接收 socket */
        final DatagramSocket socket;
        /** 登记时协商的 SSRC（SDP y= 字段） */
        volatile long ssrc = -1;
        /** 设备实际 RTP 源地址（收到首个包时学习） */
        volatile InetSocketAddress srcAddr;

        /** 已接收 RTP 包计数 */
        volatile long packets = 0;
        /** 已接收字节计数 */
        volatile long bytes = 0;
        /** 最近一个 RTP 包的接收时间（epoch millis）；未收到包为 0，供空闲看门狗使用 */
        volatile long lastPacketAt = 0;
        /** 路由登记（创建）时间戳，毫秒 */
        final long startTime = System.currentTimeMillis();

        /** 最近一包 RTP 版本号（来自固定头） */
        volatile int version = -1;
        /** 最近一包 RTP 负载类型 PT（96 等动态类型） */
        volatile int payloadType = -1;
        /** 最近一包 RTP 序列号（用于丢包估算） */
        volatile int lastSeq = -1;
        /** 最近一包 RTP 时间戳 */
        volatile long lastTimestamp = 0;
        /** RTP 头中实际携带的 SSRC */
        volatile long rtpSsrc = 0;
        /** 估算累计丢包数（基于序列号差值累加） */
        volatile long lostPackets = 0;

        /** 下游消费器列表（ffmpeg stdin），收到 RTP 载荷时广播写入 */
        volatile List<SinkWriter> sinks = new CopyOnWriteArrayList<>();

        Session(String callId, String deviceId, String channelId,
                String deviceHost, int devicePort, int port, DatagramSocket socket) {
            this.callId = callId;
            this.deviceId = deviceId;
            this.channelId = channelId;
            this.deviceHost = deviceHost;
            this.devicePort = devicePort;
            this.port = port;
            this.socket = socket;
        }

        public String getCallId() {
            return callId;
        }

        public String getDeviceId() {
            return deviceId;
        }

        public String getChannelId() {
            return channelId;
        }

        public String getDeviceHost() {
            return deviceHost;
        }

        public int getDevicePort() {
            return devicePort;
        }

        public int getPort() {
            return port;
        }

        public String getSrcAddr() {
            return srcAddr == null ? "" : srcAddr.getAddress().getHostAddress() + ":" + srcAddr.getPort();
        }

        public long getPackets() {
            return packets;
        }

        public long getBytes() {
            return bytes;
        }

        public long getLastPacketAt() {
            return lastPacketAt;
        }

        public long getStartTime() {
            return startTime;
        }

        public int getVersion() {
            return version;
        }

        public int getPayloadType() {
            return payloadType;
        }

        public int getLastSeq() {
            return lastSeq;
        }

        public long getLastTimestamp() {
            return lastTimestamp;
        }

        public long getSsrc() {
            return rtpSsrc != 0 ? rtpSsrc : ssrc;
        }

        public long getLostPackets() {
            return lostPackets;
        }
    }

    /**
     * 异步媒体下游：有界队列 + 独立写线程。
     * RTP 接收线程只做非阻塞入队，写线程负责写 ffmpeg stdin；
     * 下游消费慢时丢弃最旧数据，但绝不影响 RTP 接收（避免 UDP 丢包）。
     */
    static class SinkWriter {
        /** 下游字节流（通常为 ffmpeg 进程 stdin） */
        final OutputStream out;
        /** 有界缓冲队列；满时由接收线程丢最旧，保证不阻塞收包 */
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(SINK_QUEUE);
        /** 独立写线程，负责把队列数据落到下游 stdin */
        private final Thread thread;
        /** 主动关闭标志：close() 置位后写线程退出 */
        private volatile boolean closed = false;
        /** 写下游发生 IO 异常时置位，供 forward 摘除该下游 */
        volatile boolean failed = false;

        /** 创建下游并启动守护写线程；线程名取 callId 末 8 位便于排查 */
        SinkWriter(String callId, OutputStream out) {
            this.out = out;
            String name = callId.length() > 8
                    ? callId.substring(callId.length() - 8)
                    : callId;
            this.thread = new Thread(this::run, "sink-write-" + name);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        /** RTP 接收线程调用：非阻塞入队，满则丢最旧 */
        void write(byte[] data, int off, int len) {
            if (closed) {
                return;
            }

            byte[] copy = Arrays.copyOfRange(data, off, off + len);
            if (!queue.offer(copy)) {
                queue.poll();
                queue.offer(copy);
            }
        }

        /** 写线程主循环：从队列取数据写下游；中断即退出，IO 异常置 failed，最终关闭 out */
        private void run() {
            try {
                while (!closed) {
                    byte[] d = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (d == null) {
                        continue;
                    }
                    out.write(d);
                }
            } catch (InterruptedException ignored) {
                // 关闭
            } catch (IOException e) {
                if (!closed) {
                    failed = true;
                }
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            queue.clear();
            thread.interrupt();
            // 直接关流兜底：写线程可能阻塞在 out.write()（下游管道满），
            // interrupt 无法唤醒阻塞写；stdin 不关 ffmpeg 就收不到 EOF，
            // 不会 flush/写 moov 收尾，下载文件将无法播放。
            // 与写线程 finally 的 close 重复执行无害（幂等，异常吞掉）
            try {
                out.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
