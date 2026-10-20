package com.example.sip.model;

/**
 * 设备通道（目录查询 Catalog 返回项）。
 */
public class Channel {

    /** 通道编号（国标编码） */
    private String channelId;

    /** 通道名称 */
    private String name;

    /** 设备厂商 */
    private String manufacturer;

    /** 设备型号 */
    private String model;

    /** 通道状态：ON / OFF / UNKNOWN */
    private String status = "UNKNOWN";

    /** 父设备/父通道编号（DeviceID 或通道编码） */
    private String parentId;

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }
}
