package com.example.sip.service.local;

import com.example.sip.model.StreamSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP-FLV 广播器。
 *
 * <p>FFmpeg 输出：
 *
 * <pre>
 *             FFmpeg stdout
 *                    |
 *                    v
 *              FlvBroadcast
 *               /       \
 *              /         \
 *        Subscriber1   Subscriber2
 *             |             |
 *             v             v
 *          HTTP-FLV      HTTP-FLV
 * </pre>
 *
 * <p>主要解决：
 *
 * <ol>
 *     <li>多个 HTTP-FLV 客户端共享同一个 FFmpeg stdout。</li>
 *     <li>新客户端连接时能够获得 FLV Header / Metadata / AVC Sequence Header。</li>
 *     <li>慢客户端不会阻塞 FFmpeg stdout。</li>
 *     <li>客户端断开后及时释放队列。</li>
 * </ol>
 */
public class FlvBroadcast implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlvBroadcast.class);
    /**
     * FFmpeg stdout 每次读取的数据大小。
     */
    private static final int CHUNK_SIZE = 8192;
    /**
     * 每个客户端最多缓存的数据块数量。
     *
     * <p>
     * 8192 * 300 ≈ 2.4MB
     */
    private static final int QUEUE_SIZE = 300;
    /**
     * Bootstrap 最大缓存大小。
     */
    private static final int BOOTSTRAP_MAX_SIZE = 2 * 1024 * 1024;
    /**
     * Bootstrap 最小准备时间。
     */
    private static final long BOOTSTRAP_WAIT_MILLIS = 3000L;
    /**
     * Bootstrap 判定准备完成的最小数据量。
     *
     * <p>
     * 64KB 对普通 H264 摄像头通常已经足够。
     */
    private static final int BOOTSTRAP_READY_SIZE = 64 * 1024;
    private final StreamSession session;

    /**
     * FFmpeg stdout。
     */
    private final InputStream flvIn;
    /**
     * 所有 HTTP-FLV 客户端。
     */
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /**
     * 广播是否已经关闭。
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * FFmpeg stdout 读取线程。
     */
    private final Thread reader;

    /**
     * Bootstrap 数据。
     *
     * <p>
     * 保存 FFmpeg 启动阶段产生的 FLV 数据，
     * 用于新客户端初始化。
     */
    private final ByteArrayOutputStream bootstrap =
            new ByteArrayOutputStream(64 * 1024);

    /**
     * Bootstrap 是否已经准备完成。
     */
    private volatile boolean bootstrapReady = false;

    /**
     * Bootstrap 是否已经达到最大缓存。
     */
    private volatile boolean bootstrapFull = false;

    /**
     * 广播锁。
     *
     * <p>保护"订阅变更 + Bootstrap 投递"与"实时广播"的互斥：
     * 新客户端的队列顺序恒为 [Bootstrap..., 实时...]，既无数据空洞、
     * 也无"实时块排在 Bootstrap 之前导致 FLV header 不在首字节"的乱序。
     *
     * <p>仅在两处使用（subscribe 的原子入列、readLoop 的广播），
     * 不嵌套获取 bootstrap 锁之外的锁，无死锁风险。
     */
    private final Object broadcastLock = new Object();

    /**
     * 已经读取的总字节数。
     */
    private long totalBytes = 0;

    public FlvBroadcast(StreamSession session, InputStream flvIn) {

        this.session = session;
        this.flvIn = flvIn;
        this.reader = new Thread(this::readLoop, "flv-read-" + session.getCallId());
        this.reader.setDaemon(true);
        this.reader.start();
        log.info("[FLV] 广播器启动: callId={} device={} channel={}", session.getCallId(), session.getDeviceId(),
                session.getChannelId()
        );
    }

    /**
     * 注册一个 HTTP-FLV 播放者（FFmpeg 就绪最多等 3s）。
     * <p>
     * 加锁原子完成"加入 subscribers + 投递 Bootstrap 快照"（与 readLoop 实时广播
     * 同一把 broadcastLock 互斥），保证该客户端队列顺序恒为 [Bootstrap..., 实时...]，
     * 避免 Bootstrap 获取期间产生实时数据空洞。
     */
    public Subscriber subscribe() {
        Subscriber sub = new Subscriber();
        log.debug("[FLV] 客户端开始订阅: callId={} closed={} bootstrapReady={} bootstrapSize={}", session.getCallId(),
                closed.get(), bootstrapReady, getBootstrapSize());

        /**
         * FFmpeg 还没有产生数据时，
         * 最多等待 3 秒。
         */
        waitForBootstrap();
        if (closed.get()) {
            sub.close();
            return sub;
        }

        /**
         * 原子完成：加入广播列表 + 投递 Bootstrap 快照。
         *
         * <p>与 readLoop 的实时广播（同一把 broadcastLock）互斥：
         * 要么本客户端先完整入列 Bootstrap、之后才收实时数据；
         * 要么广播先完成、本客户端自入列起收到的都是 Bootstrap 之后
         * 的实时数据（Bootstrap 快照在入列时一并补齐）。
         * 两种情况下队列顺序都恒为 [Bootstrap..., 实时...]。
         */
        byte[] bootstrapData;
        synchronized (broadcastLock) {
            subscribers.add(sub);
            bootstrapData = getBootstrapData();
            if (bootstrapData.length > 0) {
                sub.offer(bootstrapData);
            }
        }
        if (bootstrapData.length > 0) {
            log.info("[FLV] 新客户端 Bootstrap: callId={} size={}", session.getCallId(), bootstrapData.length);
        } else {
            log.warn("[FLV] 新客户端 Bootstrap 为空: callId={}", session.getCallId());
        }
        log.info("[FLV] 客户端订阅: callId={} subscribers={} bootstrap={}", session.getCallId(), subscribers.size(),
                bootstrapData.length);
        return sub;
    }

    /**
     * 等待 Bootstrap 数据准备。
     */
    private void waitForBootstrap() {
        long deadline = System.currentTimeMillis() + BOOTSTRAP_WAIT_MILLIS;
        synchronized (bootstrap) {
            while (!closed.get() && !bootstrapReady && System.currentTimeMillis() < deadline) {
                try {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) {
                        break;
                    }
                    bootstrap.wait(Math.min(remain, 200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("[FLV] 等待 Bootstrap 被中断: callId={}", session.getCallId());
                    break;
                }
            }
        }
        log.debug("[FLV] Bootstrap 等待结束: callId={} ready={} size={} closed={}", session.getCallId(), bootstrapReady,
                getBootstrapSize(), closed.get());
    }

    /**
     * 获取 Bootstrap 快照。
     */
    private byte[] getBootstrapData() {
        synchronized (bootstrap) {
            return bootstrap.toByteArray();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 当前 HTTP-FLV 客户端数量（PlaybackService 无客户端自动停用判断用）。
     */
    public int getSubscriberCount() {
        return subscribers.size();
    }

    /**
     * 取消一个 HTTP-FLV 播放者。
     */
    public void unsubscribe(Subscriber sub) {
        if (sub == null) {
            return;
        }
        subscribers.remove(sub);
        sub.close();
        log.info("[FLV] 客户端取消订阅: callId={} subscribers={}", session.getCallId(), subscribers.size());
    }

    /**
     * 关闭广播器。
     *
     * <p>
     * FFmpeg 进程由 PlayService 管理，
     * 这里不负责销毁 FFmpeg。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        log.info("[FLV] 关闭广播 1: callId={} subscribers={}", session.getCallId(), subscribers.size());

        for (Subscriber sub : subscribers) {
            sub.close();
        }
        subscribers.clear();

        log.info("[FLV] 关闭广播 2: callId={}", session.getCallId());

        synchronized (bootstrap) {
            bootstrap.notifyAll();
        }

        log.info("[FLV] 关闭广播 3: callId={}", session.getCallId());
    }

    /**
     * 读取 FFmpeg stdout。
     */
    private void readLoop() {
        byte[] buf = new byte[CHUNK_SIZE];
        log.debug("[FLV] stdout读取线程启动: callId={} thread={}", session.getCallId(), Thread.currentThread().getName());
        try (InputStream in = flvIn) {
            while (!closed.get()) {
                int n = in.read(buf);
                if (n < 0) {
                    log.warn("[FLV] ffmpeg stdout EOF: callId={} totalBytes={} subscribers={}", session.getCallId(),
                            totalBytes, subscribers.size());
                    break;
                }

                if (n == 0) {
                    continue;
                }

                totalBytes += n;
                /**
                 * 复制当前数据。
                 *
                 * 因为 buf 会被下一次 read() 覆盖。
                 */
                byte[] chunk = Arrays.copyOf(buf, n);

                /**
                 * =====================================================
                 * 关键修改：
                 *
                 * 每次读取 FFmpeg stdout 后，
                 * 先保存 Bootstrap。
                 * =====================================================
                 */
                appendBootstrap(chunk);

                /**
                 * 广播给所有客户端。
                 *
                 * <p>与 subscribe() 的"入列表 + Bootstrap 投递"互斥，
                 * 避免实时数据插入新客户端队列的 Bootstrap 之前。
                 */
                synchronized (broadcastLock) {
                    for (Subscriber sub : subscribers) {
                        sub.offer(chunk);
                    }
                }

                /**
                 * 启动阶段日志。
                 *
                 * 使用 DEBUG，避免高频 INFO 日志影响
                 * FFmpeg stdout 读取线程时序。
                 */
                if (totalBytes <= 1024 * 1024) {
                    log.debug("[FLV] stdout收到数据: callId={} bytes={} total={} bootstrap={}", session.getCallId(), n,
                            totalBytes, getBootstrapSize());
                }
            }

        } catch (IOException e) {
            if (!closed.get()) {
                log.warn("[FLV] stdout读取异常: callId={} totalBytes={} err={}", session.getCallId(), totalBytes,
                        e.getMessage());
            }
        } finally {
            log.info("[FLV] stdout读取结束: callId={} totalBytes={}", session.getCallId(), totalBytes);
            closeReader();
        }
    }

    /**
     * 保存 Bootstrap。
     *
     * <p>
     * 注意：
     *
     * <ul>
     *     <li>Bootstrap 只保存 FFmpeg 输出最开始的数据。</li>
     *     <li>达到 64KB 后认为 Bootstrap 已经准备完成。</li>
     *     <li>最多保存 2MB。</li>
     * </ul>
     */
    private void appendBootstrap(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return;
        }

        synchronized (bootstrap) {
            /**
             * 已经达到最大值，
             * 后续不再保存。
             */
            if (bootstrapFull) {
                return;
            }
            int current = bootstrap.size();
            int remain = BOOTSTRAP_MAX_SIZE - current;
            if (remain <= 0) {
                bootstrapFull = true;
                bootstrapReady = true;
                log.debug("[FLV] Bootstrap达到最大缓存: callId={} size={}", session.getCallId(), bootstrap.size());
                bootstrap.notifyAll();
                return;
            }

            int writeLength = Math.min(remain, chunk.length);
            bootstrap.write(chunk, 0, writeLength);

            /**
             * Bootstrap 达到 64KB，
             * 唤醒等待的新客户端。
             */
            if (bootstrap.size() >= BOOTSTRAP_READY_SIZE) {
                if (!bootstrapReady) {
                    bootstrapReady = true;
                    log.info("[FLV] Bootstrap 已准备完成: callId={} size={}", session.getCallId(), bootstrap.size());
                }
            }

            /**
             * 达到最大缓存。
             */
            if (bootstrap.size() >= BOOTSTRAP_MAX_SIZE) {
                bootstrapFull = true;
                bootstrapReady = true;
                log.info("[FLV] Bootstrap 已达到最大缓存: callId={} size={}", session.getCallId(), bootstrap.size());
            }

            /**
             * 唤醒 subscribe()。
             */
            bootstrap.notifyAll();
        }
    }

    /**
     * 获取 Bootstrap 大小。
     */
    private int getBootstrapSize() {
        synchronized (bootstrap) {
            return bootstrap.size();
        }
    }

    /**
     * FFmpeg stdout EOF。
     */
    private void closeReader() {
        if (closed.compareAndSet(false, true)) {
            log.info("[FLV] FFmpeg stdout 已结束: callId={}", session.getCallId());
        }
        log.debug("[FLV] Reader关闭客户端: callId={} subscribers={}", session.getCallId(), subscribers.size());
        for (Subscriber sub : subscribers) {
            sub.close();
        }
        synchronized (bootstrap) {
            bootstrap.notifyAll();
        }
    }

    /**
     * HTTP-FLV 客户端订阅者。
     */
    public static class Subscriber {
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        private volatile boolean closed = false;

        /**
         * 放入数据。
         *
         * <p>
         * 慢客户端采用丢旧数据策略：
         *
         * <pre>
         * 队列满
         *   ↓
         * 丢弃最旧 chunk
         *   ↓
         * 加入最新 chunk
         * </pre>
         *
         * <p>
         * 防止一个慢客户端拖死整个 FFmpeg 广播。
         */
        private void offer(byte[] chunk) {

            if (closed || chunk == null || chunk.length == 0) {
                return;
            }

            if (!queue.offer(chunk)) {
                log.warn("[FLV] 客户端队列已满，丢弃旧数据: queueSize={} chunkSize={}", queue.size(), chunk.length);
                /**
                 * 队列满：
                 * 丢弃最旧数据。
                 */
                queue.poll();
                /**
                 * 再加入最新数据。
                 */
                queue.offer(chunk);
            }
        }

        /**
         * 获取一块 FLV 数据。
         */
        public byte[] poll(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        public boolean isClosed() {
            return closed;
        }

        /**
         * 获取当前队列大小。
         */
        public int size() {
            return queue.size();
        }

        private void close() {
            closed = true;
            queue.clear();
        }
    }
}
