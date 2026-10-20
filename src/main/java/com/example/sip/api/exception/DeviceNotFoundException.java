package com.example.sip.api.exception;

/**
 * 设备不存在（DeviceRegistry 中无该国标编码）。
 *
 * <p>映射为 HTTP 404 + code 40400，见 {@link OpenApiExceptionHandler}。
 */
public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException(String gbDeviceId) {
        super("设备不存在: " + gbDeviceId);
    }
}
