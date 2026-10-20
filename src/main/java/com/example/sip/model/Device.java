package com.example.sip.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 接入的国标设备（摄像头）。
 */
public class Device {

    /** 设备 20 位国标编码 */
    private String deviceId;

    /** 设备名称（从注册/目录信息中解析，可能为空） */
    private String name;

    /** 设备源 IP */
    private String host;

    /** 设备信令端口 */
    private int port;

    /** 传输协议（UDP/TCP） */
    private String transport = "UDP";

    /** 目录查询得到的通道列表（Catalog 更新） */
    private volatile List<Channel> channels = new CopyOnWriteArrayList<>();

    /** 最近一次目录查询完成时间戳（epoch millis） */
    private volatile long catalogUpdateTime;

    /** 首次注册时间 */
    private LocalDateTime registerTime = LocalDateTime.now();

    /** 最近心跳时间戳（epoch millis） */
    private volatile long lastKeepaliveTime = System.currentTimeMillis();

    /** 是否在线 */
    private volatile boolean online = true;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public LocalDateTime getRegisterTime() {
        return registerTime;
    }

    public void setRegisterTime(LocalDateTime registerTime) {
        this.registerTime = registerTime;
    }

    public long getLastKeepaliveTime() {
        return lastKeepaliveTime;
    }

    public void setLastKeepaliveTime(long lastKeepaliveTime) {
        this.lastKeepaliveTime = lastKeepaliveTime;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public void setChannels(List<Channel> channels) {
        this.channels = new CopyOnWriteArrayList<>(channels);
    }

    public long getCatalogUpdateTime() {
        return catalogUpdateTime;
    }

    public void setCatalogUpdateTime(long catalogUpdateTime) {
        this.catalogUpdateTime = catalogUpdateTime;
    }
}
