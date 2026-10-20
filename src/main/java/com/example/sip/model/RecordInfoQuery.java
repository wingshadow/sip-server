package com.example.sip.model;

import java.util.ArrayList;
import java.util.List;

/**
 * RecordInfo 查询应答（MANSCDP+xml）的聚合结果。
 *
 * <p>设备按 SumNum 分多包返回 RecordInfo MESSAGE，按 SN 聚合进本对象；
 * 条目收齐（或超时）后由调用方读取快照。
 */
public class RecordInfoQuery {

    /** 查询 SN（MANSCDP 序列号，用于匹配请求与应答） */
    private String sn;
    /** 目标设备/通道国标编码 */
    private String deviceId;
    /** 查询起始时间（dateTime 格式字符串，'T' 分隔为合法标准表示） */
    private String startTime;
    /** 查询结束时间（dateTime 格式字符串，'T' 分隔为合法标准表示） */
    private String endTime;
    /** 录像总条数（应答 SumNum），未知或解析失败为 -1 */
    private int sumNum = -1;
    /** 应答 Result 非 ERROR 即视为成功 */
    private boolean success;
    /** 失败原因（应答 Reason，可空） */
    private String reason;
    /** 跨分包聚合的录像条目列表 */
    private final List<RecordInfo> records = new ArrayList<>();

    public String getSn() {
        return sn;
    }

    public void setSn(String sn) {
        this.sn = sn;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
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

    public int getSumNum() {
        return sumNum;
    }

    public void setSumNum(int sumNum) {
        this.sumNum = sumNum;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<RecordInfo> getRecords() {
        return records;
    }

    /** SumNum 已知且条目数已达 SumNum（分包接收完整） */
    public boolean isComplete() {
        return sumNum >= 0 && records.size() >= sumNum;
    }
}