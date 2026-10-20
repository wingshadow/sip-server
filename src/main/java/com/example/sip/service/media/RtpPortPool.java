package com.example.sip.service.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 RTP 端口池（多端口媒体模型核心）。
 *
 * <p>每个点播 / 回放 / 下载会话从池中独占一个 UDP 端口，
 * SIP INVITE SDP 的 m=video/m=audio 通告该端口，设备直接推流到会话端口。
 *
 * <p>特性：
 * <ul>
 *   <li>O(1) 分配 / 释放；</li>
 *   <li>支持并发；</li>
 *   <li>池耗尽时快速失败（由调用方决定拒绝策略）；</li>
 *   <li>端口实际绑定校验由 RtpReceiver 完成（绑定失败自动换端口）。</li>
 * </ul>
 */
public class RtpPortPool {

    private static final Logger log = LoggerFactory.getLogger(RtpPortPool.class);

    private final String name;
    private final int start;
    private final int end;
    private final BitSet allocated;
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final int capacity;

    public RtpPortPool(String name, int start, int end) {
        if (end < start) {
            throw new IllegalArgumentException(
                    "端口池范围非法: start=" + start + " end=" + end);
        }
        this.name = name;
        this.start = start;
        this.end = end;
        this.capacity = end - start + 1;
        this.allocated = new BitSet(capacity);
    }

    public int capacity() {
        return capacity;
    }

    public int available() {
        synchronized (allocated) {
            return capacity - allocated.cardinality();
        }
    }

    /**
     * 分配一个端口；池耗尽返回 -1。
     */
    public int acquire() {
        synchronized (allocated) {
            int free = allocated.nextClearBit(0);
            if (free >= capacity) {
                return -1;
            }
            allocated.set(free);
            return start + free;
        }
    }

    /**
     * 尝试获取指定端口（供重试换端口场景）；已被占用返回 false。
     */
    public boolean tryAcquireSpecific(int port) {
        if (port < start || port > end) {
            return false;
        }
        int idx = port - start;
        synchronized (allocated) {
            if (allocated.get(idx)) {
                return false;
            }
            allocated.set(idx);
            return true;
        }
    }

    /**
     * 释放端口（幂等）。
     */
    public void release(int port) {
        if (port < start || port > end) {
            return;
        }
        int idx = port - start;
        synchronized (allocated) {
            if (allocated.get(idx)) {
                allocated.clear(idx);
                log.debug("[{}] 端口已释放: {}", name, port);
            }
        }
    }

    /**
     * 从游标处轮转分配（均匀分布，避免总是从头部分配）。
     */
    public int acquireRotational() {
        synchronized (allocated) {
            int size = capacity;
            int from = cursor.getAndIncrement() % size;
            for (int i = 0; i < size; i++) {
                int idx = (from + i) % size;
                if (!allocated.get(idx)) {
                    allocated.set(idx);
                    return start + idx;
                }
            }
            return -1;
        }
    }
}
