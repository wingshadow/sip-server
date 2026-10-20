package com.example.sip.service.voice;

import com.example.sip.config.VoiceProperties;
import com.example.sip.model.VoiceSession;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音 RTP 端点（多端口模式）。
 *
 * <p>每个语音会话独占一个本地 UDP 端口（从 {@link com.example.sip.service.media.RtpPortPool}
 * 语音池分配）：会话启动时绑定独立 DatagramSocket 并启动独立接收线程，
 * SIP SDP（answer / INVITE）通告该端口，设备直接推流到会话端口。
 *
 * <p>相比旧版全局固定端口（30001）模式：
 * <ul>
 *   <li>支持多台设备同时语音对讲；</li>
 *   <li>会话间无端口冲突与相互干扰；</li>
 *   <li>会话停止即释放端口，端口可回收复用。</li>
 * </ul>
 */
@Component
public class VoiceRtpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(VoiceRtpEndpoint.class);

    /** 语音端口池（sip.voice.port-pool-start ~ end） */
    private final com.example.sip.service.media.RtpPortPool portPool;

    /** 会话键（deviceId:channelId）→ 会话 Socket */
    private final Map<String, SessionSocket> sockets = new ConcurrentHashMap<>();

    /** RTP 接收监听器（VoiceService 注册） */
    private volatile AudioListener listener;

    private final boolean enabled;

    public VoiceRtpEndpoint(VoiceProperties voiceProps) {
        this.enabled = voiceProps.isEnabled();
        this.portPool = new com.example.sip.service.media.RtpPortPool(
                "voice", voiceProps.getPortPoolStart(), voiceProps.getPortPoolEnd());
        log.info("[VOICE] 语音RTP端点初始化(多端口): enabled={} 端口池=[{}..{}] 容量={}",
                enabled, voiceProps.getPortPoolStart(), voiceProps.getPortPoolEnd(),
                portPool.capacity());
    }

    /** 从语音端口池预分配一个端口（构造 VoiceSession 前调用）。 */
    public int acquirePort() {
        return portPool.acquireRotational();
    }

    /** 归还端口（未绑定 socket 的失败场景）。 */
    public void releasePort(int port) {
        portPool.release(port);
    }

    /**
     * 为会话绑定 RTP Socket（端口已在构造 VoiceSession 时分配）。
     *
     * @return true=绑定成功；false=端口占用/绑定失败（端口已归还）
     */
    public boolean bind(VoiceSession s) {
        if (!enabled) {
            return false;
        }

        String key = s.getKey();
        SessionSocket old = sockets.get(key);
        if (old != null) {
            release(s);
        }

        int port = s.getLocalPort();
        DatagramSocket socket;
        try {
            socket = new DatagramSocket(port);
        } catch (SocketException e) {
            log.warn("[VOICE] 端口绑定失败，归还端口: key={} port={} err={}",
                    key, port, e.getMessage());
            portPool.release(port);
            return false;
        }

        SessionSocket ss = new SessionSocket(s, socket);
        sockets.put(key, ss);

        Thread t = new Thread(() -> receiveLoop(ss), "voice-rtp-" + key);
        t.setDaemon(true);
        t.start();

        // TCP 媒体监听（与 UDP 同一端口号，协议栈不同互不冲突）：
        // 对讲/广播 SDP 通告 TCP/RTP/AVP + a=setup:passive，设备按 RFC 4145
        // 应答 a=setup:active 后主动连接本端口，RTP 走该 TCP 连接收发。
        // 监听失败不阻断会话——设备 200 OK 若应答 UDP，仍走上面的 UDP 通道。
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            ss.serverSocket = server;
            Thread at = new Thread(() -> acceptLoop(ss), "voice-rtp-tcp-" + key);
            at.setDaemon(true);
            at.start();
            log.info("[VOICE] TCP媒体监听已启动: key={} port={}", key, port);
        } catch (IOException e) {
            log.warn("[VOICE] TCP媒体监听失败(继续等待UDP回退): key={} port={} err={}",
                    key, port, e.getMessage());
        }

        log.info("[VOICE] 会话RTP端口已绑定: key={} port={}", key, port);
        return true;
    }

    /**
     * 通过会话 Socket 发送 RTP。
     *
     * <p><b>目标地址必须是协商/学习得到的设备媒体地址（audioIp:audioPort）</b>：
     * <ul>
     *   <li>平台主动 INVITE：取自设备 200 OK SDP（媒体级 c= 优先，会话级回退）+ m=audio 端口；</li>
     *   <li>设备主动 INVITE：取自设备 INVITE SDP 同规则解析；</li>
     *   <li>兜底：设备 RTP 到达时从其源地址学习。</li>
     * </ul>
     * 严禁把设备 SIP 地址（deviceHost:devicePort，如 192.168.8.65:5060）当作 RTP 目标——
     * 设备 SIP 端口不接收媒体流，发过去即"无声"。
     *
     * @return true=已发送；false=尚未协商到有效 RTP 目标，本次跳过（不盲发）
     */
    public boolean send(VoiceSession s, byte[] rtpPacket) throws IOException {
        SessionSocket ss = sockets.get(s.getKey());
        if (ss == null || ss.socket.isClosed()) {
            throw new IOException("语音会话 Socket 不存在或已关闭: " + s.getKey());
        }

        // TCP 传输：设备 200 OK 应答 TCP/RTP/AVP 时，RTP 走设备主动建立的 TCP 连接，
        // 按 RFC 4571 成帧（$ + 1字节通道号 + 2字节大端长度 + RTP 包）。
        // 设备应答可能含多条 m=audio 且均带 a=connection:new（RFC 4145 语义：
        // 每条 m-line 一条独立连接），设备会为"接收平台音频"单独建连。
        // 因此下行帧写到该会话的所有活跃 TCP 连接，由设备自行取用对应连接。
        if (s.isTcp()) {
            if (ss.tcpConns.isEmpty()) {
                // 设备尚未连入（ACK 后才会主动连接）：每会话只警告一次
                if (ss.warnedNoTcpConn.compareAndSet(false, true)) {
                    log.warn("[VOICE] TCP媒体连接尚未建立(等待设备连入): key={} port={}",
                            s.getKey(), s.getLocalPort());
                }
                return false;
            }
            int len = rtpPacket.length;
            byte[] framed = new byte[4 + len];
            framed[0] = '$';
            framed[1] = 0;                                   // 通道号 0
            framed[2] = (byte) ((len >> 8) & 0xFF);
            framed[3] = (byte) (len & 0xFF);
            System.arraycopy(rtpPacket, 0, framed, 4, len);

            boolean any = false;
            for (TcpConn c : ss.tcpConns) {
                synchronized (c.socket) {
                    try {
                        c.out.write(framed);
                        c.out.flush();
                        any = true;
                    } catch (IOException e) {
                        // 连接已失效：从列表摘除，后续由 finally/读取线程清理
                        ss.tcpConns.remove(c);
                        log.warn("[VOICE] TCP媒体连接写出失败(摘除): key={} peer={} err={}",
                                s.getKey(), c.socket.getRemoteSocketAddress(), e.getMessage());
                    }
                }
            }
            if (any && ss.loggedFirstSend.compareAndSet(false, true)) {
                log.info("[VOICE] RTP实际发送目标(TCP): key={} conns={} localPort={}",
                        s.getKey(), ss.tcpConns.size(), ss.port);
            }
            return any;
        }

        if (!s.isRtpTargetValid()) {
            // 限流警告：地址协商成功前每会话只提示一次，避免 20ms 一帧刷屏
            if (ss.warnedNoTarget.compareAndSet(false, true)) {
                log.warn("[VOICE] 会话尚未协商到有效RTP目标(audioIp:audioPort)，暂停发送等待协商/学习: key={} state={}",
                        s.getKey(), s.getState());
            }
            return false;
        }

        ss.socket.send(new DatagramPacket(rtpPacket, rtpPacket.length,
                new InetSocketAddress(s.getAudioIp(), s.getAudioPort())));

        // 首次成功发送时打印一次实际 RTP 目标，供抓包比对
        if (ss.loggedFirstSend.compareAndSet(false, true)) {
            log.info("[VOICE] RTP实际发送目标: key={} target={}:{} localPort={}",
                    s.getKey(), s.getAudioIp(), s.getAudioPort(), ss.port);
        }
        return true;
    }

    /** 会话 Socket 是否存活（UDP 或任一 TCP 媒体连接存活即为真）。 */
    public boolean isBound(VoiceSession s) {
        SessionSocket ss = sockets.get(s.getKey());
        if (ss == null || ss.socket.isClosed()) {
            return false;
        }
        for (TcpConn c : ss.tcpConns) {
            if (!c.socket.isClosed()) {
                return true;
            }
        }
        return ss.tcpConns.isEmpty();
    }

    /**
     * 会话端口已收到的 UDP 包数（端口级探针计数，含被上层过滤/丢弃的包）。
     *
     * <p>用于周期统计日志：在无法抓包的环境下判定设备是否在往本会话端口发媒体。
     */
    public long receivedCount(VoiceSession s) {
        SessionSocket ss = sockets.get(s.getKey());
        return ss == null ? 0 : ss.packetsReceived.get();
    }

    /** 释放会话 Socket 并归还端口。 */
    public void release(VoiceSession s) {
        String key = s.getKey();
        SessionSocket ss = sockets.remove(key);
        if (ss == null) {
            return;
        }
        closeTcp(ss);
        if (!ss.socket.isClosed()) {
            ss.socket.close();
        }
        portPool.release(ss.port);
        long received = ss.packetsReceived.get();
        log.info("[VOICE] 会话RTP端口已释放: key={} port={} 收包={}",
                key, ss.port, received);
    }

    /** 关闭会话的 TCP 监听与全部媒体连接。 */
    private void closeTcp(SessionSocket ss) {
        ServerSocket server = ss.serverSocket;
        if (server != null && !server.isClosed()) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 关闭失败不影响归还端口
            }
        }
        for (TcpConn c : ss.tcpConns) {
            if (!c.socket.isClosed()) {
                try {
                    c.socket.close();
                } catch (IOException ignored) {
                    // 同上
                }
            }
        }
        ss.tcpConns.clear();
    }

    /**
     * TCP 媒体连接接入：设备按 a=setup:active 语义主动连入会话端口。
     *
     * <p>保留该会话的所有连接：设备 200 OK 应答常含多条 m=audio 且均带
     * a=connection:new（RFC 4145：每条 m-line 独立连接），设备会为
     * "接收平台音频"与"发送设备音频"分别建连。只保留第一条会把收流连接关掉，
     * 表现为"上行通、下行无声"。下行帧发送时写到全部活跃连接，由设备取用。
     */
    private void acceptLoop(SessionSocket ss) {
        ServerSocket server = ss.serverSocket;
        if (server == null) {
            return;
        }
        while (!server.isClosed() && !ss.socket.isClosed()) {
            try {
                Socket conn = server.accept();
                conn.setTcpNoDelay(true);
                TcpConn tc = new TcpConn(conn, conn.getOutputStream());
                ss.tcpConns.add(tc);
                // 连接建立后重置"未连接"警告，便于后续重新提示
                ss.warnedNoTcpConn.set(false);
                log.info("[VOICE] 设备TCP媒体连接已建立: key={} peer={} localPort={} 当前连接数={}",
                        ss.session.getKey(), conn.getRemoteSocketAddress(), ss.port, ss.tcpConns.size());
                Thread rt = new Thread(() -> tcpReadLoop(ss, tc),
                        "voice-rtp-tcp-read-" + ss.session.getKey());
                rt.setDaemon(true);
                rt.start();
            } catch (Exception e) {
                if (!server.isClosed()) {
                    log.warn("[VOICE] TCP媒体接入异常: key={} err={}",
                            ss.session.getKey(), e.getMessage());
                }
                break;
            }
        }
        log.info("[VOICE] TCP媒体监听退出: key={} port={}", ss.session.getKey(), ss.port);
    }

    /**
     * TCP 媒体连接读取：按 RFC 4571 解帧（$ + 通道号 + 2字节大端长度 + RTP），
     * 还原后的 RTP 包与 UDP 路径共用同一上行处理（设备麦克风 → 浏览器）。
     */
    private void tcpReadLoop(SessionSocket ss, TcpConn tc) {
        Socket conn = tc.socket;
        try (InputStream in = conn.getInputStream()) {
            byte[] head = new byte[4];
            while (!conn.isClosed() && !ss.socket.isClosed()) {
                int b = in.read();
                if (b < 0) {
                    break;
                }
                if (b != '$') {
                    // 非帧起始字节：丢弃继续同步（避免一次错位后续全废）
                    continue;
                }
                if (in.read(head, 1, 3) != 3) {
                    break;
                }
                int len = ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                if (len <= 0 || len > 2048) {
                    continue;
                }
                byte[] rtp = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(rtp, read, len - read);
                    if (n < 0) {
                        return;
                    }
                    read += n;
                }
                ss.packetsReceived.incrementAndGet();
                if (ss.firstPacketLogged.compareAndSet(false, true)) {
                    log.info("[VOICE] 会话端口收到首个TCP媒体包: key={} peer={} len={}",
                            ss.session.getKey(), conn.getRemoteSocketAddress(), len);
                }
                // 逐包诊断（前 40 个包）：判断设备发的是音频 RTP 还是 RTCP/其他，
                // 并观察包率与载荷长度，定位"上行无声"是设备不发还是解码问题
                if (ss.tcpDiagCount.incrementAndGet() <= 40) {
                    long nowMs = System.currentTimeMillis();
                    long interval = ss.lastTcpDiagAt == 0 ? -1 : nowMs - ss.lastTcpDiagAt;
                    ss.lastTcpDiagAt = nowMs;
                    StringBuilder hex = new StringBuilder(len * 3);
                    for (int i = 0; i < Math.min(len, 24); i++) {
                        hex.append(String.format("%02x ", rtp[i]));
                    }
                    int pt = rtp[1] & 0xFF;
                    int seq = ((rtp[2] & 0xFF) << 8) | (rtp[3] & 0xFF);
                    long ts = ((long) (rtp[4] & 0xFF) << 24) | ((rtp[5] & 0xFF) << 16)
                            | ((rtp[6] & 0xFF) << 8) | (rtp[7] & 0xFF);
                    long ssrc = ((long) (rtp[8] & 0xFF) << 24) | ((rtp[9] & 0xFF) << 16)
                            | ((rtp[10] & 0xFF) << 8) | (rtp[11] & 0xFF);
                    log.info("[VOICE] TCP媒体包#{}: key={} len={} pt={} seq={} ts={} ssrc={} intervalMs={} head={}",
                            ss.tcpDiagCount.get(), ss.session.getKey(), len, pt, seq, ts,
                            String.format("%08x", ssrc), interval, hex.toString().trim());
                }
                AudioListener l = listener;
                if (l != null) {
                    l.onAudioRtp(ss.session, conn.getInetAddress().getHostAddress(),
                            conn.getPort(), rtp);
                }
            }
        } catch (Exception e) {
            if (!conn.isClosed()) {
                log.warn("[VOICE] TCP媒体读取异常: key={} err={}",
                        ss.session.getKey(), e.getMessage());
            }
        } finally {
            ss.tcpConns.remove(tc);
            try {
                conn.close();
            } catch (IOException ignored) {
                // 同上
            }
            log.info("[VOICE] TCP媒体连接已断开: key={} port={} 剩余连接数={}",
                    ss.session.getKey(), ss.port, ss.tcpConns.size());
        }
    }

    /** 注册 RTP 接收监听器（VoiceService 启动时调用）。 */
    public void setListener(AudioListener l) {
        this.listener = l;
    }

    /** 接收循环：本 Socket 专属于单一会话，直接归属该会话。 */
    private void receiveLoop(SessionSocket ss) {
        byte[] buf = new byte[2048];
        while (!ss.socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ss.socket.receive(p);

                // 端口级探针：不依赖会话状态/长度过滤，任何到达会话端口的 UDP 包都留痕，
                // 用于在无法抓包的环境下判定"设备是否在往本会话端口发媒体"
                ss.packetsReceived.incrementAndGet();
                if (ss.firstPacketLogged.compareAndSet(false, true)) {
                    log.info("[VOICE] 会话端口收到首个UDP包: key={} src={}:{} len={}",
                            ss.session.getKey(), p.getAddress().getHostAddress(),
                            p.getPort(), p.getLength());
                }

                int len = p.getLength();
                if (len < 12) {
                    continue;
                }

                byte[] data = new byte[len];
                System.arraycopy(p.getData(), p.getOffset(), data, 0, len);

                AudioListener l = listener;
                if (l != null) {
                    l.onAudioRtp(ss.session,
                            p.getAddress().getHostAddress(), p.getPort(), data);
                }
            } catch (Exception e) {
                if (!ss.socket.isClosed()) {
                    log.warn("[VOICE] RTP接收异常: key={} err={}",
                            ss.session.getKey(), e.getMessage());
                }
                break;
            }
        }
        log.info("[VOICE] RTP接收线程退出: key={} port={}", ss.session.getKey(), ss.port);
    }

    /** 端口池剩余端口数。 */
    public int availablePorts() {
        return portPool.available();
    }

    @PreDestroy
    public void close() {
        for (SessionSocket ss : sockets.values()) {
            closeTcp(ss);
            if (!ss.socket.isClosed()) {
                ss.socket.close();
            }
            portPool.release(ss.port);
        }
        sockets.clear();
        log.info("[VOICE] 语音RTP端点已关闭");
    }

    /** 会话 Socket 包装。 */
    private static class SessionSocket {
        final VoiceSession session;
        final DatagramSocket socket;
        final int port;

        /** TCP 媒体监听：对讲 SDP 通告 a=setup:passive，设备主动连入该端口 */
        volatile ServerSocket serverSocket;

        /**
         * 设备主动建立的 TCP 媒体连接集合（对讲/广播 RTP 承载）。
         *
         * <p>设备应答可能为每条 m=audio 各建一条连接（a=connection:new），
         * 收流与发流可能是不同连接，因此保留全部连接：下行写到所有连接，
         * 上行从所有连接读取。
         */
        final java.util.List<TcpConn> tcpConns = new java.util.concurrent.CopyOnWriteArrayList<>();

        /** TCP 逐包诊断计数（前 40 个包打明细） */
        final java.util.concurrent.atomic.AtomicInteger tcpDiagCount =
                new java.util.concurrent.atomic.AtomicInteger();

        /** TCP 逐包诊断：上一包时刻（算包间隔） */
        volatile long lastTcpDiagAt;

        /** TCP 连接尚未建立时是否已警告过（每会话一次，防止 20ms 刷屏） */
        final java.util.concurrent.atomic.AtomicBoolean warnedNoTcpConn =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /** 未协商到 RTP 目标时是否已警告过（每会话一次，防止日志刷屏） */
        final java.util.concurrent.atomic.AtomicBoolean warnedNoTarget =
                new java.util.concurrent.atomic.AtomicBoolean();

        /** 是否已打印过首次 RTP 实际发送目标（每会话一次） */
        final java.util.concurrent.atomic.AtomicBoolean loggedFirstSend =
                new java.util.concurrent.atomic.AtomicBoolean();

        /** 端口级收包计数（探针：含被上层过滤/丢弃的包） */
        final java.util.concurrent.atomic.AtomicLong packetsReceived =
                new java.util.concurrent.atomic.AtomicLong();

        /** 是否已打印端口首个 UDP 包（每会话一次） */
        final java.util.concurrent.atomic.AtomicBoolean firstPacketLogged =
                new java.util.concurrent.atomic.AtomicBoolean();

        SessionSocket(VoiceSession session, DatagramSocket socket) {
            this.session = session;
            this.socket = socket;
            this.port = socket.getLocalPort();
        }
    }

    /** 一条设备主动建立的 TCP 媒体连接（Socket + 输出流）。 */
    private static final class TcpConn {
        final Socket socket;
        final OutputStream out;

        TcpConn(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }
    }

    /** RTP 接收监听器。 */
    @FunctionalInterface
    public interface AudioListener {
        void onAudioRtp(VoiceSession session, String remoteIp, int remotePort, byte[] rtpPacket);
    }
}
