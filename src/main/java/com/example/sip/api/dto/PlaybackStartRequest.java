package com.example.sip.api.dto;

/**
 * 回放启动请求体。
 *
 * <p>startTime / endTime 支持三种格式（与内部接口解析语义一致）：
 * <ul>
 *   <li>ISO 格式：2024-01-01T00:00:00 或 2024-01-01 00:00:00（按 +08:00 解析）</li>
 *   <li>Instant 格式：2024-01-01T00:00:00Z</li>
 *   <li>秒级 epoch：1700000000</li>
 * </ul>
 *
 * <p>force：是否强制新建会话。默认 false——已有同设备+通道+时间范围的
 * 存活会话时直接复用（不拆建，保护摄像机）；true 时停止旧会话并新建
 * （旧行为，显式要求重置回放时使用）。
 */
public record PlaybackStartRequest(String gbDeviceId,
                                   String channelId,
                                   String startTime,
                                   String endTime,
                                   Boolean force) {
}
