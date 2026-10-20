package com.example.sip.handler;

import io.netty.channel.Channel;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * SIP 活跃信令通道持有器。
 *
 * <p>UDP 无连接：每次收到设备报文时由 {@link SipRequestHandler} 更新当前通道，
 * 需要主动外发 SUBSCRIBE / MESSAGE 的处理器（告警订阅、录像查询）
 * 通过这里取到回包可用的 Channel。
 */
@Component
public class SipChannelHolder {

    private final AtomicReference<Channel> channel = new AtomicReference<>();

    /** 记录最近一次收到报文的通道（后续外发请求经此通道送达设备） */
    public void set(Channel ch) {
        this.channel.set(ch);
    }

    /** 当前可用通道；从未收到过报文时为 null */
    public Channel get() {
        return this.channel.get();
    }
}
