package com.example.sip.netty;

import com.example.sip.config.SipServerProperties;
import com.example.sip.handler.SipRequestHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * Netty UDP 信令服务器：监听配置端口（sip.server.port，默认 5060）接收国标 SIP 报文，
 * 同时提供 send 方法供点播服务（PlayService）主动发送 INVITE / ACK / BYE。
 */
@Component
public class UdpSipServer implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(UdpSipServer.class);

    private final SipServerProperties props;
    private final SipRequestHandler requestHandler;

    private EventLoopGroup group;
    private Channel channel;

    /**
     * @param requestHandler SIP 请求路由器。
     *         MediaService/VoiceService → UdpSipServer → SipRequestHandler → *SignalHandler →
     *         MediaService/VoiceService 构成环，requestHandler 仅在 Netty 收包回调中使用，
     *         @Lazy 注入打破循环。
     */
    public UdpSipServer(SipServerProperties props, @Lazy SipRequestHandler requestHandler) {
        this.props = props;
        this.requestHandler = requestHandler;
    }

    /** 启动单线程 NioEventLoopGroup 并绑定 host:port（SO_REUSEADDR），UDP 报文转交 SipRequestHandler */
    @Override
    public void afterPropertiesSet() throws Exception {
        group = new NioEventLoopGroup(1);
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                        ByteBuf buf = packet.content();
                        byte[] data = new byte[buf.readableBytes()];
                        buf.readBytes(data);
                        requestHandler.handle(data, packet.sender(), ctx);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        log.warn("[28181] UDP 通道异常: {}", cause.getMessage());
                    }
                });
        channel = b.bind(props.getHost(), props.getPort()).sync().channel();
        log.info("[28181] SIP 信令服务器已启动: udp://{}:{} (serverId={})",
                props.getHost(), props.getPort(), props.getId());
    }

    /**
     * 通过信令通道向目标地址发送 SIP 报文（源端口为 5060）。
     * 供点播服务发送 INVITE / ACK / BYE 使用。
     */
    public void send(InetSocketAddress dst, byte[] data) {
        if (channel == null || !channel.isActive()) {
            log.warn("[28181] 信令通道不可用，丢弃发送到 {} 的 {} 字节", dst, data.length);
            return;
        }
        channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(data), dst));
        log.info("[28181] >>> 主动发送 {} 字节 -> {}", data.length, dst);
    }

    /** 关闭 channel 并优雅关闭线程组（Spring 容器关闭时） */
    @Override
    public void destroy() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        log.info("[28181] SIP 信令服务器已停止");
    }
}
