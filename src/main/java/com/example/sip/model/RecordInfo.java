package com.example.sip.model;

public class RecordInfo {

    /** 设备/通道编码 */
    private String deviceId;

    /** 录像名称 */
    private String name;

    /** 录像文件路径 */
    private String filePath;

    /** 录像地址 */
    private String address;

    /** 录像开始时间（dateTime 格式字符串，标准为 'T' 分隔如 2024-01-01T00:00:00，部分设备用空格分隔） */
    private String startTime;

    /** 录像结束时间（dateTime 格式字符串，标准为 'T' 分隔，部分设备用空格分隔） */
    private String endTime;

    /** 保密属性：0=不涉密，1=涉密 */
    private int secrecy;

    /** 录像类型 */
    private String type;

    /** 录像产生者/录像机ID */
    private String recorderId;

    /** 文件大小 */
    private String fileSize;

    /** 回放地址 */
    private String playbackUri;

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

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public int getSecrecy() {
        return secrecy;
    }

    public void setSecrecy(int secrecy) {
        this.secrecy = secrecy;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRecorderId() {
        return recorderId;
    }

    public void setRecorderId(String recorderId) {
        this.recorderId = recorderId;
    }

    public String getFileSize() {
        return fileSize;
    }

    public void setFileSize(String fileSize) {
        this.fileSize = fileSize;
    }

    public String getPlaybackUri() {
        return playbackUri;
    }

    public void setPlaybackUri(String playbackUri) {
        this.playbackUri = playbackUri;
    }
}