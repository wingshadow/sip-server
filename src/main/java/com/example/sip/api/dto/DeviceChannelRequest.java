package com.example.sip.api.dto;

/**
 * 通用请求体：国标设备编码 + 可选通道编码。
 *
 * <p>gbDeviceId：GB/T 28181 国标设备编码（20 位），必填。
 * channelId：通道编码，缺省时与 gbDeviceId 相同（与内部接口语义一致）。
 */
public record DeviceChannelRequest(String gbDeviceId, String channelId) {
}
