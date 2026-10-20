package com.example.sip.handler;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.Device;
import com.example.sip.service.DeviceRegistry;
import com.example.sip.sip.SipRequestBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设备控制处理器（PTZ 云台 / 预置位，MANSCDP DeviceControl）。
 *
 * <p>NVR 下挂球机的控制入口：平台发 MESSAGE(CmdType=DeviceControl)，
 * 消息体 {@code <PTZCmd>} 为 8 字节十六进制命令，设备以 SIP 200 OK
 * 应答（无 MANSCDP body），故本处理器为「发送即返回」语义，
 * 不做应答聚合；执行结果以设备日志与球机实际动作为准。
 *
 * <h3>PTZCmd 字节布局（GB/T 28181-2016 附录 A.3，与海康/大华/宇视实测一致）</h3>
 * <pre>
 *  字节:  0     1     2        3        4        5        6          7
 *        A5    0F    addr     cmd      hSpeed   vSpeed   zSpeed   checksum
 * </pre>
 * <ul>
 *   <li>{@code cmd} bit0=上仰 bit1=下倾 bit2=左移 bit3=右移
 *      bit4=光圈小(聚焦近) bit5=光圈大(聚焦远) bit6=变倍大(拉近) bit7=变倍小(拉远)；
 *      同段位不可同时置 1。GB/T 28181 无独立聚焦位，厂商以光圈位复用</li>
 *   <li>{@code checksum} = 前 7 字节算术和 mod 256</li>
 *   <li>预置位复用同格式：cmd=0x81 设置 / 0x82 调用 / 0x83 删除，第 4 字节为预置位号</li>
 * </ul>
 *
 * <h3>NVR 场景注意</h3>
 * <p>对 NVR 控制时 {@code DeviceID} 与 Request-URI 均用<b>通道编号</b>
 * （球机挂接在 NVR 通道下），地址字节固定 0x01。
 */
