package com.example.sip.handler;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * SIP 信令回写工具（响应设备请求 / 主动发送请求）。
 *
 * <p><b>为什么必须包装 DatagramPacket：</b>信令通道是无连接的
 * {@link io.netty.channel.socket.nio.NioDatagramChannel}（一个 Channel 服务所有设备），
 * 出站消息只有 {@link DatagramPacket} 这类 {@code AddressedEnvelope} 才携带
 * 目的 IP + 端口。直接 {@code writeAndFlush(byte[])} 时 Netty 无法确定发给谁，
 * 写路径直接失败（异常被 exceptionCaught 吞掉只剩 WARN 日志），设备表现为
 * 收不到任何响应 —— 注册收不到 200 OK 会反复重注册。
 *
 * <p>发送结果通过 {@link ChannelFutureListener} 检查，失败时告警定位。
 */
public final class SipResponder {

    private static final Logger log = LoggerFactory.getLogger(SipResponder.class);

    /** 工具类禁止实例化 */
    private SipResponder() {
    }

    /**
     * 回写 SIP 响应到请求来源设备。
     *
     * @param ctx   Netty 通道上下文（提供 UDP Channel）
     * @param sender 请求来源设备地址（IP + 端口，取 packet.sender()）
     * @param data  响应报文（SipResponseBuilder 产出）
     */
    public static void reply(ChannelHandlerContext ctx, InetSocketAddress sender, byte[] data) {
        if (ctx == null || sender == null || data == null) {
            log.warn("[28181] 回写参数缺失: ctx={} sender={} len={}",
                    ctx != null, sender, data != null ? data.length : -1);
            return;
        }
        write(ctx.channel(), sender, data, "响应");
    }

    /**
     * 主动发送 SIP 请求到指定设备地址（订阅/查询/点播等平台发起场景）。
     */
    public static void send(Channel channel, InetSocketAddress target, byte[] data) {
        if (channel == null || target == null || data == null) {
            log.warn("[28181] 发送参数缺失: channel={} target={} len={}",
                    channel != null, target, data != null ? data.length : -1);
            return;
        }
        write(channel, target, data, "主动发送");
    }

    /**
     * 回写非 SIP 原始字节（CRLF pong 等 keep-alive 应答）。
     *
     * <p>与 {@link #write} 的区别：成功时不打 INFO 日志（keep-alive 周期可达
     * 每 15~30s 一条，会淹没业务日志），仅在发送失败时告警。
     */
    public static void sendRaw(Channel channel, InetSocketAddress target, byte[] data) {
        if (channel == null || target == null || data == null) {
            return;
        }
        if (!channel.isActive()) {
            log.warn("[28181] 通道不活跃，keep-alive 应答丢弃: {} 字节 -> {}", data.length, target);
            return;
        }
        ChannelFuture future = channel.writeAndFlush(new DatagramPacket(
                Unpooled.wrappedBuffer(data), target));
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("[28181] keep-alive 应答发送失败: {} 字节 -> {} err={}",
                        data.length, target, f.cause().toString());
            }
        });
    }

    /**
     * 统一写出口：包装 DatagramPacket 指定设备地址 → 写 → 检查发送结果。
     */
    private static void write(Channel channel, InetSocketAddress target, byte[] data, String action) {
        if (!channel.isActive()) {
            log.warn("[28181] 通道不活跃，{}丢弃: {} 字节 -> {}", action, data.length, target);
            return;
        }

        ChannelFuture future = channel.writeAndFlush(new DatagramPacket(
                Unpooled.wrappedBuffer(data), target));

        // 检查发送结果：失败时打堆栈，避免异常被 exceptionCaught 静默吞掉
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.warn("[28181] {}发送失败: {} 字节 -> {} err={}",
                        action, data.length, target, f.cause().toString());
            }
        });

        log.info("[28181] >>> {} {} 字节 -> {}", action, data.length, target);
    }
}
