package com.example.sip.service;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 国标设备注册表：管理设备在线状态与心跳超时检测。
 */
@Service
@EnableScheduling
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final SipServerProperties props;

    public DeviceRegistry(SipServerProperties props) {
        this.props = props;
    }

    /** 注册 / 刷新设备（REGISTER 或心跳触发） */
    public Device register(String deviceId, String host, int port) {
        Device d = devices.computeIfAbsent(deviceId, id -> {
            Device nd = new Device();
            nd.setDeviceId(id);
            log.info("[28181] 设备新注册: {} @ {}:{}", id, host, port);
            return nd;
        });
        d.setHost(host);
        d.setPort(port);
        d.setOnline(true);
        d.setLastKeepaliveTime(System.currentTimeMillis());
        return d;
    }

    /** 心跳：设备不存在时自动登记（兼容服务器重启后摄像头仅发心跳的场景） */
    public void keepalive(String deviceId, String host, int port) {
        Device d = devices.computeIfAbsent(deviceId, id -> {
            Device nd = new Device();
            nd.setDeviceId(id);
            log.info("[28181] 设备经心跳自动登记: {} @ {}:{}", id, host, port);
            return nd;
        });
        if (host != null) {
            d.setHost(host);
        }
        if (port > 0) {
            d.setPort(port);
        }
        d.setLastKeepaliveTime(System.currentTimeMillis());
        d.setOnline(true);
    }

    /** 注销（Expires=0） */
    public void unregister(String deviceId) {
        Device d = devices.remove(deviceId);
        if (d != null) {
            log.info("[28181] 设备注销: {}", deviceId);
        }
    }

    /** 更新设备目录通道列表（Catalog 应答到达时调用） */
    public void updateChannels(Device d) {
        if (d != null) {
            d.setCatalogUpdateTime(System.currentTimeMillis());
        }
    }

    /** 按设备 ID 查询（不区分在线状态） */
    public Device get(String deviceId) {
        if (deviceId == null) {
            // ConcurrentHashMap.get(null) 会抛 NPE；此处降级为 null，
            // 由调用方走"设备未注册"的明确错误路径
            return null;
        }
        return devices.get(deviceId);
    }

    /** 全部设备快照（含离线） */
    public List<Device> all() {
        return new ArrayList<>(devices.values());
    }

    /** 在线设备数 */
    public long onlineCount() {
        return devices.values().stream().filter(Device::isOnline).count();
    }

    /** 每 10s 检查心跳：超过 heartbeat-timeout 秒未心跳的设备标记离线（不移除） */
    @Scheduled(fixedDelay = 10000)
    public void checkTimeout() {
        long now = System.currentTimeMillis();
        long timeout = props.getHeartbeatTimeout() * 1000L;
        for (Device d : devices.values()) {
            if (d.isOnline() && now - d.getLastKeepaliveTime() > timeout) {
                d.setOnline(false);
                log.warn("[28181] 设备心跳超时，标记离线: {}", d.getDeviceId());
            }
        }
    }
}