@Component
public class DeviceControlHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceControlHandler.class);

    /** PTZCmd 固定头两字节与地址字节 */
    private static final int PTZ_HEAD_A5 = 0xA5;
    private static final int PTZ_HEAD_0F = 0x0F;
    private static final int PTZ_ADDRESS = 0x01;

    /** 速度默认值与范围（数据1/2/3 均为 0x00~0xFF 全字节相对速度） */
    private static final int DEFAULT_SPEED = 100;
    private static final int MAX_SPEED = 255;

    /** 预置位命令字 */
    private static final int PRESET_SET = 0x81;
    private static final int PRESET_CALL = 0x82;
    private static final int PRESET_DELETE = 0x83;

    /** 方向/光学动作 -> cmd 位（GB/T 28181 控制字：
     *  bit0=上 bit1=下 bit2=左 bit3=右 bit4=光圈小(聚焦近) bit5=光圈大(聚焦远)
     *  bit6=变倍大(拉近) bit7=变倍小(拉远)） */
    private static final Map<String, Integer> PTZ_ACTIONS = Map.of(
            "up", 0x01,
            "down", 0x02,
            "left", 0x04,
            "right", 0x08,
            "focusNear", 0x10,
            "focusFar", 0x20,
            "zoomIn", 0x40,
            "zoomOut", 0x80);

    /** SN 序列（控制事务，与查询/录像查询各自独立） */
    private final AtomicLong snSeq = new AtomicLong(1);

    private final SipServerProperties props;
    private final DeviceRegistry registry;
    private final SipChannelHolder channelHolder;

    public DeviceControlHandler(SipServerProperties props,
                                DeviceRegistry registry,
                                SipChannelHolder channelHolder) {
        this.props = props;
        this.registry = registry;
        this.channelHolder = channelHolder;
    }

    // =========================================================
    // 对外 API
    // =========================================================

    /**
     * 云台方向/光学控制。
     *
     * @param action    right/left/down/up/zoomIn/zoomOut/focusNear/focusFar/stop
     * @param speed     1~255（stop 忽略；方向与光学共用全字节速度）
     * @return success / action / ptzCmd（实际下发的十六进制命令，便于前端展示与排查）
     */
    public Map<String, Object> ptz(String deviceId, String channelId, String action, int speed) {
        Integer bit = PTZ_ACTIONS.get(action);
        boolean stop = "stop".equalsIgnoreCase(action);
        if (bit == null && !stop) {
            throw new IllegalArgumentException("不支持的云台动作: " + action
                    + "，可选: " + PTZ_ACTIONS.keySet() + " / stop");
        }

        int cmd = stop ? 0x00 : bit;
        /* 速度位按动作类别归属（国标语义）：
           方向类(bit0-3)填水平/垂直速度、变倍位清零；
           光学类(bit4-7：变倍/光圈/聚焦)填变倍速度、水平/垂直清零。
           无关速度位非零会被部分固件判为脏数据直接丢弃指令。 */
        int hSpeed, vSpeed, zSpeed;
        if (stop) {
            hSpeed = 0; vSpeed = 0; zSpeed = 0;
        } else if (bit <= 0x08) {
            hSpeed = clamp(speed, 1, MAX_SPEED);
            vSpeed = hSpeed;
            zSpeed = 0;
        } else {
            hSpeed = 0; vSpeed = 0;
            zSpeed = clamp(speed, 1, MAX_SPEED);
        }

        String ptzCmd = buildPtzCmd(cmd, hSpeed, vSpeed, zSpeed);
        return sendControl(deviceId, channelId, "PTZ:" + action, ptzCmd);
    }

    /**
     * 预置位操作。
     *
     * @param op        set=设置 / call=调用 / delete=删除
     * @param presetId  预置位号 1~255
     */
    public Map<String, Object> preset(String deviceId, String channelId, String op, int presetId) {
        int cmd;
        if ("set".equalsIgnoreCase(op)) {
            cmd = PRESET_SET;
        } else if ("call".equalsIgnoreCase(op)) {
            cmd = PRESET_CALL;
        } else if ("delete".equalsIgnoreCase(op)) {
            cmd = PRESET_DELETE;
        } else {
            throw new IllegalArgumentException("不支持的预置位操作: " + op + "，可选: set/call/delete");
        }
        if (presetId < 1 || presetId > 255) {
            throw new IllegalArgumentException("预置位号须在 1~255: " + presetId);
        }

        String ptzCmd = buildPtzCmd(cmd, presetId, 0, 0);
        return sendControl(deviceId, channelId, "Preset:" + op + ":" + presetId, ptzCmd);
    }

    /**
     * 自定义 PTZCmd 逃生口：直接下发 16 位十六进制命令。
     *
     * <p>部分非标设备（改过校验规则、用扩展命令字）与标准布局不符时，
     * 现场可抓包后经此入口透传，无需改代码。
     */
    public Map<String, Object> custom(String deviceId, String channelId, String ptzCmdHex) {
        String hex = ptzCmdHex == null ? "" : ptzCmdHex.trim().toUpperCase();
        if (!hex.matches("[0-9A-F]{16}")) {
            throw new IllegalArgumentException("PTZCmd 须为 16 位十六进制（8 字节）");
        }
        return sendControl(deviceId, channelId, "PTZ:custom", hex);
    }

    // =========================================================
    // 内部
    // =========================================================

    /** 组装 8 字节 PTZCmd 并转大写十六进制 */
    private static String buildPtzCmd(int cmd, int hSpeed, int vSpeed, int zSpeed) {
        byte[] b = new byte[8];
        b[0] = (byte) PTZ_HEAD_A5;
        b[1] = (byte) PTZ_HEAD_0F;
        b[2] = (byte) PTZ_ADDRESS;
        b[3] = (byte) cmd;
        b[4] = (byte) hSpeed;
        b[5] = (byte) vSpeed;
        /* 数据3=变倍速度，国标为全字节 0~255。
           不用 nibble(z<<4)：部分大华固件按低 4 位解读，0xF0 会得到速度 0
           而被整条丢弃；全字节在"整字节/高4位/低4位"三种解读下均非零 */
        b[6] = (byte) zSpeed;

        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += b[i] & 0xFF;
        }
        b[7] = (byte) (sum & 0xFF);

        StringBuilder sb = new StringBuilder(16);
        for (byte value : b) {
            sb.append(String.format("%02X", value & 0xFF));
        }
        return sb.toString();
    }

    private Map<String, Object> sendControl(String deviceId, String channelId,
                                            String desc, String ptzCmd) {
        Device device = registry.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("通道编号不能为空（NVR 场景必须指定球机所在通道）");
        }
        io.netty.channel.Channel channel = channelHolder.get();
        if (channel == null) {
            throw new IllegalStateException("SIP UDP Channel 尚未初始化");
        }

        String sn = String.valueOf(snSeq.incrementAndGet());
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n"
                + "<Control>\r\n"
                + "    <CmdType>DeviceControl</CmdType>\r\n"
                + "    <SN>" + sn + "</SN>\r\n"
                + "    <DeviceID>" + channelId + "</DeviceID>\r\n"
                + "    <PTZCmd>" + ptzCmd + "</PTZCmd>\r\n"
                + "</Control>\r\n";

        SipResponder.send(channel, new InetSocketAddress(device.getHost(), device.getPort()),
                SipRequestBuilder.message(props, device.getHost(), device.getPort(), channelId,
                        UUID.randomUUID().toString(), sn, xml));
        log.info("[28181] >>> 设备控制 {}: device={} channel={} ptzCmd={}",
                desc, deviceId, channelId, ptzCmd);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("deviceId", deviceId);
        r.put("channelId", channelId);
        r.put("ptzCmd", ptzCmd);
        r.put("sn", sn);
        return r;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
